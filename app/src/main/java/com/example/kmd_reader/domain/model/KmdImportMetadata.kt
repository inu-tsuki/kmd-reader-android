package com.example.kmd_reader.domain.model

/**
 * 导入用元数据：从 .kmd frontmatter 解析出的作品标识 + presentation hints。
 * 用于本地导入时构造 LocalLibraryEntry。
 */
data class KmdImportMetadata(
    val title: String?,
    val author: String?,
    val hints: KmdPresentationHints
)