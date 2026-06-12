package com.example.kmd_reader.domain.model

data class KmdSourceSnapshot(
    val workId: String,
    val revisionId: String,
    val content: String,
    val fetchedAtMillis: Long,
    val lineOffsets: List<Int>
) {
    val lineCount: Int
        get() = lineOffsets.size

    fun lineAt(lineNumber: Int): String? {
        if (lineNumber !in 1..lineCount) return null

        val index = lineNumber - 1
        val start = lineOffsets[index]
        val endExclusive = if (index + 1 < lineOffsets.size) {
            (lineOffsets[index + 1] - 1).coerceAtLeast(start)
        } else {
            content.length
        }
        return content.substring(start, endExclusive).removeSuffix("\r")
    }

    fun previewSnippet(maxLines: Int = 8): KmdSourceSnippet? {
        val firstLine = firstScriptLine()
        return snippetAround(
            lineNumber = firstLine,
            before = 0,
            after = maxLines - 1
        )
    }

    fun snippetForIssue(
        issue: ScriptIssue,
        before: Int = 2,
        after: Int = 2
    ): KmdSourceSnippet? {
        val lineNumber = lineNumberForIssue(issue)
        return lineNumber?.let {
            snippetAround(
                lineNumber = it,
                before = before,
                after = after
            )
        }
    }

    fun lineNumberForIssue(issue: ScriptIssue): Int? {
        val lineNumber = issue.location.lineNumberHint()
        if (lineNumber != null) {
            return lineNumber
        }

        val locationTerm = issue.location.searchTerm()
        return locationTerm?.let(::findLineContaining)
    }

    fun snippetForRange(
        range: KmdSourceRange,
        before: Int = 2,
        after: Int = 2
    ): KmdSourceSnippet? =
        snippetAround(
            lineNumber = range.startLine,
            before = before,
            after = after
        )

    fun snippetForLine(
        lineNumber: Int,
        before: Int = 2,
        after: Int = 2
    ): KmdSourceSnippet? =
        snippetAround(
            lineNumber = lineNumber,
            before = before,
            after = after
        )

    fun snippetAround(
        lineNumber: Int,
        before: Int = 2,
        after: Int = 2
    ): KmdSourceSnippet? {
        if (lineNumber !in 1..lineCount) return null

        val startLine = (lineNumber - before).coerceAtLeast(1)
        val endLine = (lineNumber + after).coerceAtMost(lineCount)
        val lines = (startLine..endLine).mapNotNull { number ->
            lineAt(number)?.let { text ->
                KmdSourceLine(
                    number = number,
                    text = text,
                    isFocused = number == lineNumber
                )
            }
        }

        return KmdSourceSnippet(
            startLine = startLine,
            endLine = endLine,
            focusedLine = lineNumber,
            lines = lines
        )
    }

    private fun firstScriptLine(): Int {
        if (lineAt(1)?.trim() != "---") {
            return firstNonBlankLine() ?: 1
        }

        for (lineNumber in 2..lineCount) {
            if (lineAt(lineNumber)?.trim() == "---") {
                return firstNonBlankLine(startLine = lineNumber + 1) ?: lineNumber
            }
        }
        return firstNonBlankLine() ?: 1
    }

    private fun firstNonBlankLine(startLine: Int = 1): Int? =
        (startLine..lineCount).firstOrNull { lineAt(it)?.isNotBlank() == true }

    private fun findLineContaining(term: String): Int? {
        val normalizedTerm = term.trim()
        if (normalizedTerm.length < 2) return null

        return (1..lineCount).firstOrNull { lineNumber ->
            lineAt(lineNumber)?.contains(normalizedTerm, ignoreCase = true) == true
        }
    }

    companion object {
        fun fromSource(
            workId: String,
            revisionId: String,
            content: String,
            fetchedAtMillis: Long
        ): KmdSourceSnapshot =
            KmdSourceSnapshot(
                workId = workId,
                revisionId = revisionId,
                content = content,
                fetchedAtMillis = fetchedAtMillis,
                lineOffsets = buildLineOffsets(content)
            )

        private fun buildLineOffsets(content: String): List<Int> {
            if (content.isEmpty()) return listOf(0)

            val offsets = mutableListOf(0)
            content.forEachIndexed { index, char ->
                if (char == '\n' && index + 1 < content.length) {
                    offsets += index + 1
                }
            }
            return offsets
        }
    }
}

data class KmdSourceSnippet(
    val startLine: Int,
    val endLine: Int,
    val focusedLine: Int,
    val lines: List<KmdSourceLine>
)

data class KmdSourceLine(
    val number: Int,
    val text: String,
    val isFocused: Boolean
)

private fun String.lineNumberHint(): Int? {
    val patterns = listOf(
        Regex("""\bline\s*[:#]?\s*(\d+)\b""", RegexOption.IGNORE_CASE),
        Regex("""\bL(\d+)\b""", RegexOption.IGNORE_CASE),
        Regex("""^\s*(\d+)(?::\d+)?\s*$""")
    )
    return patterns.firstNotNullOfOrNull { pattern ->
        pattern.find(this)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }
}

private fun String.searchTerm(): String? {
    val raw = substringAfter(':', this).trim()
    return raw
        .takeIf { it.isNotBlank() }
        ?.removePrefix("@")
        ?.removeSuffix(".kmd")
}
