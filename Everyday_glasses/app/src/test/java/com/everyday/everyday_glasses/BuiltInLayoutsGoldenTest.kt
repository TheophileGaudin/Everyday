package com.everyday.everyday_glasses

import com.everyday.everyday_glasses.data_models.WidgetDocument
import com.everyday.everyday_glasses.stores.BuiltInLayouts
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Golden test for the app-delivered layouts.
 *
 * The built-in layouts are pure geometry: for a given screen size they must always
 * produce exactly the same persisted dashboard. Freezing that output in
 * `src/test/resources/builtin-layouts` guards them against accidental drift, and
 * pinned behaviour across the v1 -> v2 persistence migration.
 *
 * A deliberate layout change is re-approved by updating the matching resource file
 * so the new geometry is reviewed as an explicit diff.
 */
class BuiltInLayoutsGoldenTest {

    @Test
    fun `delivered layouts match their frozen dashboards`() {
        for ((label, width, height) in SCREENS) {
            for (name in BuiltInLayouts.NAMES) {
                val dashboard = requireNotNull(BuiltInLayouts.buildDashboard(name, width, height)) {
                    "$name should be a recognised built-in layout"
                }
                val actual = canonical(WidgetDocument.dashboardToJson(dashboard))
                assertEquals("$name @ $label", readGolden(name, label), actual)
            }
        }
    }

    @Test
    fun `unknown layout names and degenerate screens are rejected`() {
        assertEquals(null, BuiltInLayouts.buildDashboard("Nope", 640f, 480f))
        assertEquals(null, BuiltInLayouts.buildDashboard(BuiltInLayouts.STANDARD, 0f, 480f))
        assertEquals(null, BuiltInLayouts.buildDashboard(BuiltInLayouts.STANDARD, 640f, -1f))
    }

    private fun readGolden(name: String, label: String): String {
        val path = "builtin-layouts/$name-$label.json"
        val stream = requireNotNull(javaClass.classLoader?.getResourceAsStream(path)) {
            "Missing golden resource $path"
        }
        return stream.bufferedReader().use { it.readText() }.trim()
    }

    private companion object {
        val SCREENS = listOf(
            Triple("640x480", 640f, 480f),
            Triple("1280x720", 1280f, 720f)
        )

        /** Key-sorted rendering so comparisons do not depend on JSON member order. */
        fun canonical(value: Any?): String = when (value) {
            is JSONObject -> value.keys().asSequence().sorted().joinToString(
                separator = ",", prefix = "{", postfix = "}"
            ) { key -> "\"$key\":" + canonical(value.get(key)) }
            is JSONArray -> (0 until value.length()).joinToString(
                separator = ",", prefix = "[", postfix = "]"
            ) { index -> canonical(value.get(index)) }
            is String -> "\"$value\""
            else -> value.toString()
        }
    }
}
