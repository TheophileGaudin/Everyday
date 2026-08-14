package com.everyday.shared.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import kotlin.concurrent.thread

class NewsDataProviderTest {

    @Test
    fun `default and query feeds preserve country localization and encoded intent`() {
        val french = NewsDataProvider.buildFeedUrls(
            NewsSourceSpec.DEFAULT_LOCATION,
            "fr"
        )
        assertEquals("FR", french.countryCode)
        assertEquals("fr", french.language)
        assertEquals(2, french.urls.size)
        assertTrue(french.urls[0].contains("hl=fr&gl=FR&ceid=FR:fr"))
        assertTrue(french.urls[1].contains("topic/WORLD"))

        val query = NewsDataProvider.queryFeedUrl(
            intent = "élections & sports",
            countryCode = "BR",
            locationLabel = "São Paulo"
        )
        assertTrue(query.startsWith("https://news.google.com/rss/search?q="))
        assertTrue(query.contains("%C3%A9lections+%26+sports+S%C3%A3o+Paulo"))
        assertTrue(query.contains("hl=pt-BR&gl=BR&ceid=BR:pt-BR"))
    }

    @Test
    fun `rss and atom feeds return only usable entries`() {
        val rss = """
            <?xml version="1.0"?>
            <rss version="2.0"><channel>
              <item>
                <title>RSS headline</title>
                <description><![CDATA[<p>Preview <b>text</b></p>]]></description>
                <link>https://example.com/rss</link>
                <source>Example Wire</source>
                <pubDate>Tue, 04 Aug 2026 10:00:00 GMT</pubDate>
              </item>
              <item><title>Placeholder</title></item>
            </channel></rss>
        """.trimIndent()
        val rssResult = NewsDataProvider.parseFeed(rss)
        assertTrue(rssResult.isSuccess)
        assertEquals(1, rssResult.getOrThrow().size)
        assertEquals("RSS headline", rssResult.getOrThrow().single().title)
        assertEquals("Preview text", rssResult.getOrThrow().single().content)

        val atom = """
            <feed xmlns="http://www.w3.org/2005/Atom">
              <entry>
                <title>Atom headline</title>
                <link rel="alternate" href="https://example.com/atom" />
                <summary>Atom preview</summary>
                <updated>2026-08-04T10:00:00Z</updated>
              </entry>
            </feed>
        """.trimIndent()
        val atomResult = NewsDataProvider.parseFeed(atom)
        assertTrue(atomResult.isSuccess)
        assertEquals("https://example.com/atom", atomResult.getOrThrow().single().link)
    }

    @Test
    fun `malformed empty and unsupported feeds fail safely`() {
        assertTrue(NewsDataProvider.parseFeed("<rss><channel>").isFailure)
        assertTrue(NewsDataProvider.parseFeed("<rss><channel></channel></rss>").getOrThrow().isEmpty())
        assertFalse(NewsDataProvider.isSupportedHttpUrl("file:///tmp/news.xml"))
        assertFalse(NewsDataProvider.isSupportedHttpUrl("ftp://example.com/news.xml"))
        assertFalse(NewsDataProvider.isSupportedHttpUrl("https://user:password@example.com/news.xml"))
        assertTrue(NewsDataProvider.fetchNews(NewsSourceSpec.CUSTOM_RSS("file:///tmp/news.xml"), "US").isFailure)
    }

    @Test
    fun `custom feed follows redirects and rejects network errors and empty content`() {
        val rss = """
            <rss version="2.0"><channel>
              <item><title>Redirected headline</title><link>https://example.com/story</link></item>
            </channel></rss>
        """.trimIndent()
        withHttpServer(
            responseFactory = { port ->
                listOf(
                    response("302 Found", mapOf("Location" to "http://127.0.0.1:$port/feed"), ""),
                    response("200 OK", emptyMap(), rss)
                )
            },
            block = { url ->
            val result = NewsDataProvider.fetchNews(NewsSourceSpec.CUSTOM_RSS(url), "GB")
            assertTrue(result.isSuccess)
            assertEquals("Redirected headline", result.getOrThrow().items.single().title)
            }
        )

        withHttpServer(
            responseFactory = { listOf(response("500 Server Error", emptyMap(), "")) },
            block = { url ->
            assertTrue(NewsDataProvider.fetchNews(NewsSourceSpec.CUSTOM_RSS(url), "GB").isFailure)
            }
        )

        withHttpServer(
            responseFactory = { listOf(response("200 OK", emptyMap(), "<rss><channel></channel></rss>")) },
            block = { url ->
            assertTrue(NewsDataProvider.fetchNews(NewsSourceSpec.CUSTOM_RSS(url), "GB").isFailure)
            }
        )
    }

    @Test
    fun `source specs survive request and snapshot sync round trips`() {
        val sources = listOf(
            NewsSourceSpec.DEFAULT_LOCATION,
            NewsSourceSpec.QUERY("latest local technology"),
            NewsSourceSpec.CUSTOM_RSS("https://example.com/feed.xml")
        )
        sources.forEach { source ->
            val request = SyncRequest(
                channels = setOf(SyncChannel.NEWS),
                force = true,
                reason = "source_test",
                countryCode = "GB",
                newsSource = source
            )
            val decodedRequest = SyncProtocol.requestFromJson(SyncProtocol.requestToJson(request))!!
            assertEquals(source, decodedRequest.newsSource)

            val snapshot = SyncSnapshot(
                news = NewsSnapshot(
                    countryCode = "GB",
                    items = listOf(NewsArticle("Headline", "Preview", "https://example.com", "Wire", "now")),
                    sourceSpec = source
                )
            )
            val decodedSnapshot = SyncProtocol.snapshotFromJson(SyncProtocol.snapshotToJson(snapshot))
            assertEquals(source, decodedSnapshot.news?.sourceSpec)
        }
    }

    private fun response(status: String, headers: Map<String, String>, body: String): String {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        return buildString {
            append("HTTP/1.1 ").append(status).append("\r\n")
            append("Content-Type: application/rss+xml; charset=utf-8\r\n")
            append("Content-Length: ").append(bytes.size).append("\r\n")
            append("Connection: close\r\n")
            headers.forEach { (key, value) -> append(key).append(": ").append(value).append("\r\n") }
            append("\r\n")
            append(body)
        }
    }

    private fun withHttpServer(
        responseFactory: (port: Int) -> List<String>,
        block: (url: String) -> Unit
    ) {
        ServerSocket(0).use { server ->
            val responses = responseFactory(server.localPort)
            val worker = thread(start = true, isDaemon = true) {
                responses.forEach { payload ->
                    runCatching {
                        server.accept().use { socket ->
                            socket.soTimeout = 5000
                            consumeHeaders(socket)
                            socket.getOutputStream().use { output ->
                                output.write(payload.toByteArray(StandardCharsets.UTF_8))
                                output.flush()
                            }
                        }
                    }
                }
            }
            try {
                block("http://127.0.0.1:${server.localPort}/start")
            } finally {
                runCatching { server.close() }
                worker.join(5000)
            }
        }
    }

    private fun consumeHeaders(socket: Socket) {
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
        while (reader.readLine()?.isNotEmpty() == true) {
            // The request body is not used by the feed endpoint.
        }
    }
}
