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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

private object JwColors {
    val BrandRed = ColorProvider(day = Color(0xFFCC0000), night = Color(0xFFFF5555))
    val PrimaryText = ColorProvider(day = Color.Black, night = Color.White)
    val SecondaryText = ColorProvider(day = Color.DarkGray, night = Color.LightGray)

    fun background(alpha: Float) = ColorProvider(
        day = Color.White.copy(alpha = alpha),
        night = Color(0xFF202020).copy(alpha = alpha)
    )
}

class JWWidget : GlanceAppWidget() {

    companion object {
        private const val HOME_URL = "https://jungewelt.de"
        private const val USER_AGENT = "JWWidget/1.0"
        private const val NETWORK_TIMEOUT_MS = 8_000
        private const val TOTAL_FETCH_TIMEOUT_MS = 15_000L
        private const val MAX_TOTAL_BITMAP_BYTES = 4_000_000

        // Hintergrund-Scope für automatische Selbstheilung bei Fehlern
        private val recoveryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun scheduleSelfHealing(context: Context, glanceId: GlanceId, delayMs: Long = 5_000L) {
            val appContext = context.applicationContext
            recoveryScope.launch {
                delay(delayMs)
                runCatching {
                    JWWidget().update(appContext, glanceId)
                }
            }
        }
    }

    /**
     * Wird von Glance aufgerufen, falls beim Rendern der UI ein unerwarteter Fehler auftritt.
     * Startet das Widget automatisch nach 5 Sekunden neu.
     */
    override fun onCompositionError(
        context: Context,
        glanceId: GlanceId,
        appWidgetId: Int,
        throwable: Throwable
    ) {
        scheduleSelfHealing(context, glanceId, delayMs = 5_000L)
    }

    override suspend fun provideGlance(
        context: Context,
        id: GlanceId
    ) {
        val settings = WidgetSettingsRepository.load(context)

        // 1. Feed mit hartem Timeout und Fehler-Catching abrufen (verhindert Hänger bei schlechtem Netz)
        val rawArticles = withTimeoutOrNull(TOTAL_FETCH_TIMEOUT_MS) {
            runCatching { RssReader.fetchArticles() }.getOrNull()
        }

        // Falls der Abruf fehlgeschlagen ist: Saubere Fallback-UI anzeigen + Auto-Neustart planen
        if (rawArticles == null) {
            scheduleSelfHealing(context, id, delayMs = 10_000L)
            provideContent {
                ErrorFallbackContent(context = context, settings = settings)
            }
            return
        }

        val filteredArticles = rawArticles
            .filter { article ->
                !settings.hideOnlineAbo || !article.title.contains("[Online-Abo]", ignoreCase = true)
            }
            .take(settings.maxArticles)

        // 2. Thumbnails parallel laden, exakt skalieren und Gesamt-RAM-Budget überwachen
        val targetBitmapPx = (settings.imageWidthDp * 2).coerceIn(80, 240)
        val usedBitmapBytes = AtomicInteger(0)

        val uiArticles = withContext(Dispatchers.IO) {
            filteredArticles.map { article ->
                async {
                    val bitmap = if (settings.showImages && article.imageUrl != null) {
                        loadBoundedBitmap(article.imageUrl, targetBitmapPx, usedBitmapBytes)
                    } else {
                        null
                    }

                    UiArticle(
                        id = article.link,
                        link = article.link,
                        category = article.category,
                        title = article.title,
                        description = article.description,
                        relativeTime = formatRelativeTime(article.pubDateMillis),
                        bitmap = bitmap
                    )
                }
            }.awaitAll()
        }

        // 3. Reguläre Widget-Oberfläche rendern
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
     * Fallback-Ansicht bei Netzwerkausfall: Verhindert "Inhalt kann nicht angezeigt werden"
     * und erlaubt sofortiges manuelles oder automatisches Neu-Laden.
     */
    @Composable
    private fun ErrorFallbackContent(
        context: Context,
        settings: WidgetSettings
    ) {
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

            Box(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .clickable(actionRunCallback<RefreshAction>()),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Feed konnte nicht geladen werden.\nNeustart läuft … (Tippen zum sofortigen Laden)",
                    style = TextStyle(
                        fontSize = 12.sp,
                        color = JwColors.SecondaryText
                    )
                )
            }
        }
    }

    @Composable
    private fun WidgetHeader(
        context: Context,
        logoHeightDp: Int
    ) {
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
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
    private fun formatRelativeTime(timestampMillis: Long): String {
        return DateUtils.getRelativeTimeSpanString(
            timestampMillis,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE
        ).toString()
    }
    /**
     * Lädt ein Bild, skaliert es exakt auf die Zielbreite herunter und prüft,
     * ob das Gesamt-Speicherbudget (450 KB) noch Platz hat.
     */
    private fun loadBoundedBitmap(
        urlString: String,
        targetWidthPx: Int,
        usedBytes: AtomicInteger
    ): Bitmap? {
        if (usedBytes.get() >= MAX_TOTAL_BITMAP_BYTES) return null
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
                connectTimeout = NETWORK_TIMEOUT_MS
                readTimeout = NETWORK_TIMEOUT_MS
                setRequestProperty("User-Agent", USER_AGENT)
            }
            val bytes = connection.inputStream.use { it.readBytes() }
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            options.inSampleSize = calculateInSampleSize(options, targetWidthPx, targetWidthPx)
            options.inJustDecodeBounds = false
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
// Exakt auf Zielbreite skalieren (Original-Seitenverhältnis bleibt erhalten)
            val scaled = if (decoded.width > targetWidthPx) {
                val aspectRatio = decoded.height.toFloat() / decoded.width.toFloat()
                val targetHeightPx = (targetWidthPx * aspectRatio).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(decoded, targetWidthPx, targetHeightPx, true).also {
                    if (it != decoded) decoded.recycle()
                }
            } else {
                decoded
            }
// Prüfen, ob das Bild noch ins 450-KB-Gesamtbudget passt
            val bitmapBytes = scaled.allocationByteCount
            if (usedBytes.addAndGet(bitmapBytes) > MAX_TOTAL_BITMAP_BYTES) {
                usedBytes.addAndGet(-bitmapBytes)
                scaled.recycle()
                null
            } else {
                scaled
            }
        } catch (e: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }
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