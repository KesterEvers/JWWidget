package com.example.jwwidget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.layout.wrapContentHeight
import androidx.glance.layout.wrapContentWidth
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Zentrale Farbdefinitionen für Hell- und Dunkelmodus des Widgets.
 */
private object JwColors {
    val BrandRed = ColorProvider(day = Color(0xFFCC0000), night = Color(0xFFFF5555))
    val PrimaryText = ColorProvider(day = Color.Black, night = Color.White)
    val SecondaryText = ColorProvider(day = Color.DarkGray, night = Color.LightGray)

    fun background(alpha: Float) = ColorProvider(
        day = Color.White.copy(alpha = alpha),
        night = Color(0xFF202020).copy(alpha = alpha)
    )
}

/**
 * Hauptklasse des Jetpack-Glance-Homescreen-Widgets für junge Welt.
 * Lädt den RSS-Feed, bereitet Thumbnails speicherschonend auf und rendert die UI.
 */
class JWWidget : GlanceAppWidget() {

    companion object {
        private const val HOME_URL = "https://jungewelt.de"
        private const val USER_AGENT = "JWWidget/1.0"
        private const val NETWORK_TIMEOUT_MS = 10_000
    }

    override suspend fun provideGlance(
        context: Context,
        id: GlanceId
    ) {
        val settings = WidgetSettingsRepository.load(context)

        // 1. RSS-Artikel abrufen, optional [Online-Abo]-Beiträge filtern und begrenzen
        val filteredArticles = RssReader.fetchArticles()
            .filter { article ->
                !settings.hideOnlineAbo || !article.title.contains("[Online-Abo]", ignoreCase = true)
            }
            .take(settings.maxArticles)

        // 2. Vorschaubilder parallel im IO-Thread herunterladen & herunterskalieren
        val targetBitmapPx = (settings.imageWidthDp * 2).coerceAtLeast(80)
        val uiArticles = withContext(Dispatchers.IO) {
            filteredArticles.map { article ->
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

        // 3. Widget-Oberfläche rendern
        provideContent {
            Column(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(JwColors.background(settings.backgroundAlpha))
                    .padding(12.dp)
            ) {
                WidgetHeader(
                    context = context,
                    logoHeightDp = settings.logoHeightDp
                )

                Spacer(modifier = GlanceModifier.height(6.dp))

                LazyColumn(
                    modifier = GlanceModifier.fillMaxSize()
                ) {
                    items(
                        items = uiArticles,
                        itemId = { it.id.hashCode().toLong() }
                    ) { article ->
                        ArticleItem(
                            article = article,
                            settings = settings
                        )
                    }
                }
            }
        }
    }

    /**
     * Fixierte Kopfzeile mit klickbarem Logo (Startseite), Reload-Button (↻) und Settings-Button (⚙).
     */
    @Composable
    private fun WidgetHeader(
        context: Context,
        logoHeightDp: Int
    ) {
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Klickbares Logo -> Öffnet die Startseite im Browser
            Box(
                modifier = GlanceModifier
                    .defaultWeight()
                    .clickable(
                        actionRunCallback<OpenUrlAction>(
                            actionParametersOf(ArticleUrlKey to HOME_URL)
                        )
                    ),
                contentAlignment = Alignment.CenterStart
            ) {
                Image(
                    provider = ImageProvider(R.drawable.junge_welt_logo_dark),
                    contentDescription = "junge Welt Logo",
                    contentScale = ContentScale.Fit,
                    modifier = GlanceModifier
                        .height(logoHeightDp.dp)
                        .wrapContentWidth()
                )
            }

            Spacer(modifier = GlanceModifier.width(8.dp))

            // Manueller Aktualisieren-Button (↻)
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
                        color = JwColors.BrandRed
                    )
                )
            }

            Spacer(modifier = GlanceModifier.width(2.dp))

            // Einstellungs-Button (⚙) -> Startet die MainActivity direkt über den Launcher
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
                        fontWeight = FontWeight.Bold,
                        color = JwColors.BrandRed
                    )
                )
            }
        }
    }

    /**
     * Einzelner Artikel-Eintrag in der Liste:
     * Zeile 1: Kategorie & Alter | Zeile 2: Überschrift + Thumbnail | Zeile 3: Teaser auf voller Breite.
     */
    @Composable
    private fun ArticleItem(
        article: UiArticle,
        settings: WidgetSettings
    ) {
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
            // 1. Zeile: Kategorie und relatives Alter (z. B. "INLAND - vor 17 Min.")
            if (settings.showCategoryAndTime) {
                Text(
                    text = "${article.category.uppercase()} - ${article.relativeTime}",
                    style = TextStyle(
                        fontSize = settings.metaFontSizeSp.sp,
                        color = JwColors.SecondaryText
                    ),
                    maxLines = 1
                )
                Spacer(modifier = GlanceModifier.height(2.dp))
            }

            // 2. Zeile: Überschrift links und Vorschaubild in Original-Seitenverhältnis rechts
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    text = article.title,
                    style = TextStyle(
                        fontSize = settings.titleFontSizeSp.sp,
                        fontWeight = FontWeight.Bold,
                        color = JwColors.PrimaryText
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
// 3. Zeile: Beschreibungstext (Teaser) über die gesamte Breite
            if (settings.showDescription && article.description.isNotBlank()) {
                Spacer(modifier = GlanceModifier.height(4.dp))
                Text(
                    text = article.description,
                    style = TextStyle(
                        fontSize = settings.descriptionFontSizeSp.sp,
                        color = JwColors.SecondaryText
                    ),
                    maxLines = settings.descriptionMaxLines,
                    modifier = GlanceModifier.fillMaxWidth()
                )
            }
        }
    }
    /**
     * Formatiert einen Unix-Zeitstempel in eine relative Zeitangabe (z. B. "vor 17 Min.").
     */
    private fun formatRelativeTime(timestampMillis: Long): String {
        return DateUtils.getRelativeTimeSpanString(
            timestampMillis,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE
        ).toString()
    }
    /**
     * Lädt ein Bild aus dem Netz und skaliert es bereits beim Dekodieren herunter,
     * um das RemoteViews-Speicherlimit von Android-Widgets nicht zu überschreiten.
     */
    private fun loadBitmap(urlString: String, targetSizePx: Int): Bitmap? {
        return try {
            val connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
                connectTimeout = NETWORK_TIMEOUT_MS
                readTimeout = NETWORK_TIMEOUT_MS
                setRequestProperty("User-Agent", USER_AGENT)
            }
            val bytes = connection.inputStream.use { it.readBytes() }
            connection.disconnect()
// Erst nur Bildabmessungen auslesen, ohne Pixel in den RAM zu laden
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            options.inSampleSize = calculateInSampleSize(options, targetSizePx, targetSizePx)
            options.inJustDecodeBounds = false
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } catch (e: Exception) {
            null
        }
    }
    /**
     * Berechnet eine Zweierpotenz für inSampleSize, sodass das dekodierte Bild
     * möglichst nah an der gewünschten Zielgröße liegt.
     */
    private fun calculateInSampleSize(
        options: BitmapFactory.Options,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        val (height, width) = options.outHeight to options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }
}