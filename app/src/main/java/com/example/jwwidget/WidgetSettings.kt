package com.example.jwwidget

import android.content.Context

data class WidgetSettings(
    // Feed & Filter
    val maxArticles: Int = 30,
    val hideOnlineAbo: Boolean = false,
    val showCategoryAndTime: Boolean = true,

    // Typografie (in sp)
    val metaFontSizeSp: Float = 9f,
    val titleFontSizeSp: Float = 17f,
    val descriptionFontSizeSp: Float = 12f,

    // Layout & Sichtbarkeit
    val showDescription: Boolean = true,
    val descriptionMaxLines: Int = 6,
    val titleMaxLines: Int = 3,
    val showImages: Boolean = true,
    val imageWidthDp: Int = 84,
    val logoHeightDp: Int = 32,

    // Darstellung
    val backgroundAlpha: Float = 1.0f
)

object WidgetSettingsRepository {
    private const val PREFS_NAME = "jw_widget_settings"

    fun load(context: Context): WidgetSettings {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val defaults = WidgetSettings()

        return WidgetSettings(
            maxArticles = prefs.getInt("max_articles", defaults.maxArticles),
            hideOnlineAbo = prefs.getBoolean("hide_online_abo", defaults.hideOnlineAbo),
            showCategoryAndTime = prefs.getBoolean("show_category_and_time", defaults.showCategoryAndTime),
            metaFontSizeSp = prefs.getFloat("meta_font_size", defaults.metaFontSizeSp),
            titleFontSizeSp = prefs.getFloat("title_font_size", defaults.titleFontSizeSp),
            descriptionFontSizeSp = prefs.getFloat("description_font_size", defaults.descriptionFontSizeSp),
            showDescription = prefs.getBoolean("show_description", defaults.showDescription),
            descriptionMaxLines = prefs.getInt("description_max_lines", defaults.descriptionMaxLines),
            titleMaxLines = prefs.getInt("title_max_lines", defaults.titleMaxLines),
            showImages = prefs.getBoolean("show_images", defaults.showImages),
            imageWidthDp = prefs.getInt("image_width_dp", defaults.imageWidthDp),
            logoHeightDp = prefs.getInt("logo_height_dp", defaults.logoHeightDp),
            backgroundAlpha = prefs.getFloat("background_alpha", defaults.backgroundAlpha)
        )
    }

    fun save(context: Context, settings: WidgetSettings) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
            putInt("max_articles", settings.maxArticles)
            putBoolean("hide_online_abo", settings.hideOnlineAbo)
            putBoolean("show_category_and_time", settings.showCategoryAndTime)
            putFloat("meta_font_size", settings.metaFontSizeSp)
            putFloat("title_font_size", settings.titleFontSizeSp)
            putFloat("description_font_size", settings.descriptionFontSizeSp)
            putBoolean("show_description", settings.showDescription)
            putInt("description_max_lines", settings.descriptionMaxLines)
            putInt("title_max_lines", settings.titleMaxLines)
            putBoolean("show_images", settings.showImages)
            putInt("image_width_dp", settings.imageWidthDp)
            putInt("logo_height_dp", settings.logoHeightDp)
            putFloat("background_alpha", settings.backgroundAlpha)
            apply()
        }
    }
}
