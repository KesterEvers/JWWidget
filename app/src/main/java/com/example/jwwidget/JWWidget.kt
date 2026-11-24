package com.example.jwwidget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.text.format.DateUtils
import android.view.WindowManager
import android.widget.RemoteViews
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
import kotlin.math.max

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

        /**
         * Zähler, der bei jedem Refresh hochgezählt wird. Wird in die itemId
         * der LazyColumn-Items eingerechnet, damit der RemoteViews-Adapter
         * alle Items als "neu" behandelt und die Scroll-Position zurücksetzt.
         */
        @Volatile
        private var refreshGeneration: Long = 0L

        fun nextRefreshGeneration(): Long = ++refreshGeneration

        // Hintergrund-Scope für automatische Selbstheilung bei Fehlern
        private val recoveryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /**
         * Schätzt das Widget-Bitmap-Speicherlimit des Geräts (6 × Breite × Höhe Bytes).
         * system_server berechnet diesen Cap einmal beim Booten aus der echten Display-Größe.
         */
        fun calculateWidgetMemoryCapBytes(context: Context): Long {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = context.getSystemService(WindowManager::class.java)
                    ?.currentWindowMetrics?.bounds
                if (bounds != null && bounds.width() > 0 && bounds.height() > 0) {
                    return 6L * bounds.width().toLong() * bounds.height().toLong()
                }
            }
            val dm = context.resources.displayMetrics
            return 6L * dm.widthPixels.toLong() * dm.heightPixels.toLong()
        }

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
     * Zeigt eine Text-only Fallback-UI an (0 Bitmaps) und plant einen automatischen Neustart.
     */
    override fun onCompositionError(
        context: Context,
        glanceId: GlanceId,
        appWidgetId: Int,
        throwable: Throwable
    ) {
        // Sofort eine text-only Fallback-UI anzeigen, damit der Launcher
        // nicht "Inhalt kann nicht angezeigt werden" zeigt.
        val fallback = RemoteViews(context.packageName, android.R.layout.simple_list_item_1).apply {
            setTextViewText(android.R.id.text1, "Widget wird neu geladen…")
        }
        AppWidgetManager.getInstance(context).updateAppWidget(appWidgetId, fallback)
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

        // 2. Thumbnails parallel laden, exakt skalieren und geräte-basiertes RAM-Budget überwachen
        val distinctImageCount = if (settings.showImages) {
            filteredArticles.count { it.imageUrl != null }.coerceAtLeast(1)
        } else {
            1
        }
        val memoryCapBytes = calculateWidgetMemoryCapBytes(context)
        val perImageCapBytes = memoryCapBytes / max(1, distinctImageCount)
        val targetBitmapPx = (settings.imageWidthDp * 2).coerceIn(80, 240)
        val usedBitmapBytes = AtomicInteger(0)

        val uiArticles = withContext(Dispatchers.IO) {
            filteredArticles.map { article ->
                async {
                    val bitmap = if (settings.showImages && article.imageUrl != null) {
                        loadBoundedBitmap(
                            urlString = article.imageUrl,
                            targetWidthPx = targetBitmapPx,
                            usedBytes = usedBitmapBytes,
                            perImageCapBytes = perImageCapBytes,
                            totalCapBytes = memoryCapBytes
                        )
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
                        itemId = { it.id.hashCode().toLong() xor (refreshGeneration shl 32) }
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
     * Lädt ein Bild, skaliert es mit RGB_565 auf die Zielbreite herunter und prüft,
     * ob das geräte-basierte Speicherbudget noch Platz hat.
     */
    private fun loadBoundedBitmap(
        urlString: String,
        targetWidthPx: Int,
        usedBytes: AtomicInteger,
        perImageCapBytes: Long,
        totalCapBytes: Long
    ): Bitmap? {
        if (usedBytes.get() >= totalCapBytes) return null
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
                connectTimeout = NETWORK_TIMEOUT_MS
                readTimeout = NETWORK_TIMEOUT_MS
                setRequestProperty("User-Agent", USER_AGENT)
            }
            val bytes = connection.inputStream.use { it.readBytes() }

            // Schritt 1: Dimensionen ermitteln
            val probeOpts = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, probeOpts)

            // Bestimme tatsächliche Bytes pro Pixel (PNG/WebP mit Alpha wird
            // von BitmapFactory auf ARGB_8888 hochgestuft)
            val bytesPerPixel = if (probeOpts.outConfig != null &&
                probeOpts.outConfig != Bitmap.Config.RGB_565
            ) 4L else 2L

            // Schritt 2: inSampleSize berechnen – auf Zielbreite UND auf per-Image-Cap
            var sampleSize = 1
            while (
                (probeOpts.outWidth / (sampleSize * 2) >= targetWidthPx &&
                    probeOpts.outHeight / (sampleSize * 2) >= targetWidthPx) ||
                (probeOpts.outWidth / sampleSize > targetWidthPx * 2) ||
                (probeOpts.outHeight / sampleSize > targetWidthPx * 2)
            ) {
                sampleSize *= 2
            }
            // Zusätzlich auf per-Image-Cap begrenzen
            while (
                (max(1, probeOpts.outWidth / sampleSize).toLong() *
                    max(1, probeOpts.outHeight / sampleSize).toLong() *
                    bytesPerPixel) > perImageCapBytes
            ) {
                sampleSize *= 2
            }

            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.RGB_565
                inJustDecodeBounds = false
            }
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)
                ?: return null

            // Exakt auf Zielbreite skalieren (Seitenverhältnis bleibt erhalten)
            val scaled = if (decoded.width > targetWidthPx) {
                val aspectRatio = decoded.height.toFloat() / decoded.width.toFloat()
                val targetHeightPx = (targetWidthPx * aspectRatio).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(decoded, targetWidthPx, targetHeightPx, true).also {
                    if (it != decoded) decoded.recycle()
                }
            } else {
                decoded
            }

            // Prüfen, ob das Bild noch ins Gesamtbudget passt
            val bitmapBytes = scaled.allocationByteCount
            if (usedBytes.addAndGet(bitmapBytes) > totalCapBytes) {
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
}