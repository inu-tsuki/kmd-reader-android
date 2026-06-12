package com.example.kmd_reader.domain.kmd

import com.example.kmd_reader.domain.model.KmdPresentationHints
import com.example.kmd_reader.domain.model.PresentationMode

object KmdSourceMetadataParser {
    fun parsePresentationHints(source: String): KmdPresentationHints {
        val frontMatter = parseFrontMatter(source) ?: return KmdPresentationHints()
        return KmdPresentationHints(
            presentationMode = parsePresentationMode(frontMatter["mode"]),
            designWidth = frontMatter["designWidth"].toPositiveIntOrNull(),
            designHeight = frontMatter["designHeight"].toPositiveIntOrNull()
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
            if (line.isBlank() || line.startsWith("#")) {
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

    private fun parsePresentationMode(raw: String?): PresentationMode? =
        when (raw?.trim()?.lowercase()) {
            "scroll" -> PresentationMode.Scroll
            "page",
            "paged" -> PresentationMode.Paged
            "stage" -> PresentationMode.Stage
            "interactive" -> PresentationMode.Interactive
            else -> null
        }

    private fun String?.toPositiveIntOrNull(): Int? =
        this?.toIntOrNull()?.takeIf { it > 0 }
}
