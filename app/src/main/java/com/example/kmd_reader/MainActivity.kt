package com.example.kmd_reader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.example.kmd_reader.data.KmdReaderAppContainer
import com.example.kmd_reader.presentation.KmdReaderViewModel
import com.example.kmd_reader.ui.app.KmdReaderApp
import com.example.kmd_reader.ui.theme.KmdreaderTheme
import com.example.kmd_reader.data.preferences.ThemeMode
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.foundation.isSystemInDarkTheme

class MainActivity : ComponentActivity() {
    private val appContainer by lazy {
        KmdReaderAppContainer(applicationContext)
    }

    private val readerViewModel: KmdReaderViewModel by viewModels {
        KmdReaderViewModel.Factory(
            repository = appContainer.workRepository,
            runtimeBridge = appContainer.readerRuntimeBridge,
            localLibrary = appContainer.localLibraryRepository,
            bundleStore = appContainer.bundleStore,
            appContext = applicationContext,
            preferencesRepository = appContainer.readerPreferencesRepository
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val state by readerViewModel.state.collectAsState()
            val darkTheme = when (state.readerPreferences.themeMode) {
                ThemeMode.System -> isSystemInDarkTheme()
                ThemeMode.Light -> false
                ThemeMode.Dark -> true
            }
            KmdreaderTheme(darkTheme = darkTheme) {
                KmdReaderApp(
                    viewModel = readerViewModel,
                    runtimeBridge = readerViewModel.runtimeBridgeForHost
                )
            }
        }
    }
}
