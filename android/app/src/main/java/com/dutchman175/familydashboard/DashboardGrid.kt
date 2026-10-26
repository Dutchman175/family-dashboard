package com.dutchman175.familydashboard

import org.json.JSONObject

/**
 * 12 by 8 screen grid. The default puts the photo on the left, the notes
 * strip under it, and the clock, weather, and Upcoming down the right side,
 * which is the v1.4.0 arrangement, plus a joke strip and a bedtime
 * countdown on the right. The photo and the notes strip stay the same
 * size. Cells never overlap and never leave the grid.
 */
object DashboardGrid {
    const val COLS = 12
    const val ROWS = 8
    val IDS = listOf("photo", "notes", "clock", "weather", "bedtime", "joke", "calendar")

    data class Cell(val col: Int, val row: Int, val cols: Int, val rows: Int)

    data class Layout(
        val photo: Cell,
        val notes: Cell,
        val clock: Cell,
        val weather: Cell,
        val calendar: Cell,
        val joke: Cell,
        val bedtime: Cell
    ) {
        fun cell(id: String): Cell {
            return when (id) {
                "photo" -> photo
                "notes" -> notes
                "clock" -> clock
                "weather" -> weather
                "calendar" -> calendar
                "joke" -> joke
                "bedtime" -> bedtime
                else -> throw IllegalArgumentException("box")
            }
        }

        fun with(id: String, cell: Cell): Layout {
            return when (id) {
                "photo" -> copy(photo = cell)
                "notes" -> copy(notes = cell)
                "clock" -> copy(clock = cell)
                "weather" -> copy(weather = cell)
                "calendar" -> copy(calendar = cell)
                "joke" -> copy(joke = cell)
                "bedtime" -> copy(bedtime = cell)
                else -> this
            }
        }
    }

    fun defaultLayout(): Layout {
        return Layout(
            photo = Cell(0, 0, 8, 7),
            notes = Cell(0, 7, 8, 1),
            clock = Cell(8, 0, 4, 2),
            weather = Cell(8, 2, 4, 2),
            bedtime = Cell(8, 4, 4, 1),
            joke = Cell(8, 5, 4, 1),
            calendar = Cell(8, 6, 4, 2)
        )
    }

    fun fits(layout: Layout): Boolean {
        val cells = IDS.map { layout.cell(it) }
        if (cells.any { !onScreen(it) }) {
            return false
        }
        for (i in cells.indices) {
            for (j in i + 1 until cells.size) {
                if (overlaps(cells[i], cells[j])) {
                    return false
                }
            }
        }
        return true
    }

    fun move(layout: Layout, id: String, dCol: Int, dRow: Int): Layout? {
        if (!IDS.contains(id) || (dCol == 0 && dRow == 0)) {
            return null
        }
        val cell = layout.cell(id)
        return accept(layout, id, cell.copy(col = cell.col + dCol, row = cell.row + dRow))
    }

    fun resize(layout: Layout, id: String, dCols: Int, dRows: Int): Layout? {
        if (!IDS.contains(id) || (dCols == 0 && dRows == 0)) {
            return null
        }
        val cell = layout.cell(id)
        return accept(
            layout,
            id,
            cell.copy(cols = cell.cols + dCols, rows = cell.rows + dRows)
        )
    }

    /**
     * When the notes strip is hidden and it sits directly under the photo,
     * the photo grows into that row. Otherwise the notes space stays empty.
     */
    fun display(layout: Layout, notesVisible: Boolean): Layout {
        if (notesVisible) {
            return layout
        }
        val photo = layout.photo
        val notes = layout.notes
        val underPhoto = notes.col == photo.col &&
            notes.cols == photo.cols &&
            notes.row == photo.row + photo.rows
        if (!underPhoto) {
            return layout
        }
        return layout.copy(photo = photo.copy(rows = photo.rows + notes.rows))
    }

    fun parse(raw: String?): Layout? {
        if (raw.isNullOrBlank()) {
            return null
        }
        return try {
            val obj = JSONObject(raw)
            val photo = parseCell(obj.getJSONObject("photo"))
            val notes = parseCell(obj.getJSONObject("notes"))
            val clock = parseCell(obj.getJSONObject("clock"))
            val weather = parseCell(obj.getJSONObject("weather"))
            val calendar = parseCell(obj.getJSONObject("calendar"))
            val joke = parseCell(obj.getJSONObject("joke"))
            val layout = if (obj.has("bedtime")) {
                Layout(
                    photo,
                    notes,
                    clock,
                    weather,
                    calendar,
                    joke,
                    parseCell(obj.getJSONObject("bedtime"))
                )
            } else {
                insertBedtime(photo, notes, clock, weather, calendar, joke) ?: return null
            }
            if (fits(layout)) layout else null
        } catch (thrown: Exception) {
            null
        }
    }

