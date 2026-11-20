package com.example.jwwidget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.text.format.DateUtils
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.layout.wrapContentHeight
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import androidx.compose.ui.unit.dp
import androidx.glance.layout.Box
import androidx.glance.layout.size
import androidx.glance.layout.wrapContentWidth
import androidx.glance.appwidget.action.actionStartActivity


class JWWidget : GlanceAppWidget() {

    override suspend fun provideGlance(
        context: Context,
        id: GlanceId
    ) {
        val settings = WidgetSettingsRepository.load(context)

        val rawArticles = RssReader.fetchArticles()
            .filter { article ->
                !settings.hideOnlineAbo || !article.title.contains("[Online-Abo]", ignoreCase = true)
            }
            .take(settings.maxArticles)

        // Ziel-Pixelgröße für Bitmaps dynamisch aus imageWidthDp berechnen (Faktor 2 für scharfe Displays)
        val targetBitmapPx = (settings.imageWidthDp * 2).coerceAtLeast(80)

        val uiArticles = withContext(Dispatchers.IO) {
            rawArticles.map { article ->
                async {
                    UiArticle(
                        id = article.link,
                        link = article.link,
                        category = article.category,
                        title = article.title,
                        description = article.description,
                        relativeTime = formatRelativeTime(article.pubDateMillis),
                        bitmap = if (settings.showImages) {
                            article.imageUrl?.let { loadBitmap(it, targetBitmapPx) }
                        } else {
                            null
                        }
                    )
                }
            }.awaitAll()
        }

        provideContent {
            Column(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(
                        ColorProvider(
                            day = Color.White.copy(alpha = settings.backgroundAlpha),
                            night = Color(0xFF202020).copy(alpha = settings.backgroundAlpha)
                        )
                    )
                    .padding(12.dp)
            ) {
                // Kopfzeile: Logo -> Startseite, Rechts Reload-Button (↻)
                Row(
                    modifier = GlanceModifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = GlanceModifier
                            .defaultWeight()
                            .clickable(
                                actionRunCallback<OpenUrlAction>(
                                    actionParametersOf(ArticleUrlKey to "https://jungewelt.de")
                                )
                            ),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Image(
                            provider = ImageProvider(R.drawable.junge_welt_logo_dark),
                            contentDescription = "junge Welt Logo",
                            contentScale = ContentScale.Fit,
                            modifier = GlanceModifier
                                .height(settings.logoHeightDp.dp)
                                .wrapContentWidth()
                        )
                    }

                    Spacer(modifier = GlanceModifier.width(8.dp))

                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = GlanceModifier
                            .size(36.dp)
                            .cornerRadius(18.dp)
                            .clickable(actionRunCallback<RefreshAction>())
                    ) {
                        Text(
                            text = "↻",
                            style = TextStyle(
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                color = ColorProvider(
                                    day = Color(0xFFCC0000),
                                    night = Color(0xFFFF5555)
                                )
                            )
                        )
                    }

                    Spacer(modifier = GlanceModifier.width(2.dp))

                    // Zahnrad-Button (⚙) -> Öffnet MainActivity direkt über den Launcher
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = GlanceModifier
                            .size(36.dp)
                            .cornerRadius(18.dp)
                            .clickable(
                                actionStartActivity(
                                    Intent(context, MainActivity::class.java).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                )
                            )
                    ) {
                        Text(
                            text = "⚙",
                            style = TextStyle(
                                fontSize = 20.sp,
                                color = ColorProvider(
                                    day = Color(0xFFCC0000),
                                    night = Color(0xFFFF5555)
                                )
                            )
                        )
                    }




                }

                Spacer(modifier = GlanceModifier.height(6.dp))

                // Scrollbare Artikelliste
                LazyColumn(
                    modifier = GlanceModifier.fillMaxSize()
                ) {
                    items(
                        items = uiArticles,
                        itemId = { it.id.hashCode().toLong() }
                    ) { article ->
                        Column(
                            modifier = GlanceModifier
                                .fillMaxWidth()
                                .clickable(
                                    actionRunCallback<OpenUrlAction>(
                                        actionParametersOf(ArticleUrlKey to article.link)
                                    )
                                )
                                .padding(vertical = 6.dp)
                        ) {
                            // 1. Zeile: KATEGORIE - vor 17 Min.
                            if (settings.showCategoryAndTime) {
                                Text(
                                    text = "${article.category.uppercase()} - ${article.relativeTime}",
                                    style = TextStyle(
                                        fontSize = settings.metaFontSizeSp.sp,
                                        color = ColorProvider(
                                            day = Color.DarkGray,
                                            night = Color.LightGray
                                        )
                                    ),
                                    maxLines = 1
                                )
                                Spacer(modifier = GlanceModifier.height(2.dp))
                            }

                            // 2. Zeile: Überschrift links + Bild rechtsbündig
                            Row(
                                modifier = GlanceModifier.fillMaxWidth(),
                                verticalAlignment = Alignment.Top
                            ) {
                                Text(
                                    text = article.title,
                                    style = TextStyle(
                                        fontSize = settings.titleFontSizeSp.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = ColorProvider(
                                            day = Color.Black,
                                            night = Color.White
                                        )
                                    ),
                                    maxLines = settings.titleMaxLines,
                                    modifier = GlanceModifier.defaultWeight()
                                )

                                if (settings.showImages && article.bitmap != null) {
                                    Spacer(modifier = GlanceModifier.width(8.dp))
                                    Image(
                                        provider = ImageProvider(article.bitmap),
                                        contentDescription = article.title,
                                        contentScale = ContentScale.Fit,
                                        modifier = GlanceModifier
                                            .width(settings.imageWidthDp.dp)
                                            .wrapContentHeight()
                                            .cornerRadius(6.dp)
                                    )
                                }
                            }

                            // 3. Zeile: Description auf voller Breite
                            if (settings.showDescription && article.description.isNotBlank()) {
                                Spacer(modifier = GlanceModifier.height(4.dp))
                                Text(
                                    text = article.description,
                                    style = TextStyle(
                                        fontSize = settings.descriptionFontSizeSp.sp,
                                        color = ColorProvider(
                                            day = Color.DarkGray,
                                            night = Color.LightGray
                                        )
                                    ),
                                    maxLines = settings.descriptionMaxLines,
                                    modifier = GlanceModifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }
            }
        }
    }


    private fun formatRelativeTime(timestampMillis: Long): String {
        return DateUtils.getRelativeTimeSpanString(
            timestampMillis,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE
        ).toString()
    }

    private fun loadBitmap(urlString: String, targetSizePx: Int): Bitmap? {
        return try {
            val connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10000
                readTimeout = 10000
                setRequestProperty("User-Agent", "JWWidget/1.0")
            }

            val bytes = connection.inputStream.use { it.readBytes() }
            connection.disconnect()

            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)

            options.inSampleSize = calculateInSampleSize(options, targetSizePx, targetSizePx)
            options.inJustDecodeBounds = false

            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } catch (e: Exception) {
            null
        }
    }


    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {

        val (height: Int, width: Int) = options.run { outHeight to outWidth }
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }
}