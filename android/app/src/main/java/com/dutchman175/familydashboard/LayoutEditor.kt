package com.dutchman175.familydashboard

/**
 * Remote controls for arranging boxes. Arrows choose a box, OK picks it up,
 * OK again switches Move and Resize, and Back puts the box down.
 */
class LayoutEditor(initial: DashboardGrid.Layout) {
    private var saved: DashboardGrid.Layout = initial
    private var draft: DashboardGrid.Layout = initial
    private var open: Boolean = false
    private var selected: String = "photo"
    private var returnBox: String = "photo"
    private var grabbed: Boolean = false
    private var mode: String = "move"
    private var dirty: Boolean = false

    fun isOpen(): Boolean = open

    fun replaceSaved(layout: DashboardGrid.Layout) {
        saved = layout
        if (!open) {
            draft = layout
            dirty = false
        }
    }

    fun open(preferred: String) {
        draft = saved
        dirty = false
        grabbed = false
        mode = "move"
        open = true
        selected = if (DashboardGrid.IDS.contains(preferred)) preferred else "photo"
        returnBox = selected
    }

    fun commit(): DashboardGrid.Layout {
        saved = draft
        dirty = false
        grabbed = false
        open = false
        return saved
    }

    fun cancel() {
        draft = saved
        dirty = false
        grabbed = false
        open = false
    }

    fun left() = nudge(-1, 0)

    fun right() = nudge(1, 0)

    fun up() = nudge(0, -1)

    fun down() = nudge(0, 1)

    /**
     * @return save, cancel, reset, grab, toggle, or none
     */
    fun ok(): String {
        if (!open) {
            return "none"
        }
        if (selected == "save") {
            return "save"
        }
        if (selected == "cancel") {
            return "cancel"
        }
        if (selected == "reset") {
            draft = DashboardGrid.defaultLayout()
            dirty = draft != saved
            grabbed = false
            mode = "move"
            return "reset"
        }
        if (selected == "settings") {
            return "settings"
        }
        if (!DashboardGrid.IDS.contains(selected)) {
            return "none"
        }
        if (!grabbed) {
            grabbed = true
            mode = "move"
            returnBox = selected
            return "grab"
        }
        mode = if (mode == "move") "resize" else "move"
        return "toggle"
    }

    /**
     * @return drop while a box is held, cancel when the editor closes, or none
     */
    fun back(): String {
        if (!open) {
            return "none"
        }
        if (grabbed) {
            grabbed = false
            mode = "move"
            return "drop"
        }
        cancel()
        return "cancel"
    }

    fun stateJson(): String {
        return DashboardGrid.stateJson(
            layout = if (open) draft else saved,
            editing = open,
            selected = if (open) selected else "",
            grabbed = open && grabbed,
            mode = mode,
            dirty = open && dirty
        )
    }

    private fun nudge(dCol: Int, dRow: Int) {
        if (!open) {
            return
        }
        if (grabbed) {
            val next = if (mode == "resize") {
                DashboardGrid.resize(draft, selected, dCol, dRow)
            } else {
                DashboardGrid.move(draft, selected, dCol, dRow)
            }
            if (next != null) {
                draft = next
                dirty = draft != saved
            }
            return
        }
        if (isAction(selected)) {
            if (dRow < 0) {
                selected = returnBox
                return
            }
            if (dCol != 0) {
                selected = cycleAction(dCol)
            }
            return
        }
        val next = DashboardGrid.nearest(draft, selected, dCol, dRow)
        if (next != null) {
            selected = next
            returnBox = next
            return
        }
        if (dRow > 0) {
            selected = "save"
        }
    }

    private fun cycleAction(dCol: Int): String {
        val order = arrayOf("save", "cancel", "reset", "settings")
        val at = order.indexOf(selected).let { if (it < 0) 0 else it }
        val step = if (dCol < 0) -1 else 1
        return order[(at + step + order.size) % order.size]
    }

    private fun isAction(id: String): Boolean {
        return id == "save" || id == "cancel" || id == "reset" || id == "settings"
    }
}