    /**
     * Older saved layouts have no bedtime box. An empty strip is used when
     * one exists. Otherwise the bottom row of weather, Upcoming, the clock,
     * or the joke is given to bedtime. The photo and the notes strip are
     * left alone.
     */
    private fun insertBedtime(
        photo: Cell,
        notes: Cell,
        clock: Cell,
        weather: Cell,
        calendar: Cell,
        joke: Cell
    ): Layout? {
        val existing = listOf(photo, notes, clock, weather, calendar, joke)
        val gap = findGap(existing)
        if (gap != null) {
            val placed = Layout(photo, notes, clock, weather, calendar, joke, gap)
            if (fits(placed)) {
                return placed
            }
        }
        val donors = listOf(
            "weather" to weather,
            "calendar" to calendar,
            "clock" to clock,
            "joke" to joke
        )
        for (donor in donors) {
            val cell = donor.second
            if (cell.rows < 2) {
                continue
            }
            val slot = Cell(cell.col, cell.row + cell.rows - 1, cell.cols, 1)
            val placed = Layout(photo, notes, clock, weather, calendar, joke, slot)
                .with(donor.first, cell.copy(rows = cell.rows - 1))
            if (fits(placed)) {
                return placed
            }
        }
        return null
    }

    private fun findGap(cells: List<Cell>): Cell? {
        var row = 0
        while (row < ROWS) {
            var col = 0
            while (col < COLS) {
                if (covered(cells, col, row)) {
                    col++
                    continue
                }
                var end = col
                while (end < COLS && !covered(cells, end, row)) {
                    end++
                }
                val width = end - col
                if (width >= 3) {
                    return Cell(col, row, minOf(4, width), 1)
                }
                col = end
            }
            row++
        }
        return null
    }

    private fun covered(cells: List<Cell>, col: Int, row: Int): Boolean {
        val spot = Cell(col, row, 1, 1)
        return cells.any { overlaps(it, spot) }
    }

    fun encode(layout: Layout): String {
        val sb = StringBuilder()
        sb.append('{')
        for (i in IDS.indices) {
            if (i > 0) {
                sb.append(',')
            }
            sb.append('"')
            sb.append(IDS[i])
            sb.append('"')
            sb.append(':')
            sb.append(encodeCell(layout.cell(IDS[i])))
        }
        sb.append('}')
        return sb.toString()
    }

    fun stateJson(
        layout: Layout,
        editing: Boolean,
        selected: String,
        grabbed: Boolean,
        mode: String,
        dirty: Boolean
    ): String {
        val sb = StringBuilder()
        sb.append("{\"editing\":")
        sb.append(if (editing) "true" else "false")
        sb.append(",\"selected\":\"")
        sb.append(if (selected.all { it.isLetter() }) selected else "")
        sb.append("\",\"grabbed\":")
        sb.append(if (grabbed) "true" else "false")
        sb.append(",\"mode\":\"")
        sb.append(if (mode == "resize") "resize" else "move")
        sb.append("\",\"dirty\":")
        sb.append(if (dirty) "true" else "false")
        sb.append(",\"boxes\":")
        sb.append(encode(layout))
        sb.append('}')
        return sb.toString()
    }

    fun nearest(layout: Layout, fromId: String, dCol: Int, dRow: Int): String? {
        if (dCol == 0 && dRow == 0) {
            return null
        }
        val from = layout.cell(fromId)
        val fx = from.col + from.cols / 2.0
        val fy = from.row + from.rows / 2.0
        var best: String? = null
        var bestScore = Double.MAX_VALUE
        for (id in IDS) {
            if (id == fromId) {
                continue
            }
            val cell = layout.cell(id)
            val vx = (cell.col + cell.cols / 2.0) - fx
            val vy = (cell.row + cell.rows / 2.0) - fy
            val primary = vx * dCol + vy * dRow
            if (primary <= 0.01) {
                continue
            }
            val secondary = kotlin.math.abs(vx * dRow - vy * dCol)
            if (secondary > primary * 1.25) {
                continue
            }
            val score = primary + secondary * 0.5
            if (score < bestScore) {
                bestScore = score
                best = id
            }
        }
        return best
    }

    private fun accept(layout: Layout, id: String, cell: Cell): Layout? {
        val next = layout.with(id, cell)
        return if (fits(next)) next else null
    }

    private fun onScreen(cell: Cell): Boolean {
        return cell.cols >= 1 &&
            cell.rows >= 1 &&
            cell.col >= 0 &&
            cell.row >= 0 &&
            cell.col + cell.cols <= COLS &&
            cell.row + cell.rows <= ROWS
    }

    private fun overlaps(a: Cell, b: Cell): Boolean {
        return a.col < b.col + b.cols &&
            b.col < a.col + a.cols &&
            a.row < b.row + b.rows &&
            b.row < a.row + a.rows
    }

    private fun parseCell(obj: JSONObject): Cell {
        return Cell(
            obj.getInt("col"),
            obj.getInt("row"),
            obj.getInt("cols"),
            obj.getInt("rows")
        )
    }

    private fun encodeCell(cell: Cell): String {
        return "{\"col\":${cell.col},\"row\":${cell.row},\"cols\":${cell.cols},\"rows\":${cell.rows}}"
    }
}
