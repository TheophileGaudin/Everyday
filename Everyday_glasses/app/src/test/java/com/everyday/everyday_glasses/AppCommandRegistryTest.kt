package com.everyday.everyday_glasses

import com.everyday.everyday_glasses.interaction.commands.AppCommandCapabilities
import com.everyday.everyday_glasses.interaction.commands.AppCommandExecutor
import com.everyday.everyday_glasses.interaction.commands.AppCommandResult
import com.everyday.everyday_glasses.interaction.commands.AppCommandRegistry
import com.everyday.everyday_glasses.interaction.commands.CommandAvailability
import com.everyday.everyday_glasses.interaction.commands.CommandValidationResult
import com.everyday.everyday_glasses.persistence.ShortcutsStore
import com.everyday.everyday_glasses.runtime.WidgetTypeId
import com.everyday.everyday_glasses.widgets.news.NewsPlugin
import com.everyday.everyday_glasses.widgets.system_status.StatusBarPlugin
import com.everyday.shared.command.AppCommandArguments
import com.everyday.shared.command.AppCommandInvocation
import com.everyday.shared.command.AppCommandProtocol
import com.everyday.shared.command.CommandScrollDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class AppCommandRegistryTest {
    private val registry = AppCommandRegistry { listOf("Work") }

    @Test
    fun `registry IDs are unique and retains durable shortcut IDs`() {
        val ids = registry.definitions().map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        val stableDigest = MessageDigest.getInstance("SHA-256")
            .digest(ids.joinToString("\n").toByteArray())
            .joinToString("") { "%02x".format(it) }
        assertEquals("0330aacddf7f8ee06af6f05ba665e8fb089bdaf94cf3fabb2d81c874aa224fbe", stableDigest)
    }

    @Test
    fun `typed parameters validate ranges enums URL schemes and required values`() {
        assertTrue(registry.validate(AppCommandInvocation(
            registry.setBrightness.id, AppCommandArguments.Brightness(0.5f)
        )) is CommandValidationResult.Valid)
        assertTrue(registry.validate(AppCommandInvocation(
            registry.setBrightness.id, AppCommandArguments.Brightness(1.1f)
        )) is CommandValidationResult.Invalid)
        assertTrue(registry.validate(AppCommandInvocation(
            registry.scroll.id, AppCommandArguments.Scroll(CommandScrollDirection.DOWN, 120f)
        )) is CommandValidationResult.Valid)
        assertTrue(registry.validate(AppCommandInvocation(
            registry.scroll.id, AppCommandArguments.Scroll(CommandScrollDirection.DOWN, 0f)
        )) is CommandValidationResult.Invalid)
        assertTrue(registry.validate(AppCommandInvocation(
            registry.openUrl.id, AppCommandArguments.Url("https://example.com/path")
        )) is CommandValidationResult.Valid)
        assertTrue(registry.validate(AppCommandInvocation(
            registry.openUrl.id, AppCommandArguments.Url("file:///etc/passwd")
        )) is CommandValidationResult.Invalid)
        assertTrue(registry.validate(AppCommandInvocation("does:not_exist")) is CommandValidationResult.Invalid)
    }

    @Test
    fun `shortcut persistence resolves the same commands after restart`() {
        val before = listOf(registry.invocation(registry.toggleStatus), registry.invocation("layout:Work"))
        val after = ShortcutsStore.decode(
            ShortcutsStore.encode(before),
            AppCommandRegistry { listOf("Work") })
        assertEquals(before, after)
    }

    @Test
    fun `menu and shortcut routes produce the same invocation`() {
        val menuInvocation = registry.invocation(registry.toggleStatus)
        val shortcutInvocation = registry.defaultShortcutInvocations
            .firstOrNull { it.commandId == registry.toggleStatus.id }
            ?: registry.invocation(registry.toggleStatus)
        assertEquals(menuInvocation, shortcutInvocation)
    }

    @Test
    fun `machine declarations and serialization derive from registry metadata`() {
        val declarations = registry.toolDeclarations()
        assertEquals(registry.definitions().map { it.id }, declarations.map { it.name })
        val invocations = listOf(
            registry.invocation(registry.toggleStatus),
            AppCommandInvocation(registry.setBrightness.id, AppCommandArguments.Brightness(0.4f)),
            AppCommandInvocation(registry.scroll.id, AppCommandArguments.Scroll(CommandScrollDirection.UP, 25f)),
            AppCommandInvocation(registry.openUrl.id, AppCommandArguments.Url("https://example.com")),
            AppCommandInvocation(registry.enterText.id, AppCommandArguments.Text("hello")),
            AppCommandInvocation("metadata-only", AppCommandArguments.Widget("map")),
            AppCommandInvocation("metadata-only", AppCommandArguments.Layout("Standard"))
        )
        invocations.forEach { invocation ->
            assertEquals(invocation, AppCommandProtocol.decode(AppCommandProtocol.encode(invocation)))
        }
    }

    @Test
    fun `executor validates availability and crosses UI boundary exactly once`() {
        val capabilities = FakeCapabilities()
        var dispatchCalls = 0
        val executor = AppCommandExecutor(registry, capabilities) { action ->
            dispatchCalls++
            action()
        }

        val result = executor.execute(registry.invocation(registry.toggleStatus))
        assertTrue(result is AppCommandResult.Success)
        assertEquals(1, dispatchCalls)
        assertEquals(1, capabilities.calls)
        assertEquals(1, capabilities.finishedCalls)
        assertEquals(StatusBarPlugin.TYPE, capabilities.widget)

        val showNews = registry.assistantDefinitions().first { it.id == "show:news" }
        val showResult = executor.execute(registry.invocation(showNews))
        assertTrue(showResult is AppCommandResult.Success)
        assertEquals(2, dispatchCalls)
        assertEquals(2, capabilities.calls)
        assertEquals(2, capabilities.finishedCalls)
        assertEquals(true, capabilities.visible)
        assertEquals(NewsPlugin.TYPE, capabilities.widget)

        capabilities.available = false
        val unavailable = executor.execute(registry.invocation(registry.toggleNews))
        assertTrue(unavailable is AppCommandResult.Failure)
        assertEquals(2, dispatchCalls)
        assertEquals(2, capabilities.calls)
        assertEquals(2, capabilities.finishedCalls)

        val unknown = executor.execute(AppCommandInvocation("unknown"))
        assertTrue(unknown is AppCommandResult.Failure)
        assertFalse(unknown is AppCommandResult.Success)
        assertEquals(2, dispatchCalls)
    }

    @Test
    fun `executor reports application failure when capability cannot complete command`() {
        val capabilities = FakeCapabilities().apply { loadLayoutSucceeds = false }
        val executor =
            AppCommandExecutor(registry, capabilities) { action -> action() }

        val result = executor.execute(registry.invocation("layout:Work"))

        assertTrue(result is AppCommandResult.Failure)
        assertEquals(
            AppCommandResult.Failure.Code.APPLICATION_FAILURE,
            (result as AppCommandResult.Failure).code,
        )
        assertEquals(1, capabilities.calls)
        assertEquals(1, capabilities.finishedCalls)
    }

    @Test
    fun `executor reports application failure when dispatch has no completed result`() {
        val capabilities = FakeCapabilities()
        val executor = AppCommandExecutor(registry, capabilities) { null }

        val result = executor.execute(registry.invocation(registry.toggleStatus))

        assertTrue(result is AppCommandResult.Failure)
        assertEquals(
            AppCommandResult.Failure.Code.APPLICATION_FAILURE,
            (result as AppCommandResult.Failure).code,
        )
        assertEquals(0, capabilities.calls)
        assertEquals(0, capabilities.finishedCalls)
    }

    private class FakeCapabilities : AppCommandCapabilities {
        var available = true
        var calls = 0
        var widget: WidgetTypeId? = null
        var visible: Boolean? = null
        var loadLayoutSucceeds = true
        var finishedCalls = 0
        override fun isAvailable(requirement: CommandAvailability) = available
        override fun toggleWidget(type: WidgetTypeId) { calls++; widget = type }
        override fun setWidgetVisible(type: WidgetTypeId, visible: Boolean) {
            calls++
            widget = type
            this.visible = visible
        }
        override fun createTextWidget() { calls++ }
        override fun createBrowserWidget() { calls++ }
        override fun openYouTubeHistory() { calls++ }
        override fun loadLayout(name: String): Boolean {calls++; return loadLayoutSucceeds}
        override fun toggleLayoutLock() { calls++ }
        override fun saveLayout() { calls++ }
        override fun saveLayoutAs() { calls++ }
        override fun openFilePicker() { calls++ }
        override fun openSettings() { calls++ }
        override fun requestClose() { calls++ }
        override fun setBrightness(value: Float) { calls++ }
        override fun scroll(direction: CommandScrollDirection, amount: Float) { calls++ }
        override fun openUrl(url: String) { calls++ }
        override fun enterText(text: String) { calls++ }
        override fun commandFinished() { finishedCalls++ }
    }
}
