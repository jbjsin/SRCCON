package dev.sphc.srccon.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HexCommandParserTest {
    @Test fun acceptsWindowsSeparatorsAndPrefixes() {
        val expected = byteArrayOf(0xA0.toByte(), 0x01, 0x00, 0xA1.toByte())
        for (input in listOf(
            "A0 01 00 A1", "A0,01,00,A1", "A0-01-00-A1", "A0;01;00;A1",
            "A0:01:00:A1", "0xA0 0X01 0x00 0xa1", " A0, 1 : 0 ; A1 ",
        )) {
            assertArrayEquals(input, expected, HexCommandParser.parse(input))
        }
        assertEquals("A0 01 00 A1", HexCommandParser.format(expected))
    }

    @Test fun rejectsEmptyInvalidAndOverlongCommands() {
        for (input in listOf("", "   ", ",-;:", "0x", "0xGG", "A00", "0x123", "01, nope")) {
            assertThrows(input, HexCommandFormatException::class.java) { HexCommandParser.parse(input) }
        }
        assertThrows(HexCommandFormatException::class.java) {
            HexCommandParser.parse(List(65) { "00" }.joinToString(" "))
        }
        assertEquals(64, HexCommandParser.parse(List(64) { "FF" }.joinToString(" ")).size)
    }
}
