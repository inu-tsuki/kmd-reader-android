package com.example.kmd_reader.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KmdSourceSnapshotTest {
    @Test
    fun previewSkipsFrontMatter() {
        val snapshot = KmdSourceSnapshot.fromSource(
            workId = "work",
            revisionId = "rev-1",
            fetchedAtMillis = 1L,
            content = """
                ---
                title: Glass Rail
                mode: stage
                ---

                @ cam.move(120, 0, 1s)!
                The bridge shines.
            """.trimIndent()
        )

        val preview = requireNotNull(snapshot.previewSnippet(maxLines = 2))

        assertEquals(6, preview.startLine)
        assertEquals("@ cam.move(120, 0, 1s)!", preview.lines.first().text)
    }

    @Test
    fun issueSnippetUsesLineLocation() {
        val snapshot = KmdSourceSnapshot.fromSource(
            workId = "work",
            revisionId = "rev-1",
            fetchedAtMillis = 1L,
            content = "one\ntwo\nthree\nfour"
        )
        val issue = ScriptIssue(
            id = "issue",
            workId = "work",
            severity = IssueSeverity.Warning,
            source = IssueSource.Parser,
            location = "line 3",
            message = "message",
            suggestion = "suggestion"
        )

        val snippet = requireNotNull(snapshot.snippetForIssue(issue, before = 1, after = 1))

        assertEquals(2, snippet.startLine)
        assertEquals(4, snippet.endLine)
        assertTrue(snippet.lines.single { it.number == 3 }.isFocused)
        assertEquals("three", snippet.lines.single { it.number == 3 }.text)
    }

    @Test
    fun issueSnippetSearchesLocationTermWhenLineIsMissing() {
        val snapshot = KmdSourceSnapshot.fromSource(
            workId = "work",
            revisionId = "rev-1",
            fetchedAtMillis = 1L,
            content = "intro\nasset rail_glow.png is declared\nending"
        )
        val issue = ScriptIssue(
            id = "issue",
            workId = "work",
            severity = IssueSeverity.Info,
            source = IssueSource.Asset,
            location = "asset: rail_glow.png",
            message = "message",
            suggestion = "suggestion"
        )

        val snippet = requireNotNull(snapshot.snippetForIssue(issue, before = 0, after = 0))

        assertEquals(2, snippet.focusedLine)
        assertEquals("asset rail_glow.png is declared", snippet.lines.single().text)
    }

    @Test
    fun issueSnippetReturnsNullWhenLocationCannotBeResolved() {
        val snapshot = KmdSourceSnapshot.fromSource(
            workId = "work",
            revisionId = "rev-1",
            fetchedAtMillis = 1L,
            content = "one\ntwo"
        )
        val issue = ScriptIssue(
            id = "issue",
            workId = "work",
            severity = IssueSeverity.Info,
            source = IssueSource.Runtime,
            location = "reader-runtime",
            message = "message",
            suggestion = "suggestion"
        )

        assertNull(snapshot.snippetForIssue(issue))
    }
}
