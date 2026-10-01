package dev.sphc.srccon.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class RelayConfigurationTest {
    @Test fun snapshotOwnsIndependentCommandBytes() {
        val originalOn = byteArrayOf(1, 2)
        val originalOff = byteArrayOf(3, 4)
        val snapshot = RelayConfigurationSnapshot(RelayTarget.Demo, SerialConfiguration(9600), "user:test", originalOn, originalOff)
        originalOn[0] = 9
        originalOff[0] = 9
        snapshot.onCommand[1] = 9
        snapshot.offCommand[1] = 9
        assertArrayEquals(byteArrayOf(1, 2), snapshot.onCommand)
        assertArrayEquals(byteArrayOf(3, 4), snapshot.offCommand)
    }

    @Test fun builtInProfileIsReadOnlyAndDuplicateChangesOwnership() {
        val builtIn = BuiltInRelayProfiles.vctecNoyito
        assertEquals(ProfileOwner.BUILT_IN, builtIn.owner)
        assertEquals(9600, builtIn.serial.baudRate)
        assertArrayEquals(byteArrayOf(0xA0.toByte(), 1, 1, 0xA2.toByte()), builtIn.snapshotFor(RelayTarget.Demo).onCommand)
        val duplicate = builtIn.duplicateAsUser("user:observatory")
        assertEquals(ProfileOwner.USER, duplicate.owner)
        assertEquals("user:observatory", duplicate.id)
        assertFalse(builtIn.id == duplicate.id)
        assertThrows(IllegalArgumentException::class.java) { builtIn.duplicateAsUser("builtin:collision") }
    }

    @Test fun rejectsInvalidTargetAndMissingOff() {
        val invalidTarget = RelayConfigurationSnapshot(
            RelayTarget.Usb("", 0x1A86, 0x7523, 2), SerialConfiguration(9600), "user:x", byteArrayOf(1), byteArrayOf(0),
        )
        assertThrows(IllegalArgumentException::class.java) { invalidTarget.validate() }
        val invalidOff = RelayConfigurationSnapshot(RelayTarget.Demo, SerialConfiguration(9600), "user:x", byteArrayOf(1), byteArrayOf())
        assertThrows(IllegalArgumentException::class.java) { invalidOff.validate() }
    }
}
