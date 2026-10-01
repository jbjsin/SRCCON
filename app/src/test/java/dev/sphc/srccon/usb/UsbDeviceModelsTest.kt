package dev.sphc.srccon.usb

import dev.sphc.srccon.core.RelayTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbDeviceModelsTest {
    private fun descriptor(
        name: String,
        deviceId: Int,
        serial: String? = null,
    ) = UsbDeviceDescriptor(
        key = UsbDeviceKey(name, 0x1a86, 0x7523, deviceId),
        productName = "USB-SERIAL CH340",
        manufacturerName = "QinHeng",
        serialNumber = serial,
        driverFamily = UsbSerialDriverFamily.CH34X,
        portCount = 1,
        permissionGranted = serial != null,
        deviceClass = 0,
        interfaceClasses = listOf(255),
    )

    @Test fun fingerprintRoundTripPreservesUnicodeAndSerial() {
        val fingerprint = UsbDeviceFingerprint(0x1a86, 0x7523, "秦恒", "USB: serial", "A/B:42")
        assertEquals(fingerprint, UsbDeviceFingerprint.decode(fingerprint.encode()))
        assertNull(UsbDeviceFingerprint.decode("v2:1:2"))
    }

    @Test fun previousDeviceHintNeverChoosesAmongIdenticalDevices() {
        val saved = descriptor("/dev/usb/1", 1).fingerprintHint()
        val discovered = listOf(descriptor("/dev/usb/2", 2), descriptor("/dev/usb/3", 3))
        assertTrue(previousDeviceHints(saved, discovered).isEmpty())
    }

    @Test fun previousDeviceHintUsesSerialWhenAvailableAndFallsBackToUniqueModel() {
        val saved = descriptor("/dev/usb/1", 1, "relay-1").fingerprintHint()
        val exact = descriptor("/dev/usb/2", 2, "relay-1")
        val other = descriptor("/dev/usb/3", 3, "relay-2")
        assertEquals(
            PreviousDeviceHint.EXACT_SERIAL,
            previousDeviceHints(saved, listOf(exact, other))[exact.key],
        )
        val noPermission = descriptor("/dev/usb/2", 2)
        assertEquals(
            PreviousDeviceHint.POSSIBLE_MODEL_MATCH,
            previousDeviceHints(saved, listOf(noPermission))[noPermission.key],
        )
        assertTrue(previousDeviceHints(saved, listOf(other)).isEmpty())
    }

    @Test fun detachedDeviceMustMatchCurrentAttachmentNotOnlyVidPid() {
        val target = RelayTarget.Usb("/dev/usb/1", 0x1a86, 0x7523, 7, 0)
        assertTrue(matchesUsbDetach(target, UsbDeviceKey("/dev/usb/1", 0x1a86, 0x7523, 7)))
        assertFalse(matchesUsbDetach(target, UsbDeviceKey("/dev/usb/2", 0x1a86, 0x7523, 8)))
    }

    @Test fun selectingPortBuildsAttachmentTargetOnlyOnExplicitCall() {
        val device = descriptor("/dev/usb/1", 1)
        val target = device.targetForPort(0)
        assertEquals(device.key.deviceName, target.deviceName)
        assertEquals(0, target.portIndex)
    }
}
