package com.everyday.everyday_glasses.runtime

import android.os.Looper
import com.everyday.everyday_glasses.persistence.DashboardPersistence
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
 * The registry/container migration supplies the concrete membership callbacks; this class is
 * intentionally independent of either implementation while that work is in flight.
 */
class WidgetLifecycleRegistration<W : BaseWidget, S : Any>(
    val descriptor: WidgetDescriptor,
    val widgetType: KClass<W>,
    val stateType: KClass<S>,
    val stateCodec: WidgetDocument.WidgetStateCodec? = null,
    val current: () -> W? = { null },
    val createDefault: (() -> W?)? = null,
    val restore: ((S) -> W?)? = null,
    val capture: ((W) -> S?)? = null,
    /** Runs for both newly-created and restored instances, before [register]. */
    val configure: (W) -> Unit = {},
    /** Adds the configured instance to the live widget inventory. */
    val register: (W) -> Unit = {},
    /** Removes the instance from the live widget inventory. */
    val unregister: (W) -> Unit = {},
    /** Releases the resources owned by the instance. Called at most once per instance. */
    val teardown: (W) -> Unit = { it.onDestroy() },
    /** Preserves exceptional per-widget fullscreen side effects. */
    val onFullscreenChanged: (W, Boolean) -> Unit = { _, _ -> },
    /** Preserves exceptional per-widget minimize side effects. */
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

    internal fun accepts(widget: BaseWidget): Boolean = widgetType.isInstance(widget)

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

    internal fun configureAndRegister(widget: BaseWidget, listener: WidgetLifecycleListener) {
        require(accepts(widget)) {
            "${descriptor.type} expects ${widgetType.simpleName}, got ${widget::class.simpleName}"
        }
        @Suppress("UNCHECKED_CAST")
        (widget as W).also {
            configure(it)
            it.lifecycleListener = listener
            register(it)
        }
    }

    internal fun unregister(widget: BaseWidget) {
        if (!accepts(widget)) return
        @Suppress("UNCHECKED_CAST")
        this.unregister.invoke(widget as W)
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
 * Owns structural widget mutations and is the common listener installed on registrations.
 * Every public mutation is batched into one [onChanged] notification.
 */
class WidgetLifecycleController(
    private val runOnUiThread: ((() -> Unit) -> Unit),
    private val onChanged: () -> Unit,
    private val registry: WidgetRegistry,
    private val onMutationStarted: () -> Unit = {},
    private val onMutationFinished: () -> Unit = {}
) : WidgetLifecycleListener {
    private val registrations = LinkedHashMap<WidgetTypeId, WidgetLifecycleRegistration<out BaseWidget, out Any>>()
    private val registrationByWidget =
        IdentityHashMap<BaseWidget, WidgetLifecycleRegistration<out BaseWidget, out Any>>()
    private val destroyed = Collections.newSetFromMap(
        IdentityHashMap<BaseWidget, Boolean>()
    )

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
        registration.fullscreenChanged(widget, isFullscreen)
        markChanged()
    }

    override fun onMinimizeToggled(widget: BaseWidget, isMinimized: Boolean) = mutate {
        val registration = registrationByWidget[widget] ?: return@mutate
        if (isMinimized && currentFullscreenWidget() === widget) {
            // BaseWidget clears fullscreen while minimizing without a second callback.
            clearFullscreen(widget)
            registration.fullscreenChanged(widget, false)
        } else if (!isMinimized && widget.isFullscreen && currentFullscreenWidget() !== widget) {
            val previous = currentFullscreenWidget()
            setFullscreen(widget)
            if (previous != null && previous.isFullscreen) previous.toggleFullscreen()
            registration.fullscreenChanged(widget, true)
        }
        registration.minimizeChanged(widget, isMinimized)
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
                if (state is DashboardPersistence.CommonWidgetFields &&
                    state.isFullscreen && !widget.isMinimized && !widget.isFullscreen
                ) {
                    widget.toggleFullscreen()
                }
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
        val candidate = registration.createDefault?.invoke() ?: return null
        registerCandidate(candidate, registration)
        return candidate
    }

    private fun registerCandidate(
        candidate: BaseWidget,
        registration: WidgetLifecycleRegistration<out BaseWidget, out Any>
    ) {
        try {
            registration.configureAndRegister(candidate, this)
            registrationByWidget[candidate] = registration
            destroyed.remove(candidate)
            if (candidate.isFullscreen && !candidate.isMinimized) {
                val previous = currentFullscreenWidget()
                setFullscreen(candidate)
                if (previous != null && previous !== candidate && previous.isFullscreen) {
                    previous.toggleFullscreen()
                }
                registration.fullscreenChanged(candidate, true)
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
            registration.unregister(widget)
        } finally {
            teardownOnce(widget, registration::teardown)
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