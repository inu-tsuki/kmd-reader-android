package com.example.kmd_reader.domain.kmd

import com.example.kmd_reader.domain.model.KmdImportMetadata
import com.example.kmd_reader.domain.model.KmdPresentationHints
import com.example.kmd_reader.domain.model.PresentationMode
import android.util.Log

object KmdSourceMetadataParser {

    private const val TAG = "KmdSourceMetadata"

    fun parsePresentationHints(source: String): KmdPresentationHints {
        val frontMatter = parseFrontMatter(source) ?: return KmdPresentationHints()
        return KmdPresentationHints(
            presentationMode = parsePresentationMode(frontMatter["mode"]),
            designWidth = frontMatter["designWidth"].toPositiveIntOrNull(),
            designHeight = frontMatter["designHeight"].toPositiveIntOrNull()
        )
    }

    /**
     * 解析导入用元数据（title / author / speed + presentation hints）。
     * mode 按主仓库 frontmatter-schema.md：paged → page 归一化；interactive 降级 stage + 诊断。
     */
    fun parseImportMetadata(source: String): KmdImportMetadata {
        val frontMatter = parseFrontMatter(source) ?: return KmdImportMetadata(
            title = null, author = null, hints = KmdPresentationHints()
        )
        return KmdImportMetadata(
            title = frontMatter["title"]?.takeIf { it.isNotBlank() },
            author = frontMatter["author"]?.takeIf { it.isNotBlank() },
            hints = KmdPresentationHints(
                presentationMode = parsePresentationMode(frontMatter["mode"]),
                designWidth = frontMatter["designWidth"].toPositiveIntOrNull(),
                designHeight = frontMatter["designHeight"].toPositiveIntOrNull()
            )
        )
    }

    private fun parseFrontMatter(source: String): Map<String, String>? {
        val lines = source.lineSequence().toList()
        if (lines.firstOrNull()?.trim() != "---") {
            return null
        }

        val values = mutableMapOf<String, String>()
        for (index in 1 until lines.size) {
            val line = lines[index].trim()
            if (line == "---") {
                return values
            }
            if (line.isBlank() || line.startsWith("#") || line.startsWith("//")) {
                continue
            }

            val separator = line.indexOf(':')
            if (separator <= 0) {
                continue
            }
            val key = line.substring(0, separator).trim()
            val value = line.substring(separator + 1)
                .trim()
                .trim('"', '\'')
            values[key] = value
        }
        return null
    }

    /**
     * mode 解析按主仓库 frontmatter-schema.md §4：
     * - canonical 枚举 = stage / scroll / page
     * - paged = 导入兼容别名，归一化为 page
     * - interactive = 不是合法 runtime mode，降级为 stage + 诊断警告
     */
    private fun parsePresentationMode(raw: String?): PresentationMode? =
        when (raw?.trim()?.lowercase()) {
            "scroll" -> PresentationMode.Scroll
            "page", "paged" -> PresentationMode.Paged
            "stage" -> PresentationMode.Stage
            "interactive" -> {
                Log.w(TAG, "mode: interactive is not a valid runtime mode, downgrading to stage")
                PresentationMode.Stage
            }
            else -> null
        }

    private fun String?.toPositiveIntOrNull(): Int? =
        this?.toIntOrNull()?.takeIf { it > 0 }
}