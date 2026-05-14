package com.example.kmd_reader.runtime

import com.example.kmd_reader.data.mock.MockWorks
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FakeReaderRuntimeBridgeTest {
    @Test
    fun loadEmitsReadyAndProgressEvents() = runTest {
        val bridge = FakeReaderRuntimeBridge()
        val events = mutableListOf<ReaderRuntimeEvent>()
        val collectJob = launch {
            bridge.events.take(2).toList(events)
        }
        runCurrent()

        bridge.load(ReaderLoadRequest(work = MockWorks.works.first { it.id == "glass-rail" }))
        collectJob.join()

        assertTrue(events[0] is ReaderRuntimeEvent.Ready)
        assertTrue(events[1] is ReaderRuntimeEvent.ProgressChanged)
        assertEquals(
            0.42f,
            (events[1] as ReaderRuntimeEvent.ProgressChanged).progress,
            0.001f
        )
    }

    @Test
    fun inspectionEmitsRuntimeIssueForCurrentWork() = runTest {
        val bridge = FakeReaderRuntimeBridge()
        val events = mutableListOf<ReaderRuntimeEvent>()
        val collectJob = launch {
            bridge.events.take(3).toList(events)
        }
        runCurrent()

        bridge.load(ReaderLoadRequest(work = MockWorks.works.first { it.id == "glass-rail" }))
        bridge.setInspectionEnabled(true)
        collectJob.join()

        val inspection = events.last() as ReaderRuntimeEvent.InspectionReported
        assertEquals("glass-rail", inspection.workId)
        assertEquals("runtime-glass-rail-inspection", inspection.issues.single().id)
    }
}
