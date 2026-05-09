package com.everyday.shared.sync

import android.text.Html
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

object NewsDataProvider {
    private const val FETCH_TIMEOUT_MS = 8000

    fun defaultCountryCode(): String {
        return Locale.getDefault().country
            .takeIf { it.isNotBlank() }
            ?.uppercase(Locale.US)
            ?: "US"
    }

    fun normalizeCountryCode(code: String?): String? {
        val normalized = code?.trim()?.uppercase(Locale.US) ?: return null
        return normalized.takeIf { it.length == 2 }
    }

    fun fetchNews(countryCode: String): Result<NewsSnapshot> {
        return runCatching {
            val normalized = normalizeCountryCode(countryCode) ?: defaultCountryCode()
            val feedConfig = buildFeedConfig(normalized)
            val national = fetchFeed(feedConfig.nationalUrl)
            val world = fetchFeed(feedConfig.worldUrl)
            val merged = mergeNews(national, world)
            if (merged.isEmpty()) throw IllegalStateException("Empty feed")
            NewsSnapshot(
                countryCode = normalized,
                items = merged
            )
        }
    }

    private data class FeedConfig(
        val nationalUrl: String,
        val worldUrl: String
    )

    private fun buildFeedConfig(countryCode: String): FeedConfig {
        val language = when (countryCode.uppercase(Locale.US)) {
            "FR" -> "fr"
            "DE", "AT", "CH" -> "de"
            "ES", "MX" -> "es"
            "IT" -> "it"
            "JP" -> "ja"
            "KR" -> "ko"
            "BR" -> "pt-BR"
            else -> "en"
        }
        val ceid = "${countryCode.uppercase(Locale.US)}:$language"
        return FeedConfig(
            nationalUrl = "https://news.google.com/rss?hl=$language&gl=$countryCode&ceid=$ceid",
            worldUrl = "https://news.google.com/rss/headlines/section/topic/WORLD?hl=$language&gl=$countryCode&ceid=$ceid"
        )
    }

    private fun fetchFeed(urlString: String): List<NewsArticle> {
        val connection = URL(urlString).openConnection() as HttpURLConnection
        connection.connectTimeout = FETCH_TIMEOUT_MS
        connection.readTimeout = FETCH_TIMEOUT_MS
        connection.setRequestProperty("User-Agent", "Mozilla/5.0")

        return try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IllegalStateException("HTTP ${connection.responseCode}")
            }
            val xml = connection.inputStream.bufferedReader().use { it.readText() }
            parseRss(xml)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseRss(xml: String): List<NewsArticle> {
        val parser = XmlPullParserFactory.newInstance().newPullParser().apply {
            setInput(StringReader(xml))
        }

        val items = mutableListOf<NewsArticle>()
        var eventType = parser.eventType
        var inItem = false
        var title = ""
        var description = ""
        var encoded = ""
        var link = ""
        var source = ""
        var pubDate = ""

        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    val tag = parser.name.lowercase(Locale.US)
                    when {
                        tag == "item" -> {
                            inItem = true
                            title = ""
                            description = ""
                            encoded = ""
                            link = ""
                            source = ""
                            pubDate = ""
                        }
                        inItem && tag == "title" -> title = parser.safeNextText()
                        inItem && tag == "description" -> description = parser.safeNextText()
                        inItem && tag == "link" -> link = parser.safeNextText()
                        inItem && tag == "source" -> source = parser.safeNextText()
                        inItem && tag == "pubdate" -> pubDate = parser.safeNextText()
                        inItem && (tag == "content:encoded" || tag == "encoded") -> encoded = parser.safeNextText()
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name.equals("item", ignoreCase = true) && inItem) {
                        val cleanTitle = htmlToPlain(title).ifBlank { "Untitled" }
                        val rawBody = if (encoded.isNotBlank()) encoded else description
                        val cleanBody = htmlToPlain(rawBody).ifBlank { "No preview available for this article." }
                        items.add(
                            NewsArticle(
                                title = cleanTitle,
                                content = cleanBody,
                                link = link.trim(),
                                source = source.trim(),
                                publishedAt = normalizePubDate(pubDate)
                            )
                        )
                        inItem = false
                    }
                }
            }
            eventType = parser.next()
        }

        return items
    }

    private fun mergeNews(national: List<NewsArticle>, world: List<NewsArticle>): List<NewsArticle> {
        val result = mutableListOf<NewsArticle>()
        val seenLinks = mutableSetOf<String>()

        fun addUnique(items: List<NewsArticle>) {
            for (item in items) {
                val key = item.link.ifBlank { item.title }
                if (seenLinks.add(key)) {
                    result.add(item)
                }
            }
        }

        addUnique(national.take(12))
        addUnique(world.take(8))
        if (result.isEmpty()) addUnique(national + world)
        return result.take(20)
    }

    private fun htmlToPlain(value: String): String {
        return Html.fromHtml(value, Html.FROM_HTML_MODE_LEGACY)
            .toString()
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun normalizePubDate(value: String): String {
        return value.replace("GMT", "").trim()
    }

    private fun XmlPullParser.safeNextText(): String {
        return runCatching { nextText() }.getOrDefault("")
    }
}
