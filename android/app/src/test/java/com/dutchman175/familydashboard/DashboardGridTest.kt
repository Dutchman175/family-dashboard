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
        assertEquals(DashboardGrid.Cell(0, 0, 6, 1), layout.clock)
        assertEquals(DashboardGrid.Cell(0, 1, 6, 6), layout.photo)
        assertEquals(DashboardGrid.Cell(0, 7, 6, 1), layout.notes)
        assertEquals(DashboardGrid.Cell(6, 0, 6, 3), layout.calendar)
        assertEquals(DashboardGrid.Cell(6, 3, 6, 3), layout.weather)
        assertEquals(DashboardGrid.Cell(6, 6, 6, 1), layout.bedtime)
        assertEquals(DashboardGrid.Cell(6, 7, 6, 1), layout.joke)
        assertEquals(DashboardGrid.defaultLayout(), DashboardGrid.upgrade(DashboardGrid.v15Layout()))
        assertEquals(layout, DashboardGrid.upgrade(layout))
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
        assertNull(DashboardGrid.resize(layout, "joke", 0, -1))
        assertNull(DashboardGrid.resize(layout, "notes", 0, -1).let { resized ->
            // Shrinking the notes height below 1 is refused. A valid shrink is tested below.
            if (resized == null) null else resized
        })
    }

    @Test
    fun allowsAMoveThatStaysClear() {
        val shrunk = DashboardGrid.resize(DashboardGrid.defaultLayout(), "clock", -1, 0)
        assertEquals(5, shrunk!!.clock.cols)
        val moved = DashboardGrid.move(shrunk, "clock", 1, 0)
        assertEquals(1, moved!!.clock.col)
        assertTrue(DashboardGrid.fits(moved))
    }

    @Test
    fun hiddenNotesLetThePhotoFillThatStrip() {
        val layout = DashboardGrid.defaultLayout()
        val filled = DashboardGrid.display(layout, notesVisible = false)
        assertEquals(7, filled.photo.rows)
        assertEquals(1, filled.photo.row)
        val underPhoto = """{"photo":{"col":0,"row":0,"cols":6,"rows":4},"notes":{"col":0,"row":4,"cols":6,"rows":1},"clock":{"col":6,"row":0,"cols":6,"rows":3},"weather":{"col":6,"row":3,"cols":6,"rows":3},"calendar":{"col":0,"row":5,"cols":6,"rows":2},"bedtime":{"col":6,"row":6,"cols":6,"rows":1},"joke":{"col":6,"row":7,"cols":6,"rows":1}}"""
        val parked = DashboardGrid.parse(underPhoto)!!
        assertEquals(5, DashboardGrid.display(parked, notesVisible = false).photo.rows)
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
        val legacy = """{"photo":{"col":0,"row":0,"cols":8,"rows":7},"notes":{"col":0,"row":7,"cols":8,"rows":1},"clock":{"col":8,"row":0,"cols":4,"rows":2},"weather":{"col":8,"row":2,"cols":4,"rows":3},"joke":{"col":8,"row":5,"cols":4,"rows":1},"calendar":{"col":8,"row":6,"cols":4,"rows":2}}"""
        assertEquals(DashboardGrid.v15Layout(), DashboardGrid.parse(legacy))
        val overlap = encoded.replace("\"clock\":{\"col\":0,\"row\":0", "\"clock\":{\"col\":0,\"row\":1")
        assertNull(DashboardGrid.parse(overlap))
    }

    @Test
    fun arrowsChooseTheNeighbor() {
        val layout = DashboardGrid.defaultLayout()
        assertEquals("notes", DashboardGrid.nearest(layout, "photo", 0, 1))
        assertEquals("weather", DashboardGrid.nearest(layout, "photo", 1, 0))
        assertEquals("photo", DashboardGrid.nearest(layout, "clock", 0, 1))
        assertEquals("bedtime", DashboardGrid.nearest(layout, "weather", 0, 1))
        assertEquals("joke", DashboardGrid.nearest(layout, "bedtime", 0, 1))
        assertNull(DashboardGrid.nearest(layout, "joke", 0, 1))
        assertEquals("weather", DashboardGrid.nearest(layout, "calendar", 0, 1))
        assertNull(DashboardGrid.nearest(layout, "notes", 0, 1))
    }

    @Test
    fun movingIntoAnotherBoxSwapsTheirPlaces() {
        val layout = DashboardGrid.defaultLayout()
        val clockDown = DashboardGrid.move(layout, "clock", 0, 1)!!
        assertEquals(DashboardGrid.Cell(0, 6, 6, 1), clockDown.clock)
        assertEquals(DashboardGrid.Cell(0, 0, 6, 6), clockDown.photo)
        assertTrue(DashboardGrid.fits(clockDown))
        val jokeUp = DashboardGrid.move(layout, "joke", 0, -1)!!
        assertEquals(DashboardGrid.Cell(6, 6, 6, 1), jokeUp.joke)
        assertEquals(DashboardGrid.Cell(6, 7, 6, 1), jokeUp.bedtime)
        assertTrue(DashboardGrid.fits(jokeUp))
        val notesRight = DashboardGrid.move(layout, "notes", 1, 0)!!
        assertEquals(6, notesRight.notes.col)
        assertEquals(0, notesRight.joke.col)
        assertEquals(7, notesRight.joke.row)
        assertTrue(DashboardGrid.fits(notesRight))
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
        assertTrue(editor.stateJson().contains("\"notes\":{\"col\":0"))
        assertEquals("toggle", editor.ok())
        assertTrue(editor.stateJson().contains("\"mode\":\"resize\""))
        editor.left()
        assertTrue(editor.stateJson().contains("\"notes\":{\"col\":0,\"row\":7,\"cols\":5"))
        assertEquals("drop", editor.back())
        assertTrue(editor.isOpen())
        assertFalse(editor.stateJson().contains("\"grabbed\":true"))
    }

    @Test
    fun saveCancelAndReset() {
        val editor = LayoutEditor(DashboardGrid.defaultLayout())
        editor.open("joke")
        assertNull(DashboardGrid.nearest(DashboardGrid.defaultLayout(), "joke", 0, 1))
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
        assertTrue(editor.stateJson().contains("\"cols\":5"))
        assertEquals("drop", editor.back())
        assertTrue(editor.isOpen())
        assertEquals("focus-save", editor.back())
        editor.right()
        assertEquals("cancel", editor.ok())
        editor.cancel()
        assertFalse(editor.isOpen())
        assertTrue(editor.stateJson().contains("\"joke\":{\"col\":6,\"row\":7,\"cols\":6"))
        assertFalse(editor.stateJson().contains("\"cols\":5"))
        editor.open("clock")
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
        assertTrue(editor.stateJson().contains("\"clock\":{\"col\":0"))
        assertFalse(editor.stateJson().contains("\"dirty\":true"))
    }

    @Test
    fun backAndMenuReachSaveAndPlaySavesFromAnyState() {
        val editor = LayoutEditor(DashboardGrid.defaultLayout())
        editor.open("clock")
        assertEquals("grab", editor.ok())
        assertEquals("drop", editor.back())
        assertEquals("focus-save", editor.back())
        assertTrue(editor.stateJson().contains("\"selected\":\"save\""))
        assertTrue(editor.isOpen())
        editor.open("weather")
        assertEquals("grab", editor.ok())
        assertEquals("focus-save", editor.menu())
        assertFalse(editor.stateJson().contains("\"grabbed\":true"))
        assertEquals("save", editor.play())
        assertEquals(DashboardGrid.defaultLayout(), editor.commit())
        assertFalse(editor.isOpen())
    }
}
