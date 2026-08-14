package com.everyday.everyday_glasses

import android.os.Looper
import kotlin.reflect.KClass

/** Runtime behavior associated with a singleton descriptor. */
class SingletonWidgetLifecycle(
    val descriptor: WidgetDescriptor,
    val current: () -> BaseWidget?,
    val closeCurrent: (BaseWidget) -> Unit,
    val restoreClosedTemplate: () -> BaseWidget?,
    val createDefault: () -> BaseWidget?
)

/** Runtime behavior associated with a multi-instance descriptor. */
class MultiWidgetLifecycle<W : BaseWidget>(
    val descriptor: WidgetDescriptor,
    val register: (W) -> Unit,
    val unregister: (W) -> Unit,
    val teardown: (W) -> Unit = { it.onDestroy() }
)

class WidgetStateRestorer<S : Any>(
    val descriptor: WidgetDescriptor,
    private val stateType: KClass<S>,
    private val restoreState: (S) -> Unit
) {
    internal fun restore(state: Any) {
        require(stateType.isInstance(state)) {
            "${descriptor.type} cannot restore ${state::class.simpleName}"
        }
        @Suppress("UNCHECKED_CAST")
        restoreState(state as S)
    }
}

/**
 * Owns structural widget mutations. Descriptors retain per-type construction and codec
 * knowledge; the controller supplies the invariant lifecycle ordering.
 */
class WidgetLifecycleController(
    private val runOnUiThread: ((() -> Unit) -> Unit),
    private val onChanged: () -> Unit
) {
    private val singletons = LinkedHashMap<WidgetTypeId, SingletonWidgetLifecycle>()
    private val restorers = LinkedHashMap<WidgetTypeId, WidgetStateRestorer<*>>()
    private val destroyed = java.util.Collections.newSetFromMap(
        java.util.IdentityHashMap<BaseWidget, Boolean>()
    )

    fun register(lifecycle: SingletonWidgetLifecycle) {
        require(lifecycle.descriptor.policy == WidgetInstancePolicy.SINGLETON)
        check(singletons.put(lifecycle.descriptor.type, lifecycle) == null) {
            "Duplicate widget lifecycle for ${lifecycle.descriptor.type}"
        }
    }

    fun <S : Any> register(restorer: WidgetStateRestorer<S>) {
        check(restorers.put(restorer.descriptor.type, restorer) == null) {
            "Duplicate widget state restorer for ${restorer.descriptor.type}"
        }
    }

    fun verifyComplete() {
        val expected = WidgetDescriptors.all
            .filter { it.policy == WidgetInstancePolicy.SINGLETON }
            .map { it.type }
            .toSet()
        check(singletons.keys == expected) { "Missing singleton lifecycles: ${expected - singletons.keys}" }
        check(restorers.keys == expected) { "Missing singleton state restorers: ${expected - restorers.keys}" }
    }

    fun toggle(type: WidgetTypeId) = mutate {
        val lifecycle = requireNotNull(singletons[type]) { "No lifecycle for $type" }
        lifecycle.current()?.let { existing ->
            if (destroyed.add(existing)) lifecycle.closeCurrent(existing)
            onChanged()
            return@mutate
        }
        (lifecycle.restoreClosedTemplate() ?: lifecycle.createDefault())?.let(destroyed::remove)
        onChanged()
    }

    /** Ensure a singleton exists; repeated calls are no-ops. */
    fun show(type: WidgetTypeId) = mutate {
        val lifecycle = requireNotNull(singletons[type]) { "No lifecycle for $type" }
        if (lifecycle.current() != null) return@mutate
        (lifecycle.restoreClosedTemplate() ?: lifecycle.createDefault())?.let(destroyed::remove)
        onChanged()
    }

    /** Ensure a singleton is absent; repeated calls are no-ops. */
    fun hide(type: WidgetTypeId) = mutate {
        val lifecycle = requireNotNull(singletons[type]) { "No lifecycle for $type" }
        val existing = lifecycle.current() ?: return@mutate
        if (destroyed.add(existing)) lifecycle.closeCurrent(existing)
        onChanged()
    }

    fun restore(type: WidgetTypeId, state: Any) = mutate {
        requireNotNull(restorers[type]) { "No state restorer for $type" }.restore(state)
    }

    fun <W : BaseWidget> open(lifecycle: MultiWidgetLifecycle<W>, factory: () -> W?): W? {
        check(lifecycle.descriptor.policy == WidgetInstancePolicy.MULTIPLE)
        var opened: W? = null
        mutate {
            val candidate = factory() ?: return@mutate
            try {
                lifecycle.register(candidate)
                destroyed.remove(candidate)
                opened = candidate
            } catch (failure: Throwable) {
                teardownOnce(candidate, lifecycle.teardown)
                throw failure
            }
            onChanged()
        }
        return opened
    }

    fun <W : BaseWidget> close(lifecycle: MultiWidgetLifecycle<W>, widget: W) = mutate {
        lifecycle.unregister(widget)
        teardownOnce(widget, lifecycle.teardown)
        onChanged()
    }

    fun closeAll(
        widgets: List<BaseWidget>,
        teardown: (BaseWidget) -> Unit,
        clearRegistry: () -> Unit
    ) = mutate {
        widgets.forEach { teardownOnce(it, teardown) }
        clearRegistry()
        onChanged()
    }

    private fun <W : BaseWidget> teardownOnce(widget: W, teardown: (W) -> Unit) {
        if (destroyed.add(widget)) teardown(widget)
    }

    private fun mutate(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else runOnUiThread(action)
    }
}
