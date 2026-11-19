package com.example.jwwidget

data class Article(
    val title: String,
    val description: String,
    val link: String,
    val pubDate: String,
    val pubDateMillis: Long, // <-- Dieses Feld ergänzen
    val category: String,
    val imageUrl: String?
)