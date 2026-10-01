package dev.sphc.srccon.core

import dev.sphc.srccon.transport.FakeSerialRelayTransport
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OperationCoordinatorTest {
    private val target = RelayTarget.Demo
    private val snapshot = BuiltInRelayProfiles.vctecNoyito.snapshotFor(target)
    private val on = "A0 01 01 A2"
    private val off = "A0 01 00 A1"

    private class MemoryRiskStore(initial: Boolean = false) : RiskMarkerStore {
        private val risky = AtomicBoolean(initial)
        override suspend fun markAtRisk(snapshot: RelayConfigurationSnapshot) { risky.set(true) }
        override suspend fun clear() { risky.set(false) }
        override suspend fun isAtRisk(): Boolean = risky.get()
    }

    private class Clock : MonotonicClock {
        private val now = AtomicLong(1000)
        override fun elapsedRealtimeMillis(): Long = now.get()
        fun advance(millis: Long) { now.addAndGet(millis) }
    }

    private class Harness(
        val transport: FakeSerialRelayTransport = FakeSerialRelayTransport(),
        val risk: MemoryRiskStore = MemoryRiskStore(),
        val clock: Clock = Clock(),
        sleeper: Sleeper = Sleeper { delay(it) },
        readiness: TargetReadiness = TargetReadiness { },
    ) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinator = OperationCoordinator(scope, transport, readiness, clock, sleeper, risk)
        fun commands(): List<String> = transport.attempts.map { HexCommandParser.format(it.command) }
        fun close() = scope.cancel()
    }

    @Test fun manualPreflightOnStopOffAndOwnership() = runBlocking {
        val h = Harness()
        try {
            assertEquals(OperationResult.Started, h.coordinator.startManual(snapshot))
            assertEquals(listOf(off, on), h.commands())
            assertEquals(OperationPhase.MANUAL_ON, h.coordinator.state.value.phase)
            assertTrue(h.risk.isAtRisk())
            assertTrue(h.coordinator.startSingle(snapshot, 1000, 2000) is OperationResult.Rejected)
            assertEquals(OperationResult.Stopped, h.coordinator.manualOff())
            assertEquals(listOf(off, on, off), h.commands())
            assertEquals(OperationPhase.IDLE, h.coordinator.state.value.phase)
            assertEquals(PhysicalRelayState.OFF_COMMAND_SENT, h.coordinator.state.value.physicalRelayState)
            assertFalse(h.risk.isAtRisk())
        } finally { h.close() }
    }

    @Test fun failedPreflightBlocksOnAndFaultsUnknown() = runBlocking {
        val h = Harness()
        try {
            repeat(3) { h.transport.enqueueFailure() }
            assertTrue(h.coordinator.startManual(snapshot) is OperationResult.Failed)
            assertEquals(listOf(off, off, off), h.commands())
            assertEquals(OperationPhase.FAULT, h.coordinator.state.value.phase)
            assertEquals(PhysicalRelayState.UNKNOWN, h.coordinator.state.value.physicalRelayState)
            assertTrue(h.coordinator.startManual(snapshot) is OperationResult.Rejected)
        } finally { h.close() }
    }

    @Test fun failedOnIsRetriedTwiceThenSafetyOff() = runBlocking {
        val h = Harness()
        try {
            h.transport.enqueueSuccess()
            h.transport.enqueueFailure()
            h.transport.enqueueFailure()
            assertTrue(h.coordinator.startManual(snapshot) is OperationResult.Failed)
            assertEquals(listOf(off, on, on, off), h.commands())
            assertEquals(OperationPhase.IDLE, h.coordinator.state.value.phase)
            assertFalse(h.risk.isAtRisk())
        } finally { h.close() }
    }

    @Test fun riskMarkerIsDurableBeforeOnAndReadinessBlocksAllWrites() = runBlocking {
        val h = Harness()
        try {
            var markedAtOn = false
            h.transport.onSend = { sent ->
                if (HexCommandParser.format(sent.command) == on) markedAtOn = h.risk.isAtRisk()
            }
            assertEquals(OperationResult.Started, h.coordinator.startManual(snapshot))
            assertTrue(markedAtOn)
            assertEquals(OperationResult.Stopped, h.coordinator.stop())
        } finally { h.close() }

        val blocked = Harness(readiness = TargetReadiness { throw SecurityException("USB permission missing") })
        try {
            assertTrue(blocked.coordinator.startManual(snapshot) is OperationResult.Failed)
            assertTrue(blocked.commands().isEmpty())
            assertEquals(OperationPhase.FAULT, blocked.coordinator.state.value.phase)
        } finally { blocked.close() }
    }

    @Test fun stopAfterFailedOnAttemptPreventsSecondOn() = runBlocking {
        val firstOn = CompletableDeferred<Unit>()
        val h = Harness(sleeper = Sleeper { delay(it) })
        try {
            h.transport.onSend = { sent ->
                if (HexCommandParser.format(sent.command) == on) {
                    firstOn.complete(Unit)
                    throw IOException("first ON failed")
                }
            }
            val started = async(Dispatchers.Default) { h.coordinator.startManual(snapshot) }
            withTimeout(2000) { firstOn.await() }
            val stopped = h.coordinator.stop()
            assertEquals(OperationResult.Stopped, stopped)
            started.await()
            assertEquals(listOf(off, on, off), h.commands())
            assertEquals(OperationPhase.IDLE, h.coordinator.state.value.phase)
        } finally { h.close() }
    }

    @Test fun stopWhilePreflightIsPendingNeverStartsOn() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val h = Harness()
        try {
            var first = true
            h.transport.onSend = { sent ->
                if (first && HexCommandParser.format(sent.command) == off) {
                    first = false
                    entered.complete(Unit)
                    withContext(NonCancellable) { release.await() }
                }
            }
            val starting = async(Dispatchers.Default) { h.coordinator.startManual(snapshot) }
            withTimeout(2000) { entered.await() }
            val stopping = async(Dispatchers.Default) { h.coordinator.stop() }
            withTimeout(2000) { h.coordinator.state.first { it.phase == OperationPhase.STOPPING } }
            release.complete(Unit)
            assertEquals(OperationResult.Stopped, stopping.await())
            assertTrue(starting.await() is OperationResult.Rejected)
            assertEquals(listOf(off, off), h.commands())
            assertEquals(OperationPhase.IDLE, h.coordinator.state.value.phase)
        } finally { h.close() }
    }

    @Test fun singleUsesMonotonicDeadlineAndNoCompetingManual() = runBlocking {
        val clock = Clock()
        val h = Harness(sleeper = Sleeper { millis ->
            if (millis > 0) {
                clock.advance(millis)
            }
        }, clock = clock)
        try {
            assertEquals(OperationResult.Started, h.coordinator.startSingle(snapshot, 1500, 2000))
            withTimeout(2000) { h.coordinator.state.first { it.phase == OperationPhase.IDLE && it.physicalRelayState == PhysicalRelayState.OFF_COMMAND_SENT } }
            assertEquals(listOf(off, on, off), h.commands())
            assertEquals(2500L, h.clock.elapsedRealtimeMillis())
        } finally { h.close() }
    }

    @Test fun stopDuringSingleCancelsTimerAndOldTaskCannotOffNewManual() = runBlocking {
        val sleepEntered = CompletableDeferred<Unit>()
        val sleepRelease = CompletableDeferred<Unit>()
        val h = Harness(sleeper = Sleeper { millis ->
            if (millis > 1000) {
                sleepEntered.complete(Unit)
                sleepRelease.await()
            } else delay(millis)
        })
        try {
            assertEquals(OperationResult.Started, h.coordinator.startSingle(snapshot, 10_000, 20_000))
            withTimeout(2000) { sleepEntered.await() }
            assertEquals(OperationResult.Stopped, h.coordinator.stop())
            assertEquals(OperationResult.Started, h.coordinator.startManual(snapshot))
            sleepRelease.complete(Unit)
            delay(50)
            assertEquals(listOf(off, on, off, off, on), h.commands())
            assertEquals(OperationPhase.MANUAL_ON, h.coordinator.state.value.phase)
        } finally { h.close() }
    }

    @Test fun failedOffStaysFaultUntilExplicitRecovery() = runBlocking {
        val h = Harness()
        try {
            assertEquals(OperationResult.Started, h.coordinator.startManual(snapshot))
            repeat(3) { h.transport.enqueueFailure() }
            assertTrue(h.coordinator.stop() is OperationResult.Failed)
            assertEquals(OperationPhase.FAULT, h.coordinator.state.value.phase)
            assertEquals(PhysicalRelayState.UNKNOWN, h.coordinator.state.value.physicalRelayState)
            assertTrue(h.risk.isAtRisk())
            assertTrue(h.coordinator.startManual(snapshot) is OperationResult.Rejected)
            assertEquals(OperationResult.Stopped, h.coordinator.safetyOff(snapshot))
            assertFalse(h.risk.isAtRisk())
            assertEquals(OperationPhase.IDLE, h.coordinator.state.value.phase)
        } finally { h.close() }
    }

    @Test fun persistedRiskAndUsbDetachNeverAutoAccessHardware() = runBlocking {
        val h = Harness(risk = MemoryRiskStore(initial = true))
        try {
            assertTrue(h.coordinator.restoreRiskMarker())
            assertEquals(OperationPhase.FAULT, h.coordinator.state.value.phase)
            assertTrue(h.commands().isEmpty())
        } finally { h.close() }

        val usb = RelayTarget.Usb("/dev/bus/usb/001/002", 0x1A86, 0x7523, 2)
        val usbSnapshot = BuiltInRelayProfiles.vctecNoyito.snapshotFor(usb)
        val h2 = Harness()
        try {
            assertEquals(OperationResult.Started, h2.coordinator.startManual(usbSnapshot))
            h2.coordinator.onUsbDetached(usb)
            assertEquals(OperationPhase.FAULT, h2.coordinator.state.value.phase)
            assertEquals(PhysicalRelayState.UNKNOWN, h2.coordinator.state.value.physicalRelayState)
            assertEquals(listOf(off, on), h2.commands())
            assertTrue(h2.risk.isAtRisk())
        } finally { h2.close() }
    }

    @Test fun invalidDurationOrOffNeverTouchesTransport() = runBlocking {
        val h = Harness()
        try {
            assertTrue(h.coordinator.startSingle(snapshot, 0, 1000) is OperationResult.Rejected)
            assertTrue(h.coordinator.startSingle(snapshot, 2000, 1000) is OperationResult.Rejected)
            val invalidOff = RelayConfigurationSnapshot(target, SerialConfiguration(9600), "user:x", byteArrayOf(1), byteArrayOf())
            assertTrue(h.coordinator.startManual(invalidOff) is OperationResult.Rejected)
            assertTrue(h.commands().isEmpty())
        } finally { h.close() }
    }
}
