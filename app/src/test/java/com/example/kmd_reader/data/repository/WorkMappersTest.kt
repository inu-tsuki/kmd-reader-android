package com.example.kmd_reader.data.repository

import com.example.kmd_reader.data.mock.MockWorks
import com.example.kmd_reader.domain.model.PresentationMode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class WorkMappersTest {
    @Test
    fun workToEntityToDomainPreservesPresentationMode() = runTest {
        val work = MockWorks.works.first { it.id == "glass-rail" }

        val restored = work.toEntity(syncedAt = 1_700_000_000_000L).toDomain()

        assertEquals(PresentationMode.Stage, restored.presentation.mode)
        assertEquals(work.presentation.orientationHint, restored.presentation.orientationHint)
        assertEquals(work.commentSummary.concerns, restored.commentSummary.concerns)
        assertEquals(work.script.activeRevision.sourceUrl, restored.script.activeRevision.sourceUrl)
        assertEquals(work.script.activeRevision.runtimeVersion, restored.script.activeRevision.runtimeVersion)
    }
}
