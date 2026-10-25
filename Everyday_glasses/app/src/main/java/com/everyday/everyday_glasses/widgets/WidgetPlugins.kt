package com.everyday.everyday_glasses.widgets

import com.everyday.everyday_glasses.runtime.WidgetContext
import com.everyday.everyday_glasses.runtime.WidgetPlugin
import com.everyday.everyday_glasses.widgets.browser.BrowserPlugin
import com.everyday.everyday_glasses.widgets.calendar.CalendarPlugin
import com.everyday.everyday_glasses.widgets.file_browser.FileBrowserPlugin
import com.everyday.everyday_glasses.widgets.file_browser.ImagePlugin
import com.everyday.everyday_glasses.widgets.file_browser.PdfPlugin
import com.everyday.everyday_glasses.widgets.file_browser.VideoPlugin
import com.everyday.everyday_glasses.widgets.finance.FinancePlugin
import com.everyday.everyday_glasses.widgets.map.MapPlugin
import com.everyday.everyday_glasses.widgets.news.NewsPlugin
import com.everyday.everyday_glasses.widgets.notifications.NotificationsPlugin
import com.everyday.everyday_glasses.widgets.phone_screen_mirror.MirrorPlugin
import com.everyday.everyday_glasses.widgets.speech_to_text.SubtitlePlugin
import com.everyday.everyday_glasses.widgets.speedometer.SpeedometerPlugin
import com.everyday.everyday_glasses.widgets.system_status.StatusBarPlugin
import com.everyday.everyday_glasses.widgets.text_editor.TextBoxPlugin
import com.everyday.everyday_glasses.widgets.weather_location.WeatherPlugin
import com.everyday.everyday_glasses.persistence.WidgetDocument
import com.everyday.everyday_glasses.widgets.system_status.StatusBarState
import com.everyday.everyday_glasses.widgets.weather_location.LocationWidgetState
import com.everyday.everyday_glasses.widgets.map.MapWidgetState
import com.everyday.everyday_glasses.widgets.calendar.CalendarWidgetState
import com.everyday.everyday_glasses.widgets.finance.FinanceWidgetState
import com.everyday.everyday_glasses.widgets.news.NewsWidgetState
import com.everyday.everyday_glasses.widgets.notifications.NotificationsWidgetState
import com.everyday.everyday_glasses.widgets.speedometer.SpeedometerWidgetState
import com.everyday.everyday_glasses.widgets.speech_to_text.SubtitleWidgetState
import com.everyday.everyday_glasses.widgets.phone_screen_mirror.MirrorWidgetState
import com.everyday.everyday_glasses.widgets.browser.BrowserWidgetState
import com.everyday.everyday_glasses.widgets.text_editor.TextWidgetState
import com.everyday.everyday_glasses.widgets.file_browser.ImageWidgetState
import com.everyday.everyday_glasses.widgets.file_browser.VideoWidgetState
import com.everyday.everyday_glasses.widgets.file_browser.PdfWidgetState
import com.everyday.everyday_glasses.widgets.file_browser.FileBrowserWidgetState

/**
 * Every widget type of the dashboard. This is the one place that names them all: a new widget type
 * is added here, its plugin to [all] and, when it saves its state, its codec to [codecs].
 */
internal object WidgetPlugins {
    val all: List<(WidgetContext) -> WidgetPlugin> = listOf(
        // Several widgets of these types can be open at once.
        ::BrowserPlugin,
        ::TextBoxPlugin,
        ::ImagePlugin,
        ::VideoPlugin,
        ::PdfPlugin,
        ::FileBrowserPlugin,
        // One widget at most of these types.
        ::StatusBarPlugin,
        ::WeatherPlugin,
        ::MapPlugin,
        ::CalendarPlugin,
        ::FinancePlugin,
        ::NewsPlugin,
        ::NotificationsPlugin,
        ::SpeedometerPlugin,
        ::SubtitlePlugin,
        ::MirrorPlugin,
    )

    /**
     * How each type saves its widgets. A saved dashboard is read before any plugin exists, so this
     * lists the codecs themselves.
     */
    val codecs: List<WidgetDocument.WidgetStateCodec> = listOf(
        BrowserWidgetState.Codec,
        TextWidgetState.Codec,
        ImageWidgetState.Codec,
        VideoWidgetState.Codec,
        PdfWidgetState.Codec,
        FileBrowserWidgetState.Codec,
        StatusBarState.Codec,
        LocationWidgetState.Codec,
        MapWidgetState.Codec,
        CalendarWidgetState.Codec,
        FinanceWidgetState.Codec,
        NewsWidgetState.Codec,
        NotificationsWidgetState.Codec,
        SpeedometerWidgetState.Codec,
        SubtitleWidgetState.Codec,
        MirrorWidgetState.Codec,
    )
}