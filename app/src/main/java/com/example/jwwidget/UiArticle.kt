package com.example.jwwidget
import android.graphics.Bitmap

data class UiArticle(
    val id: String,
    val link: String,
    val category: String,
    val title: String,
    val description: String,
    val relativeTime: String,
    val bitmap: Bitmap?
)