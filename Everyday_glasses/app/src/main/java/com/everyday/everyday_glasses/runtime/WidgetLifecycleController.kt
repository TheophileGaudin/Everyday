package com.everyday.everyday_glasses.runtime

import android.os.Looper
import com.everyday.everyday_glasses.interaction.user_input.TextZoneHost
import com.everyday.everyday_glasses.persistence.WidgetDocument
import com.everyday.everyday_glasses.widgets._common.BaseWidget
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.reflect.KClass

/**
 * The complete runtime contract for one widget type.
 *
 * A registration deliberately joins runtime behavior to the static [WidgetDescriptor]
 * without putting Android objects, factories, or mutable state into the descriptor itself.
 * Registry membership and the cleanup shared by every widget type belong to
 * [WidgetLifecycleController]; a registration only describes what is specific to its type.
 */
class WidgetLifecycleRegistration<W : BaseWidget, S : Any>(
    val descriptor: WidgetDescriptor,
    val widgetType: KClass<W>,
    val stateType: KClass<S>,
    val stateCodec: WidgetDocument.WidgetStateCodec? = null,
    val current: () -> W? = { null },
    val createDefault: (() -> W?)? = null,
    val restore: ((S) -> W?)? = null,
    /**
     * Rebuilds an instance from this type's closed template when it is opened again.
     * Defaults to [restore]; override only when reopening differs from loading a saved dashboard.
     */
    val reopen: ((S) -> W?)? = restore,
    val capture: ((W) -> S?)? = null,
    /** Runs for both newly-created and restored instances, before they join the registry. */
    val configure: (W) -> Unit = {},
    /**
     * Type-specific cleanup when an instance is closed. Runs before the shared removal steps,
     * while the widget is still in the registry.
     */
    val beforeRemove: (W) -> Unit = {},
    /** Releases the resources owned by the instance. Called at most once per instance. */
    val teardown: (W) -> Unit = { it.onDestroy() },
    /**
     * Type-specific fullscreen side effects. Runs after the controller's default, which fits a
     * widget entering fullscreen to the viewport.
     */
    val onFullscreenChanged: (W, Boolean) -> Unit = { _, _ -> },
    /**
     * Type-specific minimize side effects. Runs after the controller's default, which refits a
     * fullscreen widget being restored and rearranges the minimized row.
     */
    val onMinimizeChanged: (W, Boolean) -> Unit = { _, _ -> }
) {
    init {
        require(descriptor.type in WidgetTypeId.entries) { "Unknown descriptor ${descriptor.type}" }
        stateCodec?.let { codec ->
            require(codec.type == descriptor.type) { "Codec ${codec.type} does not belong to ${descriptor.type}" }
            require(codec.stateClass == stateType) {
                "Codec for ${descriptor.type} expects ${codec.stateClass.simpleName}, not ${stateType.simpleName}"
            }
        }
        if (descriptor.policy == WidgetInstancePolicy.SINGLETON) {
            require(createDefault != null || restore != null) {
                "Singleton ${descriptor.type} needs a default creator or restorer"
            }
        }
    }

    companion object {
        /** A singleton type's registration; its type, descriptor and saved state come from [codec]. */
        fun <W : BaseWidget, S : Any> singleton(
            widgetType: KClass<W>,
            codec: WidgetDocument.StateCodec<S>,
            current: () -> W?,
            create: () -> W,
            restore: (S) -> W,
            capture: (W) -> S?,
            reopen: (S) -> W = restore,
            configure: (W) -> Unit = {},
            beforeRemove: (W) -> Unit = {},
            teardown: (W) -> Unit = { it.onDestroy() },
            onFullscreenChanged: (W, Boolean) -> Unit = { _, _ -> },
            onMinimizeChanged: (W, Boolean) -> Unit = { _, _ -> },
        ) = WidgetLifecycleRegistration(
            descriptor = WidgetDescriptors[codec.type],
            widgetType = widgetType,
            stateType = codec.stateClass,
            stateCodec = codec,
            current = current,
            createDefault = create,
            restore = restore,
            reopen = reopen,
            capture = capture,
            configure = configure,
            beforeRemove = beforeRemove,
            teardown = teardown,
            onFullscreenChanged = onFullscreenChanged,
            onMinimizeChanged = onMinimizeChanged,
        )

        /** A multiple-instance type's registration; its type, descriptor and saved state come from [codec]. */
        fun <W : BaseWidget, S : Any> multiple(
            widgetType: KClass<W>,
            codec: WidgetDocument.StateCodec<S>,
            capture: (W) -> S?,
            restore: (S) -> W?,
            configure: (W) -> Unit = {},
            beforeRemove: (W) -> Unit = {},
            onFullscreenChanged: (W, Boolean) -> Unit = { _, _ -> },
        ) = WidgetLifecycleRegistration(
            descriptor = WidgetDescriptors[codec.type],
            widgetType = widgetType,
            stateType = codec.stateClass,
            stateCodec = codec,
            capture = capture,
            restore = restore,
            configure = configure,
            beforeRemove = beforeRemove,
            onFullscreenChanged = onFullscreenChanged,
        )
    }

    internal fun accepts(widget: BaseWidget): Boolean = widgetType.isInstance(widget)

    internal fun reopenCandidate(state: Any): W? {
        val reopen = reopen ?: return null
        if (!stateType.isInstance(state)) return null
        @Suppress("UNCHECKED_CAST")
        return reopen(state as S)
    }

    internal fun restoreCandidate(state: Any): W? {
        val restore = requireNotNull(restore) { "${descriptor.type} has no state restorer" }
        require(stateType.isInstance(state)) {
            "${descriptor.type} cannot restore ${state::class.simpleName}"
        }
        @Suppress("UNCHECKED_CAST")
        return restore(state as S)
    }

    internal fun captureState(widget: BaseWidget): Any? {
        if (!accepts(widget)) return null
        @Suppress("UNCHECKED_CAST")
        return capture?.invoke(widget as W)
    }

    internal fun prepare(widget: BaseWidget, listener: WidgetLifecycleListener) {
        require(accepts(widget)) {
            "${descriptor.type} expects ${widgetType.simpleName}, got ${widget::class.simpleName}"
        }
        @Suppress("UNCHECKED_CAST")
        configure(widget as W)
        widget.lifecycleListener = listener
    }

    internal fun beforeRemove(widget: BaseWidget) {
        if (!accepts(widget)) return
        @Suppress("UNCHECKED_CAST")
        this.beforeRemove.invoke(widget as W)
    }


    internal fun teardown(widget: BaseWidget) {
        if (!accepts(widget)) return
        @Suppress("UNCHECKED_CAST")
        this.teardown.invoke(widget as W)
    }

    internal fun fullscreenChanged(widget: BaseWidget, isFullscreen: Boolean) {
        if (!accepts(widget)) return
        @Suppress("UNCHECKED_CAST")
        this.onFullscreenChanged.invoke(widget as W, isFullscreen)
    }

    internal fun minimizeChanged(widget: BaseWidget, isMinimized: Boolean) {
        if (!accepts(widget)) return
        @Suppress("UNCHECKED_CAST")
        this.onMinimizeChanged.invoke(widget as W, isMinimized)
    }
}

