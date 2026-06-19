package com.example.kmd_reader.data

import android.content.Context
import com.example.kmd_reader.data.local.KmdReaderDatabase
import com.example.kmd_reader.data.remote.NetworkModule
import com.example.kmd_reader.data.repository.FallbackWorkRepository
import com.example.kmd_reader.data.repository.LocalLibraryRepository
import com.example.kmd_reader.data.repository.OfflineFirstWorkRepository
import com.example.kmd_reader.data.repository.RoomLocalLibraryRepository
import com.example.kmd_reader.runtime.ReaderRuntimeBridge
import com.example.kmd_reader.runtime.webview.WebViewReaderRuntimeBridge

class KmdReaderAppContainer(context: Context) {
    private val appContext = context.applicationContext

    private val database: KmdReaderDatabase by lazy {
        KmdReaderDatabase.create(appContext)
    }

    private val offlineFirstRepository: WorkRepository by lazy {
        OfflineFirstWorkRepository(
            workDao = database.workDao(),
            issueDao = database.scriptIssueDao(),
            api = NetworkModule.createApi()
        )
    }

    val workRepository: WorkRepository by lazy {
        FallbackWorkRepository(
            primary = offlineFirstRepository,
            fallback = MockWorkRepository()
        )
    }

    val readerRuntimeBridge: ReaderRuntimeBridge by lazy {
        WebViewReaderRuntimeBridge()
    }

    val localLibraryRepository: LocalLibraryRepository by lazy {
        RoomLocalLibraryRepository(
            libraryDao = database.localLibraryDao(),
            revisionDao = database.localRevisionDao(),
            draftDao = database.localDraftDao()
        )
    }
}
