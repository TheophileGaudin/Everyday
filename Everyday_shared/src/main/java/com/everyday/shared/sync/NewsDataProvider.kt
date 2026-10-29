package com.everyday.shared.sync

import android.text.Html
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import java.util.Locale

data class NewsFeedUrls(
    val urls: List<String>,
    val countryCode: String,
    val language: String
)

object NewsDataProvider {
    private const val FETCH_TIMEOUT_MS = 8000
    private const val MAX_FEED_BYTES = 1024 * 1024

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

    fun resolveCountryCode(
        requestedCountryCode: String?,
        weather: WeatherSnapshot?,
        cachedNews: NewsSnapshot?
    ): String = normalizeCountryCode(requestedCountryCode)
        ?: normalizeCountryCode(weather?.countryCode)
        ?: normalizeCountryCode(cachedNews?.countryCode)
        ?: defaultCountryCode()

    /** Build the Google News/custom feed URLs without performing network I/O. */
    fun buildFeedUrls(
        sourceSpec: NewsSourceSpec,
        countryCode: String,
        locationLabel: String? = null
    ): NewsFeedUrls {
        val normalizedCountry = normalizeCountryCode(countryCode) ?: defaultCountryCode()
        val language = languageForCountry(normalizedCountry)
        val ceid = "${normalizedCountry}:$language"
        val urls = when (sourceSpec.kind) {
            NewsSourceKind.DEFAULT_LOCATION -> listOf(
                "https://news.google.com/rss?hl=$language&gl=$normalizedCountry&ceid=$ceid",
                "https://news.google.com/rss/headlines/section/topic/WORLD?hl=$language&gl=$normalizedCountry&ceid=$ceid"
            )
            NewsSourceKind.QUERY -> {
                val query = listOfNotNull(
                    sourceSpec.intent?.trim()?.takeIf { it.isNotBlank() },
                    locationLabel?.trim()?.takeIf { it.isNotBlank() && !it.equals("Unknown", true) }
                ).joinToString(" ")
                require(query.isNotBlank()) { "News query must not be blank" }
                listOf(
                    "https://news.google.com/rss/search?q=${encodeQuery(query)}" +
                        "&hl=$language&gl=$normalizedCountry&ceid=$ceid"
                )
            }
            NewsSourceKind.CUSTOM_RSS -> listOf(requireSupportedHttpUrl(sourceSpec.url.orEmpty()))
        }
        return NewsFeedUrls(urls, normalizedCountry, language)
    }

    fun queryFeedUrl(
        intent: String,
        countryCode: String,
        locationLabel: String? = null
    ): String = buildFeedUrls(NewsSourceSpec.QUERY(intent), countryCode, locationLabel).urls.single()

    fun isSupportedHttpUrl(value: String): Boolean = runCatching {
        val uri = URI(value.trim())
        uri.isAbsolute &&
            uri.host != null &&
            uri.userInfo == null &&
            uri.scheme.lowercase(Locale.US) in setOf("http", "https")
    }.getOrDefault(false)

    fun fetchNews(countryCode: String): Result<NewsSnapshot> =
        fetchNews(NewsSourceSpec.DEFAULT_LOCATION, countryCode)

    fun fetchNews(
        sourceSpec: NewsSourceSpec,
        countryCode: String,
        locationLabel: String? = null
    ): Result<NewsSnapshot> = runCatching {
        val feedUrls = buildFeedUrls(sourceSpec, countryCode, locationLabel)
        val feeds = feedUrls.urls.map(::fetchFeed)
        val merged = if (sourceSpec.kind == NewsSourceKind.DEFAULT_LOCATION) {
            mergeNews(feeds.getOrElse(0) { emptyList() }, feeds.getOrElse(1) { emptyList() })
        } else {
            uniqueNews(feeds.flatten()).take(20)
        }
        if (merged.isEmpty()) throw IllegalStateException("Empty feed")
        NewsSnapshot(
            countryCode = feedUrls.countryCode,
            items = merged,
            sourceSpec = sourceSpec
        )
    }

    /** Parse either RSS/2.x or Atom content. The result is empty for a valid feed with no usable entries. */
    fun parseFeed(xml: String): Result<List<NewsArticle>> = runCatching {
        if (xml.isBlank()) throw IllegalArgumentException("Feed is empty")
        parseFeedOrThrow(xml)
    }

    private data class RawEntry(
        var title: String = "",
        var description: String = "",
        var encoded: String = "",
        var link: String = "",
        var source: String = "",
        var publishedAt: String = ""
    )

