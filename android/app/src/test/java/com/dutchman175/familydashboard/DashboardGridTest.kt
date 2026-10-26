package com.dutchman175.familydashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardGridTest {
    @Test
    fun defaultMatchesTheCurrentDashboardAndFillsTheGrid() {
        val layout = DashboardGrid.defaultLayout()
        assertTrue(DashboardGrid.fits(layout))
        assertEquals(DashboardGrid.Cell(0, 0, 8, 7), layout.photo)
        assertEquals(DashboardGrid.Cell(0, 7, 8, 1), layout.notes)
        assertEquals(DashboardGrid.Cell(8, 0, 4, 2), layout.clock)
        assertEquals(DashboardGrid.Cell(8, 2, 4, 3), layout.weather)
        assertEquals(DashboardGrid.Cell(8, 5, 4, 1), layout.joke)
        assertEquals(DashboardGrid.Cell(8, 6, 4, 2), layout.calendar)
        val covered = DashboardGrid.IDS.sumOf { id ->
            val cell = layout.cell(id)
            cell.cols * cell.rows
        }
        assertEquals(DashboardGrid.COLS * DashboardGrid.ROWS, covered)
    }

    @Test
    fun blocksOverlapAndOffScreenMoves() {
        val layout = DashboardGrid.defaultLayout()
        assertNull(DashboardGrid.move(layout, "photo", 1, 0))
        assertNull(DashboardGrid.move(layout, "notes", 0, 1))
        assertNull(DashboardGrid.move(layout, "clock", 0, -1))
        assertNull(DashboardGrid.move(layout, "clock", -1, 0))
        assertNull(DashboardGrid.resize(layout, "photo", 1, 0))
        assertNull(DashboardGrid.resize(layout, "photo", 0, 1))
        assertNull(DashboardGrid.resize(layout, "calendar", 0, 1))
        assertNull(DashboardGrid.move(layout, "joke", 0, -1))
        assertNull(DashboardGrid.resize(layout, "joke", 0, -1))
        assertNull(DashboardGrid.resize(layout, "notes", 0, -1).let { resized ->
            // Shrinking the notes height below 1 is refused. A valid shrink is tested below.
            if (resized == null) null else resized
        })
    }

    @Test
    fun allowsAMoveThatStaysClear() {
        val shrunk = DashboardGrid.resize(DashboardGrid.defaultLayout(), "clock", -1, 0)
        assertEquals(3, shrunk!!.clock.cols)
        val moved = DashboardGrid.move(shrunk, "clock", 1, 0)
        assertEquals(9, moved!!.clock.col)
        assertTrue(DashboardGrid.fits(moved))
    }

    @Test
    fun hiddenNotesLetThePhotoFillThatStrip() {
        val layout = DashboardGrid.defaultLayout()
        val filled = DashboardGrid.display(layout, notesVisible = false)
        assertEquals(8, filled.photo.rows)
        assertEquals(0, filled.photo.row)
        val shorter = DashboardGrid.resize(layout, "calendar", 0, -1)!!
        val narrower = DashboardGrid.resize(shorter, "notes", -4, 0)!!
        val parked = DashboardGrid.move(narrower, "notes", 8, 0)!!
        assertEquals(8, parked.notes.col)
        assertEquals(4, parked.notes.cols)
        val shown = DashboardGrid.display(parked, notesVisible = false)
        assertEquals(7, shown.photo.rows)
    }

    @Test
    fun roundTripRejectsGarbageAndKeepsSecretsOut() {
        val encoded = DashboardGrid.encode(DashboardGrid.defaultLayout())
        assertEquals(DashboardGrid.defaultLayout(), DashboardGrid.parse(encoded))
        assertFalse(encoded.contains("http"))
        assertFalse(encoded.contains("ntfy"))
        assertFalse(encoded.contains("webcal"))
        assertNull(DashboardGrid.parse("not json"))
        assertNull(DashboardGrid.parse("{\"photo\":{\"col\":0,\"row\":0,\"cols\":12,\"rows\":8}}"))
        val overlap = encoded.replace("\"clock\":{\"col\":8", "\"clock\":{\"col\":6")
        assertNull(DashboardGrid.parse(overlap))
    }

    @Test
    fun arrowsChooseTheNeighbor() {
        val layout = DashboardGrid.defaultLayout()
        assertEquals("notes", DashboardGrid.nearest(layout, "photo", 0, 1))
        assertEquals("weather", DashboardGrid.nearest(layout, "photo", 1, 0))
        assertEquals("weather", DashboardGrid.nearest(layout, "clock", 0, 1))
        assertEquals("joke", DashboardGrid.nearest(layout, "weather", 0, 1))
        assertEquals("calendar", DashboardGrid.nearest(layout, "joke", 0, 1))
        assertEquals("notes", DashboardGrid.nearest(layout, "calendar", -1, 0))
        assertNull(DashboardGrid.nearest(layout, "calendar", 0, 1))
    }
}

