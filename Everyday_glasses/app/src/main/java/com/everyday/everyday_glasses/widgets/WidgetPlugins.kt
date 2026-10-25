package com.everyday.everyday_glasses.widgets

import com.everyday.everyday_glasses.runtime.WidgetType
import com.everyday.everyday_glasses.widgets.browser.BrowserPlugin
import com.everyday.everyday_glasses.widgets.calendar.CalendarPlugin
import com.everyday.everyday_glasses.widgets.clock.ClockPlugin
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
 *
 * To add a widget type (`widgets/clock` is the smallest example):
 * 1. Create its package under `widgets/`, with:
 *    - the widget, a [com.everyday.everyday_glasses.widgets._common.BaseWidget] that draws itself and
 *      takes its own taps;
 *    - its saved state, a data class with the common fields, and a `companion object Codec`
 *      ([com.everyday.everyday_glasses.persistence.WidgetDocument.StateCodec]) for its JSON;
 *    - its plugin, a [com.everyday.everyday_glasses.runtime.WidgetPlugin] built from the
 *      [com.everyday.everyday_glasses.runtime.WidgetContext] alone, whose `lifecycle` creates,
 *      restores and captures the widget, and which subscribes to the app's data it needs;
 *    - the plugin's `companion object`, a [WidgetType]: `TYPE` (also its name in saved files, so
 *      never change it), `descriptor` (the next free `order`), `codec`, `create`, and `commands`
 *      (a single-instance type needs its `toggle:<id>` command, on [com.everyday.everyday_glasses.runtime.WidgetMenu.TOGGLE]).
 * 2. Add the companion object to [all] below.
 *
 * The menus, the commands and shortcuts, the assistant's show/hide commands, saving, restoring and
 * closed templates then come from the list.
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
        ClockPlugin,
    )
}