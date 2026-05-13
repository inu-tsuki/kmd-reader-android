package com.example.kmd_reader.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.kmd_reader.data.mock.MockWorks
import com.example.kmd_reader.data.repository.toEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE, sdk = [28])
class KmdReaderDatabaseTest {
    private lateinit var database: KmdReaderDatabase
    private lateinit var workDao: WorkDao
    private lateinit var issueDao: ScriptIssueDao

    @Before
    fun createDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, KmdReaderDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        workDao = database.workDao()
        issueDao = database.scriptIssueDao()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun insertWorksThenQueryAllReturnsInsertedWorks() = runTest {
        val now = 1_700_000_000_000L

        workDao.upsertAll(MockWorks.works.take(2).map { it.toEntity(now) })

        val works = workDao.getAll()
        assertEquals(2, works.size)
        assertNotNull(workDao.getById("rain-city"))
    }

    @Test
    fun upsertWorkWithSameIdReplacesOldValue() = runTest {
        val work = MockWorks.works.first { it.id == "rain-city" }
        val oldEntity = work.toEntity(syncedAt = 1L)
        val newEntity = oldEntity.copy(title = "雨城慢镜 Revised", syncedAt = 2L)

        workDao.upsert(oldEntity)
        workDao.upsert(newEntity)

        val saved = requireNotNull(workDao.getById("rain-city"))
        assertEquals("雨城慢镜 Revised", saved.title)
        assertEquals(2L, saved.syncedAt)
    }

    @Test
    fun replaceIssuesForWorkClearsOldIssues() = runTest {
        val work = MockWorks.works.first { it.id == "glass-rail" }
        val issues = requireNotNull(MockWorks.issues["glass-rail"])
        workDao.upsert(work.toEntity(syncedAt = 1L))
        issueDao.upsertAll(issues.map { it.toEntity(syncedAt = 1L) })

        issueDao.replaceForWork(
            workId = "glass-rail",
            issues = issues.take(1).map { it.toEntity(syncedAt = 2L) }
        )

        val saved = issueDao.getByWorkId("glass-rail")
        assertEquals(1, saved.size)
        assertEquals(2L, saved.single().syncedAt)
    }
}