class LayoutEditorTest {
    @Test
    fun grabMoveResizeAndBack() {
        val editor = LayoutEditor(DashboardGrid.defaultLayout())
        editor.open("photo")
        assertTrue(editor.stateJson().contains("\"editing\":true"))
        assertTrue(editor.stateJson().contains("\"selected\":\"photo\""))
        editor.down()
        assertTrue(editor.stateJson().contains("\"selected\":\"notes\""))
        assertEquals("grab", editor.ok())
        assertTrue(editor.stateJson().contains("\"mode\":\"move\""))
        editor.left()
        assertTrue(editor.stateJson().contains("\"col\":0"))
        assertEquals("toggle", editor.ok())
        assertTrue(editor.stateJson().contains("\"mode\":\"resize\""))
        editor.left()
        assertTrue(editor.stateJson().contains("\"cols\":7"))
        assertEquals("drop", editor.back())
        assertTrue(editor.isOpen())
        assertFalse(editor.stateJson().contains("\"grabbed\":true"))
    }

    @Test
    fun saveCancelAndReset() {
        val editor = LayoutEditor(DashboardGrid.defaultLayout())
        editor.open("calendar")
        assertNull(DashboardGrid.nearest(DashboardGrid.defaultLayout(), "calendar", 0, 1))
        editor.down()
        assertTrue(editor.stateJson().contains("\"selected\":\"save\""))
        editor.right()
        editor.right()
        assertTrue(editor.stateJson().contains("\"selected\":\"reset\""))
        assertEquals("reset", editor.ok())
        assertFalse(editor.stateJson().contains("\"dirty\":true"))
        editor.up()
        editor.ok()
        editor.right()
        assertEquals("toggle", editor.ok())
        editor.left()
        assertTrue(editor.stateJson().contains("\"dirty\":true"))
        assertTrue(editor.stateJson().contains("\"cols\":3"))
        assertEquals("drop", editor.back())
        assertTrue(editor.isOpen())
        assertEquals("cancel", editor.back())
        assertFalse(editor.isOpen())
        assertTrue(editor.stateJson().contains("\"cols\":4"))
        assertFalse(editor.stateJson().contains("\"cols\":3"))
        editor.open("clock")
        editor.down()
        editor.down()
        editor.down()
        editor.down()
        assertTrue(editor.stateJson().contains("\"selected\":\"save\""))
        assertEquals("save", editor.ok())
        val kept = editor.commit()
        assertEquals(DashboardGrid.defaultLayout(), kept)
        assertFalse(editor.isOpen())
    }

    @Test
    fun invalidGrabbedMoveDoesNotChangeTheDraft() {
        val editor = LayoutEditor(DashboardGrid.defaultLayout())
        editor.open("clock")
        editor.ok()
        editor.left()
        assertTrue(editor.stateJson().contains("\"col\":8"))
        assertFalse(editor.stateJson().contains("\"dirty\":true"))
    }
}
