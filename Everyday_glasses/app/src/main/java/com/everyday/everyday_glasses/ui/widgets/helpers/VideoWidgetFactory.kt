package com.everyday.everyday_glasses.ui.widgets.helpers

import android.content.Context
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import com.everyday.everyday_glasses.ui.widgets.VideoWidget
import com.everyday.everyday_glasses.runtime.VideoWidgetListener
import com.everyday.everyday_glasses.video.VideoQueueRepository

/** Constructs VideoWidget instances with their shared queue and callbacks. */
internal class VideoWidgetFactory(
    private val context: Context,
    private val rootLayout: ViewGroup,
    private val containerView: View,
    private val queueRepository: VideoQueueRepository,
    private val onStateChanged: () -> Unit,
    private val onPlaybackVisibilityChanged: (sourceId: String, active: Boolean) -> Unit,
) {
    fun create(
        uri: Uri,
        displayName: String,
        x: Float,
        y: Float,
    ): VideoWidget {
        val sourceId = "local-video-${uri.hashCode()}"
        return VideoWidget(
            context = context,
            rootLayout = rootLayout,
            containerView = containerView,
            contentUri = uri,
            displayName = displayName,
            x = x,
            y = y,
            queueItems = queueRepository.buildQueue(uri, displayName),
        ).apply {
            listener = object : VideoWidgetListener {
                override fun onStateChanged() =
                    this@VideoWidgetFactory.onStateChanged.invoke()

                override fun onPlaybackVisibilityChanged(active: Boolean) {
                    this@VideoWidgetFactory.onPlaybackVisibilityChanged.invoke(sourceId, active)
                }
            }
        }
    }
}