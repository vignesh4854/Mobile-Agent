package com.example.mobileagent.research

data class SearchResult(
    val title: String,
    val url: String,
    val description: String
)

data class SavedResearch(
    val title: String,
    val url: String,
    val retrievedAt: Long,
    val content: String
)
