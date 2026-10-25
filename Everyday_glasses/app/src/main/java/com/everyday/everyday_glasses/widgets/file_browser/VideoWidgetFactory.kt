package com.everyday.everyday_glasses.widgets.file_browser

import android.content.Context
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import com.everyday.everyday_glasses.persistence.DashboardPersistence
import com.everyday.everyday_glasses.persistence.WidgetCaptureRestore
import com.everyday.everyday_glasses.persistence.DashboardPersistence.putCommonFields
import com.everyday.everyday_glasses.persistence.DashboardPersistence.parseCommonFields
import com.everyday.everyday_glasses.persistence.WidgetDocument
import com.everyday.everyday_glasses.runtime.WidgetContext
import com.everyday.everyday_glasses.runtime.WidgetPlugin
import com.everyday.everyday_glasses.runtime.VideoWidgetListener
import com.everyday.everyday_glasses.runtime.WidgetDescriptor
import com.everyday.everyday_glasses.runtime.WidgetInstancePolicy
import com.everyday.everyday_glasses.runtime.WidgetLifecycleRegistration
import com.everyday.everyday_glasses.runtime.WidgetType
import com.everyday.everyday_glasses.runtime.WidgetTypeId
import com.everyday.everyday_glasses.widgets.file_browser.video_backend.VideoQueueRepository
import org.json.JSONObject

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
        VideoWidget::class, VideoWidgetState.Codec,
        capture = ::capture,
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
            services.open(TYPE) { widget }
            widget.toggleFullscreen()
        }
    }

    /** A video that can't be read anymore is dropped from the dashboard. */
    private fun restore(state: VideoWidgetState): VideoWidget? {
        val factory = factory() ?: return null
        val uri = WidgetCaptureRestore.readableUri(context, state.uri) ?: return null
        val widget = factory.create(uri, state.displayName, state.x, state.y)
        services.captureRestore.restoreCommonFields(widget, state, services.viewportWidth, services.viewportHeight)
        return widget
    }

    private fun capture(widget: VideoWidget): VideoWidgetState {
        val c = services.captureRestore.extractCommonFields(widget)
        return VideoWidgetState(
            x = c.x, y = c.y, width = c.width, height = c.height,
            uri = widget.contentUri.toString(),
            displayName = widget.displayName,
            isMinimized = c.isMinimized,
            isFullscreen = c.isFullscreen,
            savedMinX = c.savedMinX, savedMinY = c.savedMinY,
            savedMinWidth = c.savedMinWidth, savedMinHeight = c.savedMinHeight,
            isPinned = c.isPinned
        )
    }

    // The player's native views go into the root layout, which exists once the dashboard is attached.
    private fun factory(): VideoWidgetFactory? = rootLayout()?.let { root ->
        VideoWidgetFactory(context, root, containerView, queueRepository, setMediaSourceActive)
    }

    /** The video as a widget type: its id, its descriptor, how it is saved, and its plugin. */
    companion object : WidgetType {
        val TYPE = WidgetTypeId("video")
        override val descriptor = WidgetDescriptor(
            TYPE,
            "Video",
            WidgetInstancePolicy.MULTIPLE,
            order = 13,
            category = "media"
        )
        override val codec get() = VideoWidgetState.Codec
        override fun create(context: WidgetContext) = VideoPlugin(context)
    }
}

/** What a saved dashboard keeps of a video: its content URI and name. */
data class VideoWidgetState(
    override val x: Float,
    override val y: Float,
    override val width: Float,
    override val height: Float,
    val uri: String,
    val displayName: String,
    override val isMinimized: Boolean = false,
    override val isFullscreen: Boolean = false,
    override val savedMinX: Float = 0f,
    override val savedMinY: Float = 0f,
    override val savedMinWidth: Float = 0f,
    override val savedMinHeight: Float = 0f,
    override val isPinned: Boolean = false
) : DashboardPersistence.SizedWidgetFields {
    /** Its entry in the saved dashboard file. */
    companion object Codec : WidgetDocument.StateCodec<VideoWidgetState>(VideoPlugin.TYPE, VideoWidgetState::class) {
        override fun toJson(state: VideoWidgetState): JSONObject = JSONObject().apply {
            putCommonFields(state)
            put("uri", state.uri)
            put("displayName", state.displayName)
        }

        override fun fromJson(payload: JSONObject): VideoWidgetState? = runCatching {
            val c = parseCommonFields(payload)
            val uri = payload.optString("uri", "")
            if (uri.isBlank()) return null
            VideoWidgetState(
                x = c.x, y = c.y,
                width = payload.getDouble("width").toFloat(),
                height = payload.getDouble("height").toFloat(),
                uri = uri,
                displayName = payload.optString("displayName", "Video"),
                isMinimized = c.isMinimized,
                isFullscreen = c.isFullscreen,
                savedMinX = c.savedMinX, savedMinY = c.savedMinY,
                savedMinWidth = c.savedMinWidth, savedMinHeight = c.savedMinHeight,
                isPinned = c.isPinned
            )
        }.getOrNull()
    }
}