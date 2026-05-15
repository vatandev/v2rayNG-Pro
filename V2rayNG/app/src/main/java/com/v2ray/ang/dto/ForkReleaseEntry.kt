package com.v2ray.ang.dto

data class ForkReleaseEntry(
    val version: String = "",
    val title_en: String = "",
    val title_fa: String = "",
    val body_en: String = "",
    val body_fa: String = ""
)

data class ForkReleaseCatalog(
    val releases: List<ForkReleaseEntry> = emptyList()
)
