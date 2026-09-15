package com.everyday.everyday_glasses

import com.everyday.everyday_glasses.data_models.WidgetDocument
import com.everyday.everyday_glasses.data_models.WidgetTypeId

/**
 * Shared helpers for persistence tests.
 *
 * Widget state is persisted as a per-type codec payload inside a dashboard record,
 * so "does this widget survive a save/load" is a codec round trip.
 */
object WidgetPersistenceTestSupport {

    /** Encodes [state] and decodes it back through the codec registered for [type]. */
    inline fun <reified S : Any> roundTrip(type: WidgetTypeId, state: S): S {
        val codec = WidgetDocument.codecs.getValue(type)
        val decoded = codec.decode(codec.encode(state))
        return requireNotNull(decoded as? S) {
            "${type.persistedId} did not decode back to ${S::class.simpleName}"
        }
    }

    /** Builds a single-widget dashboard, for tests that need a whole document. */
    fun dashboardOf(
        type: WidgetTypeId,
        state: Any,
        instanceId: String = type.persistedId
    ): WidgetDocument.PersistedDashboard = WidgetDocument.PersistedDashboard(
        widgets = listOf(
            WidgetDocument.PersistedWidgetRecord(
                type = type,
                instanceId = instanceId,
                state = WidgetDocument.codecs.getValue(type).encode(state)
            )
        )
    )

    /** Decodes the first record of [type] from a dashboard. */
    inline fun <reified S : Any> WidgetDocument.PersistedDashboard.stateOf(
        type: WidgetTypeId
    ): S {
        val record = requireNotNull(widgets.firstOrNull { it.type == type }) {
            "Dashboard has no ${type.persistedId} widget"
        }
        val decoded = WidgetDocument.codecs.getValue(type).decode(record.state)
        return requireNotNull(decoded as? S) {
            "${type.persistedId} did not decode to ${S::class.simpleName}"
        }
    }
}
