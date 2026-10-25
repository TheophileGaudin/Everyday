package com.everyday.everyday_glasses.widgets.text_editor

import com.everyday.everyday_glasses.interaction.assistant.AssistantToolExecution
import com.everyday.everyday_glasses.interaction.assistant.AssistantToolExecutionCodes
import com.everyday.everyday_glasses.interaction.assistant.AssistantTextContentFormatter
import com.everyday.everyday_glasses.interaction.assistant.AssistantTextPresentationRequest
import com.everyday.everyday_glasses.runtime.UiThreadDispatcher
import com.everyday.everyday_glasses.widgets._common.PresentationSpec
import java.util.concurrent.CompletableFuture

internal class TextAssistantActions(
    private val ui: UiThreadDispatcher,
    private val findTextWidget: (String) -> TextBoxWidget?,
    private val createTextWidget: () -> TextBoxWidget?,
    private val clearEditing: (TextBoxWidget) -> Unit,
    private val presentWidget: (TextBoxWidget, PresentationSpec, Boolean) -> Boolean,
    private val removePresentationTarget: (TextBoxWidget) -> Unit,
) {
    fun present(request: AssistantTextPresentationRequest): CompletableFuture<AssistantToolExecution> {
        val result = CompletableFuture<AssistantToolExecution>()
        val accepted = ui.dispatch {
            result.complete(runCatching { presentOnUi(request) }.getOrElse {
                failure(it.message ?: "Text presentation failed")
            })
        }
        if (!accepted) result.complete(failure("Glasses display is unavailable"))
        return result
    }

    private fun presentOnUi(request: AssistantTextPresentationRequest): AssistantToolExecution {
        val targeted = request.targetWidgetId?.let(findTextWidget)
        val widget = targeted ?: createTextWidget()
        ?: return failure("Text widget could not be created")
        widget.setAssistantContent(AssistantTextContentFormatter.render(request.content, request.sources))
        clearEditing(widget)
        if (!presentWidget(widget, request.spec, targeted == null)) {
            if (targeted == null) removePresentationTarget(widget)
            return failure("Text presentation could not be started")
        }
        return AssistantToolExecution(true, AssistantToolExecutionCodes.SUCCESS, "Text presented")
    }

    private fun failure(message: String) = AssistantToolExecution(
        false, AssistantToolExecutionCodes.EXECUTION_FAILED, message
    )
}