/**
 * Owns structural widget mutations and registry membership, and is the common listener
 * installed on registrations. Every widget it registers receives [changeListener]; every widget
 * it closes goes through the same removal sequence: its closed template is stored, then the
 * registration's `beforeRemove`, [onWidgetRemoving], registry removal, the minimized row is
 * rearranged, [onWidgetRemoved], and finally teardown.
 *
 * It also owns the closed templates (one per type whose descriptor stores one), which are
 * reopened before a default instance is created, and the fullscreen/minimize side effects every
 * widget shares.
 * Every public mutation is batched into one [onChanged] notification.
 */
class WidgetLifecycleController(
    private val runOnUiThread: ((() -> Unit) -> Unit),
    private val onChanged: () -> Unit,
    private val registry: WidgetRegistry,
    private val onMutationStarted: () -> Unit = {},
    private val onMutationFinished: () -> Unit = {},
    private val textZoneHost: TextZoneHost? = null,
    private val changeListener: WidgetChangeListener? = null, /** Installed on every registered widget as its single change channel. */
    private val onWidgetRemoving: (BaseWidget) -> Unit = {},  /** Shared cleanup that still needs the widget's stable ID; runs before registry removal. */
    private val onWidgetRemoved: (BaseWidget) -> Unit = {},   /** Shared follow-up that needs the updated inventory; runs after registry removal. */
    private val viewportSize: () -> Pair<Float, Float> = { 0f to 0f }, /** Current dashboard size, used for fullscreen bounds and the minimized row. */
    private val displayState: () -> WakeSleepManager.DisplayState = { WakeSleepManager.DisplayState.WAKE }, /** Current glasses display state, installed on every registered widget. */
) : WidgetLifecycleListener {
    private companion object {
        const val MINIMIZED_SQUARE_SIZE = 64f
        const val MINIMIZED_PER_ROW = 7
    }
    private val registrations = LinkedHashMap<WidgetTypeId, WidgetLifecycleRegistration<out BaseWidget, out Any>>()
    private val registrationByWidget =
        IdentityHashMap<BaseWidget, WidgetLifecycleRegistration<out BaseWidget, out Any>>()
    private val destroyed = Collections.newSetFromMap(
        IdentityHashMap<BaseWidget, Boolean>()
    )

    private val closedTemplates = LinkedHashMap<WidgetTypeId, WidgetDocument.PersistedWidgetRecord>() /** Runtime source of truth for reopenable widget templates, at most one per type. */
    private var mutationDepth = 0
    private var changedDuringMutation = false

    fun <W : BaseWidget, S : Any> register(registration: WidgetLifecycleRegistration<W, S>) {
        check(registration.descriptor.type !in registrations) {
            "Duplicate widget lifecycle for ${registration.descriptor.type}"
        }
        registrations[registration.descriptor.type] = registration
    }

    /** Ensures that the central runtime catalog covers every descriptor exactly once. */
    fun verifyComplete() {
        val expected = WidgetDescriptors.all.map { it.type }.toSet()
        check(registrations.keys == expected) {
            "Missing widget lifecycle registrations: ${expected - registrations.keys}"
        }
        registrations.values.forEach { registration ->
            val descriptor = WidgetDescriptors[registration.descriptor.type]
            check(descriptor == registration.descriptor) {
                "Registration descriptor does not match catalog for ${descriptor.type}"
            }
        }
    }

    /** Creates a default singleton if absent, returning the live instance when available. */
    fun create(type: WidgetTypeId): BaseWidget? = show(type)

    /** Toggles a singleton's presence. */
    fun toggle(type: WidgetTypeId) = mutate {
        val registration = singletonRegistration(type)
        val existing = registration.current()
        if (existing != null) closeRegistered(existing, registration) else createRegistered(registration)
    }

    /** Ensures a singleton exists; repeated calls are no-ops. */
    fun show(type: WidgetTypeId): BaseWidget? {
        var shown: BaseWidget? = null
        mutate {
            val registration = singletonRegistration(type)
            shown = registration.current() ?: createRegistered(registration)
        }
        return shown
    }

    /** Ensures a singleton is absent; repeated calls are no-ops. */
    fun hide(type: WidgetTypeId) = mutate {
        val registration = singletonRegistration(type)
        registration.current()?.let { closeRegistered(it, registration) }
    }

    /** Restores a widget through its registered state factory. */
    fun restore(type: WidgetTypeId, state: Any): BaseWidget? {
        var restored: BaseWidget? = null
        mutate {
            val registration = requireNotNull(registrations[type]) { "No lifecycle registration for $type" }
            restored = registration.restoreCandidate(state)?.also { candidate ->
                registerCandidate(candidate, registration)
            }
        }
        return restored
    }

    /** Opens a multiple-instance widget using an already registered runtime definition. */
    fun <W : BaseWidget> open(type: WidgetTypeId, factory: () -> W?): W? {
        var opened: W? = null
        mutate {
            val registration = requireNotNull(registrations[type]) { "No lifecycle registration for $type" }
            require(registration.descriptor.policy == WidgetInstancePolicy.MULTIPLE) {
                "$type is not a multiple-instance widget"
            }
            val candidate = factory() ?: return@mutate
            require(registration.accepts(candidate)) {
                "$type expects ${registration.widgetType.simpleName}, got ${candidate::class.simpleName}"
            }
            registerCandidate(candidate, registration)
            opened = candidate
        }
        return opened
    }

    /** Opens a widget through an explicit registration; useful to build the central catalog. */
    fun <W : BaseWidget, S : Any> open(
        registration: WidgetLifecycleRegistration<W, S>,
        factory: () -> W?
    ): W? {
        require(registrations[registration.descriptor.type] === registration) {
            "Register ${registration.descriptor.type} before opening it"
        }
        return open(registration.descriptor.type, factory)
    }

    /**
     * Reopens the closed template of [type] as a new instance, if one is stored.
     * Used by multiple-instance types; singletons reopen their template automatically.
     */
    fun openClosedTemplate(type: WidgetTypeId): BaseWidget? {
        var opened: BaseWidget? = null
        mutate {
            val registration = requireNotNull(registrations[type]) { "No lifecycle registration for $type" }
            opened = reopenClosedTemplate(registration)
        }
        return opened
    }

    /** The stored closed templates, for saving with the dashboard. */
    fun closedTemplateRecords(): List<WidgetDocument.PersistedWidgetRecord> = closedTemplates.values.toList()

    /** Replaces the stored closed templates with those of a loaded dashboard. */
    fun replaceClosedTemplates(records: List<WidgetDocument.PersistedWidgetRecord>) {
        closedTemplates.clear()
        records.forEach { record ->
            if (WidgetDescriptors[record.type].storesClosedTemplate) {
                closedTemplates.putIfAbsent(record.type, record)
            }
        }
    }

    /** Lays out minimized widgets as rows of squares along the bottom edge of the viewport. */
    fun arrangeMinimizedWidgets() {
        val (_, viewportHeight) = viewportSize()
        if (viewportHeight <= 0f) return
        registry.getMinimizedWidgets().forEachIndexed { index, widget ->
            val row = index / MINIMIZED_PER_ROW
            val col = index % MINIMIZED_PER_ROW
            widget.setPosition(col * MINIMIZED_SQUARE_SIZE, viewportHeight - ((row + 1) * MINIMIZED_SQUARE_SIZE))
        }
    }

    /** Closes any tracked widget using its registration. */
    fun close(widget: BaseWidget) = mutate {
        val registration = registrationByWidget[widget]
            ?: error("Widget ${widget::class.simpleName} is not lifecycle-registered")
        closeRegistered(widget, registration)
    }

    /** Closes all lifecycle-registered widgets. */
    fun closeAll() = mutate {
        registrationByWidget.keys.toList().forEach { widget ->
            registrationByWidget[widget]?.let { closeRegistered(widget, it) }
        }
    }

    /** Requests fullscreen for [widget], enforcing the one-fullscreen-widget invariant. */
    fun enterFullscreen(widget: BaseWidget) = mutate {
        if (!widget.isFullscreen) widget.toggleFullscreen()
    }

    override fun onCloseRequested(widget: BaseWidget) {
        if (registrationByWidget.containsKey(widget)) close(widget)
    }

    override fun onFullscreenToggled(widget: BaseWidget, isFullscreen: Boolean) = mutate {
        val registration = registrationByWidget[widget] ?: return@mutate
        if (isFullscreen) {
            val previous = currentFullscreenWidget()
            setFullscreen(widget)
            if (previous != null && previous !== widget && previous.isFullscreen) {
                previous.toggleFullscreen()
            }
        } else if (currentFullscreenWidget() === widget) {
            clearFullscreen(widget)
        }
        fullscreenChanged(widget, registration, isFullscreen)
        markChanged()
    }

    override fun onMinimizeToggled(widget: BaseWidget, isMinimized: Boolean) = mutate {
        val registration = registrationByWidget[widget] ?: return@mutate
        if (isMinimized && currentFullscreenWidget() === widget) {
            // BaseWidget clears fullscreen while minimizing without a second callback.
            clearFullscreen(widget)
            fullscreenChanged(widget, registration, false)
        } else if (!isMinimized && widget.isFullscreen && currentFullscreenWidget() !== widget) {
            val previous = currentFullscreenWidget()
            setFullscreen(widget)
            if (previous != null && previous.isFullscreen) previous.toggleFullscreen()
            fullscreenChanged(widget, registration, true)
        }
        minimizeChanged(widget, registration, isMinimized)
        markChanged()
    }

    /** Captures a registered widget state for the persistence codec migration. */
    fun capture(widget: BaseWidget): Any? = registrationByWidget[widget]?.captureState(widget)

    fun captureRecord(widget: BaseWidget, instanceId: String): WidgetDocument.PersistedWidgetRecord? {
        val registration = registrationByWidget[widget] ?: return null
        val state = registration.captureState(widget) ?: return null
        val codec = registration.stateCodec ?: return null
        return WidgetDocument.PersistedWidgetRecord(widget.type, instanceId, codec.encode(state))
    }

    fun restore(record: WidgetDocument.PersistedWidgetRecord): BaseWidget? {
        val registration = requireNotNull(registrations[record.type]) { "No lifecycle registration for ${record.type}" }
        val codec = requireNotNull(registration.stateCodec) { "No persistence codec for ${record.type}" }
        val state = requireNotNull(codec.decode(record.state)) { "Invalid state for ${record.type}" }
        var restored: BaseWidget? = null
        mutate {
            restored = registration.restoreCandidate(state)?.also { widget ->
                registerCandidate(widget, registration)
                registry.rebindStableId(widget, record.instanceId)
            }
        }
        return restored
    }

    private fun singletonRegistration(type: WidgetTypeId): WidgetLifecycleRegistration<out BaseWidget, out Any> {
        val registration = requireNotNull(registrations[type]) { "No lifecycle registration for $type" }
        registration.requireSingleton()
        return registration
    }

    private fun WidgetLifecycleRegistration<out BaseWidget, out Any>.requireSingleton() {
        require(descriptor.policy == WidgetInstancePolicy.SINGLETON) { "$descriptor is not a singleton" }
    }

    private fun createRegistered(
        registration: WidgetLifecycleRegistration<out BaseWidget, out Any>
    ): BaseWidget? {
        reopenClosedTemplate(registration)?.let { return it }
        val candidate = registration.createDefault?.invoke() ?: return null
        registerCandidate(candidate, registration)
        return candidate
    }

    private fun reopenClosedTemplate(
        registration: WidgetLifecycleRegistration<out BaseWidget, out Any>
    ): BaseWidget? {
        val record = closedTemplates[registration.descriptor.type] ?: return null
        val state = registration.stateCodec?.decode(record.state) ?: return null
        val widget = registration.reopenCandidate(state) ?: return null
        registerCandidate(widget, registration)
        closedTemplates.remove(registration.descriptor.type) // Used up: the next new widget of this type starts from its default.
        return widget
    }

    private fun storeClosedTemplate(
        widget: BaseWidget,
        registration: WidgetLifecycleRegistration<out BaseWidget, out Any>
    ) {
        if (!registration.descriptor.storesClosedTemplate) return
        val codec = registration.stateCodec ?: return
        val state = registration.captureState(widget) ?: return
        closedTemplates[widget.type] = WidgetDocument.PersistedWidgetRecord(
            type = widget.type,
            instanceId = "${widget.type.persistedId}#template",
            state = codec.encode(state)
        )
    }

    /** Shared fullscreen side effect, then the type-specific one. */
    private fun fullscreenChanged(
        widget: BaseWidget,
        registration: WidgetLifecycleRegistration<out BaseWidget, out Any>,
        isFullscreen: Boolean
    ) {
        if (isFullscreen) fitToViewport(widget)
        registration.fullscreenChanged(widget, isFullscreen)
    }

    /** Shared minimize side effects, then the type-specific one. */
    private fun minimizeChanged(
        widget: BaseWidget,
        registration: WidgetLifecycleRegistration<out BaseWidget, out Any>,
        isMinimized: Boolean
    ) {
        if (!isMinimized && widget.isFullscreen) fitToViewport(widget)
        arrangeMinimizedWidgets()
        registration.minimizeChanged(widget, isMinimized)
    }

    private fun fitToViewport(widget: BaseWidget) {
        val (viewportWidth, viewportHeight) = viewportSize()
        if (viewportWidth > 0f && viewportHeight > 0f) {
            widget.setFullscreenBounds(viewportWidth, viewportHeight)
        }
    }

    private fun registerCandidate(
        candidate: BaseWidget,
        registration: WidgetLifecycleRegistration<out BaseWidget, out Any>
    ) {
        try {
            candidate.textZoneHost = textZoneHost
            candidate.changeListener = changeListener
            candidate.glassesDisplayState = displayState()
            registration.prepare(candidate, this)
            registry.add(candidate)
            registrationByWidget[candidate] = registration
            destroyed.remove(candidate)
            if (candidate.isFullscreen && !candidate.isMinimized) {
                val previous = currentFullscreenWidget()
                setFullscreen(candidate)
                if (previous != null && previous !== candidate && previous.isFullscreen) {
                    previous.toggleFullscreen()
                }
                fullscreenChanged(candidate, registration, true)
            }
            markChanged()
        } catch (failure: Throwable) {
            teardownOnce(candidate, registration::teardown)
            throw failure
        }
    }

    private fun closeRegistered(
        widget: BaseWidget,
        registration: WidgetLifecycleRegistration<out BaseWidget, out Any>
    ) {
        registrationByWidget.remove(widget)
        if (currentFullscreenWidget() === widget) clearFullscreen(widget)
        try {
            storeClosedTemplate(widget, registration)
            registration.beforeRemove(widget)
            onWidgetRemoving(widget)
            registry.remove(widget)
            arrangeMinimizedWidgets()
            onWidgetRemoved(widget)
        } finally {
            teardownOnce(widget, registration::teardown)
            widget.changeListener = null
        }
        markChanged()
    }

    private fun <W : BaseWidget> teardownOnce(widget: W, teardown: (W) -> Unit) {
        if (destroyed.add(widget)) teardown(widget)
    }

    private fun currentFullscreenWidget(): BaseWidget? = registry.fullscreenWidget

    private fun setFullscreen(widget: BaseWidget) {
        registry.setFullscreen(widget)
    }

    private fun clearFullscreen(widget: BaseWidget? = null) {
        registry.clearFullscreen(widget)
    }

    private fun markChanged() {
        changedDuringMutation = true
    }

    private fun mutate(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            mutateOnUiThread(action)
        } else {
            runOnUiThread { mutateOnUiThread(action) }
        }
    }

    private fun mutateOnUiThread(action: () -> Unit) {
        if (mutationDepth == 0) onMutationStarted()
        mutationDepth++
        try {
            action()
        } finally {
            mutationDepth--
            if (mutationDepth == 0 && changedDuringMutation) {
                changedDuringMutation = false
                onMutationFinished()
                onChanged()
            } else if (mutationDepth == 0) {
                onMutationFinished()
            }
        }
    }
}