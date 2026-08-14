package com.everyday.everyday_phone

import com.everyday.shared.transport.AssistantSessionState

internal data class AssistantPushToTalkPresentation(
    val label: String,
    val showVoiceIcon: Boolean
)

internal fun assistantPushToTalkPresentation(
    state: AssistantSessionState?
): AssistantPushToTalkPresentation = when (state) {
    null,
    AssistantSessionState.UNCONFIGURED,
    AssistantSessionState.IDLE -> AssistantPushToTalkPresentation(label = "", showVoiceIcon = true)
    AssistantSessionState.LISTENING -> AssistantPushToTalkPresentation(label = "LISTEN", showVoiceIcon = false)
    AssistantSessionState.PROCESSING -> AssistantPushToTalkPresentation(label = "WAIT", showVoiceIcon = false)
    AssistantSessionState.SUCCESS -> AssistantPushToTalkPresentation(label = "DONE", showVoiceIcon = false)
    AssistantSessionState.ERROR -> AssistantPushToTalkPresentation(label = "ERROR", showVoiceIcon = false)
}
