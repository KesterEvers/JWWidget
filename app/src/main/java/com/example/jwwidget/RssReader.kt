package com.example.jwwidget

import android.util.Xml
import androidx.core.text.HtmlCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.net.HttpURLConnection
import java.net.URL
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

object RssReader {
    private const val FEED_URL = "https://www.jungewelt.de/feeds/newsticker.rss"

    // Parser für RFC-1123 Zeitstempel wie "Thu, 19 Nov 2026 00:00:00 +0100"
    private val rfc1123Formatter = DateTimeFormatter.ofPattern(
        "EEE, dd MMM yyyy HH:mm:ss Z",
        Locale.ENGLISH
    )

    suspend fun fetchArticles(): List<Article> = withContext(Dispatchers.IO) {
        val articles = mutableListOf<Article>()
        val connection = URL(FEED_URL).openConnection() as HttpURLConnection

        try {
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.setRequestProperty("User-Agent", "JWWidget/1.0")

            connection.inputStream.use { stream ->
                val parser = Xml.newPullParser()
                parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
                parser.setInput(stream, null)

                var currentArticle = false
                var title = ""
                var description = ""
                var link = ""
                var pubDate = ""
                var category = ""
                var imageUrl: String? = null

                var event = parser.eventType

                while (event != XmlPullParser.END_DOCUMENT) {
                    when (event) {
                        XmlPullParser.START_TAG -> {
                            when (parser.name) {
                                "item" -> {
                                    currentArticle = true
                                    title = ""
                                    description = ""
                                    link = ""
                                    pubDate = ""
                                    category = ""
                                    imageUrl = null
                                }
                                "title" -> if (currentArticle && parser.namespace.isNullOrEmpty()) {
                                    title = decodeHtml(parser.nextText())
                                }
                                "description" -> if (currentArticle) {
                                    description = decodeHtml(parser.nextText())
                                }
                                "link" -> if (currentArticle) {
                                    link = parser.nextText().trim()
                                }
                                "pubDate" -> if (currentArticle) {
                                    pubDate = parser.nextText().trim()
                                }
                                "category" -> if (currentArticle) {
                                    category = decodeHtml(parser.nextText())
                                }
                                "content" -> if (currentArticle &&
                                    parser.namespace == "http://search.yahoo.com/mrss/"
                                ) {
                                    imageUrl = parser.getAttributeValue(null, "url")
                                }
                            }
                        }

                        XmlPullParser.END_TAG -> {
                            if (parser.name == "item" && currentArticle) {
                                articles.add(
                                    Article(
                                        title = title,
                                        description = description,
                                        link = link,
                                        pubDate = pubDate,
                                        pubDateMillis = parsePubDate(pubDate),
                                        category = category,
                                        imageUrl = imageUrl
                                    )
                                )
                                currentArticle = false
                            }
                        }
                    }
                    event = parser.next()
                }
            }
        } finally {
            connection.disconnect()
        }

        articles
    }

    private fun parsePubDate(dateString: String): Long {
        return try {
            ZonedDateTime.parse(dateString, rfc1123Formatter)
                .toInstant()
                .toEpochMilli()
        } catch (e: Exception) {
            System.currentTimeMillis()
        }
    }

    // Wandelt z. B. "Kapital &amp; Arbeit" in "Kapital & Arbeit" um
    private fun decodeHtml(text: String): String {
        return HtmlCompat.fromHtml(text, HtmlCompat.FROM_HTML_MODE_LEGACY).toString().trim()
    }
}
