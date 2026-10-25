package com.everyday.everyday_glasses.widgets

import com.everyday.everyday_glasses.runtime.WidgetType
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

/**
 * Every widget type of the dashboard, as its plugin's companion object (its id, descriptor, how it
 * is saved, and its plugin). This is the one place that names them all: a new widget type is one
 * line here.
 */
internal object WidgetPlugins {
    val all: List<WidgetType> = listOf(
        // Several widgets of these types can be open at once.
        BrowserPlugin,
        TextBoxPlugin,
        ImagePlugin,
        VideoPlugin,
        PdfPlugin,
        FileBrowserPlugin,
        // One widget at most of these types.
        StatusBarPlugin,
        WeatherPlugin,
        MapPlugin,
        CalendarPlugin,
        FinancePlugin,
        NewsPlugin,
        NotificationsPlugin,
        SpeedometerPlugin,
        SubtitlePlugin,
        MirrorPlugin,
    )
}