package com.everyday.everyday_glasses.widgets.file_browser

import android.content.Context
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import com.everyday.everyday_glasses.persistence.DashboardPersistence
import com.everyday.everyday_glasses.persistence.WidgetCaptureRestore
import com.everyday.everyday_glasses.runtime.WidgetContext
import com.everyday.everyday_glasses.runtime.WidgetPlugin
import com.everyday.everyday_glasses.runtime.VideoWidgetListener
import com.everyday.everyday_glasses.runtime.WidgetLifecycleRegistration
import com.everyday.everyday_glasses.runtime.WidgetTypeId
import com.everyday.everyday_glasses.widgets.file_browser.video_backend.VideoQueueRepository

/** Constructs VideoWidget instances with their shared queue and playback callback. */
internal class VideoWidgetFactory(
    private val context: Context,
    private val rootLayout: ViewGroup,
    private val containerView: View,
    private val queueRepository: VideoQueueRepository,
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
                override fun onPlaybackVisibilityChanged(active: Boolean) {
                    this@VideoWidgetFactory.onPlaybackVisibilityChanged.invoke(sourceId, active)
                }
            }
        }
    }
}
/** Puts local videos on the dashboard; a new one opens fullscreen and plays. */
class VideoPlugin(
    private val services: WidgetContext,
) : WidgetPlugin {
    private val context: Context get() = services.androidContext
    private val rootLayout: () -> ViewGroup? = { services.rootLayout }
    private val containerView: View get() = services.dashboardView
    private val setMediaSourceActive: (sourceId: String, active: Boolean) -> Unit = { sourceId, active -> services.setMediaSourceActive(sourceId, active) }
    private val queueRepository = VideoQueueRepository(context)

    override val lifecycle = WidgetLifecycleRegistration.multiple(
        WidgetTypeId.VIDEO, VideoWidget::class, DashboardPersistence.VideoWidgetState::class,
        capture = { services.captureRestore.getVideoWidgetState(it) },
        restore = ::restore,
        onFullscreenChanged = { widget, fullscreen ->
            if (fullscreen) widget.startPlayback(autoPlay = true)
            widget.setWindowedMode()
        },
    )

    /** Opens [uri] centered, then fullscreen. Does nothing before the dashboard is on screen. */
    fun open(uri: Uri, displayName: String) {
        val factory = factory() ?: return
        val x = ((services.viewportWidth - 360f) / 2f).coerceAtLeast(0f)
        val y = ((services.viewportHeight - 220f) / 2f).coerceAtLeast(0f)
        val widget = factory.create(uri, displayName, x, y)
        services.runPersistentMutation {
            services.open(WidgetTypeId.VIDEO) { widget }
            widget.toggleFullscreen()
        }
    }

    /** A video that can't be read anymore is dropped from the dashboard. */
    private fun restore(state: DashboardPersistence.VideoWidgetState): VideoWidget? {
        val factory = factory() ?: return null
        val uri = WidgetCaptureRestore.readableUri(context, state.uri) ?: return null
        val widget = factory.create(uri, state.displayName, state.x, state.y)
        services.captureRestore.restoreCommonFields(widget, state, services.viewportWidth, services.viewportHeight)
        return widget
    }

    // The player's native views go into the root layout, which exists once the dashboard is attached.
    private fun factory(): VideoWidgetFactory? = rootLayout()?.let { root ->
        VideoWidgetFactory(context, root, containerView, queueRepository, setMediaSourceActive)
    }
}