    private fun parseFeedOrThrow(xml: String): List<NewsArticle> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
            runCatching { setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "") }
            runCatching { setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "") }
            isExpandEntityReferences = false
        }
        val builder = factory.newDocumentBuilder().apply {
            setEntityResolver { _, _ -> InputSource(StringReader("")) }
        }
        val document = builder.parse(
            ByteArrayInputStream(xml.toByteArray(StandardCharsets.UTF_8))
        )
        val items = mutableListOf<NewsArticle>()
        val entryNodes = document.getElementsByTagName("*")
        for (index in 0 until entryNodes.length) {
            val element = entryNodes.item(index) as? Element ?: continue
            val tag = normalizedTag(element.tagName)
            if (tag != "item" && tag != "entry") continue

            val entry = RawEntry()
            val children = element.childNodes
            for (childIndex in 0 until children.length) {
                val child = children.item(childIndex) as? Element ?: continue
                val childTag = normalizedTag(child.tagName)
                val childText = child.textContent.orEmpty()
                when (childTag) {
                    "title" -> entry.title = childText
                    "description", "summary" -> entry.description = childText
                    "content", "encoded" -> entry.encoded = childText
                    "link" -> {
                        val href = child.getAttribute("href").trim()
                        val rel = child.getAttribute("rel").trim()
                        if (href.isNotBlank() &&
                            (entry.link.isBlank() || rel.isBlank() || rel.equals("alternate", true))
                        ) {
                            entry.link = href
                        } else if (href.isBlank()) {
                            entry.link = childText
                        }
                    }
                    "source" -> entry.source = childText
                    "name" -> if (entry.source.isBlank()) entry.source = childText
                    "pubdate", "published", "updated", "date" -> entry.publishedAt = childText
                }
            }

            val cleanTitle = htmlToPlain(entry.title)
            val rawBody = entry.encoded.ifBlank { entry.description }
            val cleanBody = htmlToPlain(rawBody)
            // A title plus either a link or a preview is the minimum useful entry. This drops
            // empty placeholder nodes while preserving short RSS items with no description.
            if (cleanTitle.isNotBlank() && (entry.link.isNotBlank() || cleanBody.isNotBlank())) {
                items += NewsArticle(
                    title = cleanTitle,
                    content = cleanBody.ifBlank { "No preview available for this article." },
                    link = entry.link.trim(),
                    source = entry.source.trim(),
                    publishedAt = normalizePubDate(entry.publishedAt)
                )
            }
        }
        return items
    }

    private fun fetchFeed(urlString: String): List<NewsArticle> {
        val safeUrl = requireSupportedHttpUrl(urlString)
        val connection = URL(safeUrl).openConnection() as HttpURLConnection
        connection.connectTimeout = FETCH_TIMEOUT_MS
        connection.readTimeout = FETCH_TIMEOUT_MS
        connection.instanceFollowRedirects = true
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/rss+xml, application/atom+xml, application/xml, text/xml, */*")
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 Everyday/NewsWidget")

        return try {
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("HTTP ${connection.responseCode}")
            }
            if (connection.contentLengthLong > MAX_FEED_BYTES) {
                throw IllegalStateException("Feed is too large")
            }
            val xml = connection.inputStream.use(::readLimitedUtf8)
            parseFeed(xml).getOrThrow()
        } finally {
            connection.disconnect()
        }
    }

    private fun requireSupportedHttpUrl(value: String): String {
        val normalized = value.trim()
        require(isSupportedHttpUrl(normalized)) { "News source must be an HTTP(S) URL" }
        return normalized
    }

    private fun readLimitedUtf8(input: java.io.InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > MAX_FEED_BYTES) throw IllegalStateException("Feed is too large")
            output.write(buffer, 0, count)
        }
        return output.toString(StandardCharsets.UTF_8.name())
    }

    private fun languageForCountry(countryCode: String): String = when (countryCode.uppercase(Locale.US)) {
        "FR" -> "fr"
        "DE", "AT", "CH" -> "de"
        "ES", "MX" -> "es"
        "IT" -> "it"
        "JP" -> "ja"
        "KR" -> "ko"
        "BR" -> "pt-BR"
        else -> "en"
    }

    private fun encodeQuery(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private fun normalizedTag(name: String?): String =
        name.orEmpty().substringAfterLast(':').lowercase(Locale.US)

    private fun uniqueNews(items: List<NewsArticle>): List<NewsArticle> {
        val result = mutableListOf<NewsArticle>()
        val seen = mutableSetOf<String>()
        for (item in items) {
            val key = item.link.ifBlank { "${item.title}\u0000${item.source}" }
            if (seen.add(key)) result += item
        }
        return result
    }

    private fun mergeNews(national: List<NewsArticle>, world: List<NewsArticle>): List<NewsArticle> {
        val result = mutableListOf<NewsArticle>()
        result += uniqueNews(national).take(12)
        result += uniqueNews(world).filter { candidate ->
            result.none { it.link == candidate.link && candidate.link.isNotBlank() } &&
                result.none { it.link.isBlank() && candidate.link.isBlank() && it.title == candidate.title }
        }.take(8)
        return uniqueNews(result).take(20)
    }

    private fun htmlToPlain(value: String): String {
        val plain = runCatching {
            Html.fromHtml(value, Html.FROM_HTML_MODE_LEGACY).toString()
        }.getOrElse {
            value.replace(Regex("<[^>]*>"), " ")
        }
        return plain
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun normalizePubDate(value: String): String = value.replace("GMT", "").trim()

}
