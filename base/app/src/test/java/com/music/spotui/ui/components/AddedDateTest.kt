package com.music.spotui.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class AddedDateTest {
    @Test
    fun parsesSpotifyIsoInstantOffsetAndPlainDates() {
        assertEquals(LocalDate.of(2024, 3, 7), AddedDate.parse("2024-03-07T12:00:00Z"))
        assertEquals(LocalDate.of(2024, 3, 7), AddedDate.parse("2024-03-07"))
        assertNull(AddedDate.parse("1970-01-01T00:00:00Z"))
        assertNull(AddedDate.parse("1970-01-01"))
        assertNull(AddedDate.parse("not a date"))
    }

    @Test
    fun formatsAsDayMonthYearAndSkipsBlank() {
        assertEquals("07.03.2024", AddedDate.format("2024-03-07"))
        assertEquals(10, AddedDate.format("2024-03-07T12:00:00Z")!!.length)
        assertNull(AddedDate.format("1970-01-01T00:00:00Z"))
        assertNull(AddedDate.format("1970-01-01"))
        assertNull(AddedDate.format(""))
        assertNull(AddedDate.format(null))
        assertNull(AddedDate.format("garbage"))
    }
}
