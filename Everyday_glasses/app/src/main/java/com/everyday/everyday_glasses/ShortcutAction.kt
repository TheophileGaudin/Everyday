package com.everyday.everyday_glasses

/**
 * An action that can be assigned to a lemon-hover-control slice or surfaced in the
 * shortcuts settings menu. Identifiers are stable strings used in persistence.
 */
sealed class ShortcutAction {
    abstract val id: String
    abstract val label: String

    object ToggleStatus : ShortcutAction() {
        override val id = "toggle:status"
        override val label = "Toggle: System"
    }
    object ToggleLocation : ShortcutAction() {
        override val id = "toggle:location"
        override val label = "Toggle: Location/Weather"
    }
    object ToggleCalendar : ShortcutAction() {
        override val id = "toggle:calendar"
        override val label = "Toggle: Calendar"
    }
    object ToggleFinance : ShortcutAction() {
        override val id = "toggle:finance"
        override val label = "Toggle: Finance"
    }
    object ToggleNews : ShortcutAction() {
        override val id = "toggle:news"
        override val label = "Toggle: News"
    }
    object ToggleSpeedometer : ShortcutAction() {
        override val id = "toggle:speedometer"
        override val label = "Toggle: Speedometer"
    }
    object ToggleSubtitle : ShortcutAction() {
        override val id = "toggle:subtitle"
        override val label = "Toggle: Subtitles"
    }
    object ToggleMirror : ShortcutAction() {
        override val id = "toggle:mirror"
        override val label = "Toggle: Screen Mirror"
    }
    object CreateText : ShortcutAction() {
        override val id = "create:text"
        override val label = "Create: Text widget"
    }
    object CreateBrowser : ShortcutAction() {
        override val id = "create:browser"
        override val label = "Open: Browser"
    }
    object OpenYouTubeHistory : ShortcutAction() {
        override val id = "open:youtube"
        override val label = "Open: YouTube history"
    }

    /**
     * Loads a layout by name. Names matching the well-known [BUILTIN_LAYOUTS] map to programmatic
     * layouts; other names are looked up in the user-saved layouts file.
     */
    data class Layout(val name: String) : ShortcutAction() {
        override val id: String = "layout:$name"
        override val label: String = "Layout: $name"
    }

    companion object {
        const val BUILTIN_LAYOUT_STANDARD = "Standard"
        const val BUILTIN_LAYOUT_GROCERIES = "Groceries"
        const val BUILTIN_LAYOUT_SUBTITLES = "Subtitles"
        const val BUILTIN_LAYOUT_MIRROR = "Mirror"

        val BUILTIN_LAYOUTS: List<String> = listOf(
            BUILTIN_LAYOUT_STANDARD,
            BUILTIN_LAYOUT_GROCERIES,
            BUILTIN_LAYOUT_SUBTITLES,
            BUILTIN_LAYOUT_MIRROR
        )

        /** Maximum number of slices the lemon can hold before navigation becomes impractical. */
        const val MAX_LEMON_SLICES = 10

        /** Built-in actions (excluding saved-layout loads, which are discovered at runtime). */
        val BUILTIN: List<ShortcutAction> = listOf(
            ToggleStatus,
            ToggleLocation,
            ToggleCalendar,
            ToggleFinance,
            ToggleNews,
            ToggleSpeedometer,
            ToggleSubtitle,
            ToggleMirror,
            CreateText,
            CreateBrowser,
            OpenYouTubeHistory
        ) + BUILTIN_LAYOUTS.map { Layout(it) }

        val DEFAULT_LEMON_SHORTCUTS: List<ShortcutAction> = listOf(
            Layout(BUILTIN_LAYOUT_STANDARD),
            Layout(BUILTIN_LAYOUT_GROCERIES),
            Layout(BUILTIN_LAYOUT_SUBTITLES),
            Layout(BUILTIN_LAYOUT_MIRROR),
            CreateText,
            CreateBrowser
        )

        fun fromId(id: String): ShortcutAction? {
            val trimmed = id.trim()
            if (trimmed.startsWith("layout:")) {
                val name = trimmed.removePrefix("layout:")
                if (name.isBlank()) return null
                return Layout(name)
            }
            return BUILTIN.firstOrNull { it.id == trimmed }
        }
    }
}
