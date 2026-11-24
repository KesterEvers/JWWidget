package com.example.jwwidget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Falls die Activity beim Hinzufügen eines Widgets als Konfigurations-Screen geöffnet wurde
        val appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            val resultValue = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            setResult(Activity.RESULT_CANCELED, resultValue)
        }

        setContent {
            val darkTheme = isSystemInDarkTheme()
            val colorScheme = if (darkTheme) {
                darkColorScheme(primary = Color(0xFFFF5555))
            } else {
                lightColorScheme(primary = Color(0xFFCC0000))
            }

            MaterialTheme(colorScheme = colorScheme) {
                SettingsScreen(
                    onConfigured = {
                        if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                            val resultValue = Intent().putExtra(
                                AppWidgetManager.EXTRA_APPWIDGET_ID,
                                appWidgetId
                            )
                            setResult(Activity.RESULT_OK, resultValue)
                            finish()
                        }
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onConfigured: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var settings by remember { mutableStateOf(WidgetSettingsRepository.load(context)) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "junge Welt Widget – Einstellungen",
                        fontWeight = FontWeight.Bold
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFFCC0000),
                    titleContentColor = Color.White
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // --- Sektion 1: Feed & Filter ---
            SectionHeader("Feed & Filter")

            SliderSetting(
                label = "Anzahl geladener Artikel",
                valueText = "${settings.maxArticles}",
                value = settings.maxArticles.toFloat(),
                range = 5f..50f,
                steps = 24,
                onValueChange = { settings = settings.copy(maxArticles = it.roundToInt()) }
            )

            SwitchSetting(
                title = "[Online-Abo]-Artikel ausblenden",
                checked = settings.hideOnlineAbo,
                onCheckedChange = { settings = settings.copy(hideOnlineAbo = it) }
            )

            SwitchSetting(
                title = "Kategorie & Alter anzeigen",
                checked = settings.showCategoryAndTime,
                onCheckedChange = { settings = settings.copy(showCategoryAndTime = it) }
            )

            HorizontalDivider()

            // --- Sektion 2: Schriftgrößen ---
            SectionHeader("Schriftgrößen")

            SliderSetting(
                label = "Überschrift",
                valueText = "${settings.titleFontSizeSp.roundToInt()} sp",
                value = settings.titleFontSizeSp,
                range = 12f..24f,
                steps = 11,
                onValueChange = { settings = settings.copy(titleFontSizeSp = it.roundToInt().toFloat()) }
            )

            SliderSetting(
                label = "Beschreibung (Teaser)",
                valueText = "${settings.descriptionFontSizeSp.roundToInt()} sp",
                value = settings.descriptionFontSizeSp,
                range = 9f..18f,
                steps = 8,
                onValueChange = {
                    settings = settings.copy(descriptionFontSizeSp = it.roundToInt().toFloat())
                }
            )

            SliderSetting(
                label = "Kategorie & Zeitstempel",
                valueText = "${settings.metaFontSizeSp.roundToInt()} sp",
                value = settings.metaFontSizeSp,
                range = 7f..14f,
                steps = 6,
                onValueChange = { settings = settings.copy(metaFontSizeSp = it.roundToInt().toFloat()) }
            )

            HorizontalDivider()

            // --- Sektion 3: Layout & Bilder ---
            SectionHeader("Layout & Bilder")

            SwitchSetting(
                title = "Artikelbilder anzeigen",
                checked = settings.showImages,
                onCheckedChange = { settings = settings.copy(showImages = it) }
            )

            if (settings.showImages) {
                SliderSetting(
                    label = "Bildbreite",
                    valueText = "${settings.imageWidthDp} dp",
                    value = settings.imageWidthDp.toFloat(),
                    range = 50f..140f,
                    steps = 17,
                    onValueChange = { settings = settings.copy(imageWidthDp = it.roundToInt()) }
                )
            }

            SwitchSetting(
                title = "Beschreibungstext anzeigen",
                checked = settings.showDescription,
                onCheckedChange = { settings = settings.copy(showDescription = it) }
            )

            if (settings.showDescription) {
                SliderSetting(
                    label = "Max. Zeilen Beschreibung",
                    valueText = "${settings.descriptionMaxLines}",
                    value = settings.descriptionMaxLines.toFloat(),
                    range = 1f..20f,
                    steps = 18,
                    onValueChange = { settings = settings.copy(descriptionMaxLines = it.roundToInt()) }
                )
            }

            SliderSetting(
                label = "Max. Zeilen Überschrift",
                valueText = "${settings.titleMaxLines}",
                value = settings.titleMaxLines.toFloat(),
                range = 1f..5f,
                steps = 3,
                onValueChange = { settings = settings.copy(titleMaxLines = it.roundToInt()) }
            )

            SliderSetting(
                label = "Logo-Höhe im Header",
                valueText = "${settings.logoHeightDp} dp",
                value = settings.logoHeightDp.toFloat(),
                range = 20f..48f,
                steps = 13,
                onValueChange = { settings = settings.copy(logoHeightDp = it.roundToInt()) }
            )

            HorizontalDivider()

            // --- Sektion 4: Darstellung ---
            SectionHeader("Darstellung")

            SliderSetting(
                label = "Hintergrund-Deckkraft",
                valueText = "${(settings.backgroundAlpha * 100).roundToInt()} %",
                value = settings.backgroundAlpha,
                range = 0.2f..1.0f,
                steps = 15,
                onValueChange = {
                    val stepped = (it * 20).roundToInt() / 20f
                    settings = settings.copy(backgroundAlpha = stepped)
                }
            )

            Spacer(modifier = Modifier.height(8.dp))

            // --- Aktions-Buttons ---
            Button(
                onClick = {
                    WidgetSettingsRepository.save(context, settings)
                    scope.launch {
                        JWWidget().updateAll(context)
                        Toast.makeText(context, "Gespeichert & Widget aktualisiert", Toast.LENGTH_SHORT).show()
                        onConfigured()
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC002E)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Speichern & Widget aktualisieren", color = Color.White)
            }
            OutlinedButton(
                onClick = {
                    settings = WidgetSettings()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Auf Standardwerte zurücksetzen")
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary
    )
}
@Composable
private fun SwitchSetting(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
@Composable
private fun SliderSetting(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = valueText,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            steps = steps
        )
    }
}