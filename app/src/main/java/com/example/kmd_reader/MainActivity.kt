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

class MainActivity : ComponentActivity() {
    private val appContainer by lazy {
        KmdReaderAppContainer(applicationContext)
    }

    private val readerViewModel: KmdReaderViewModel by viewModels {
        KmdReaderViewModel.Factory(
            repository = appContainer.workRepository,
            runtimeBridge = appContainer.readerRuntimeBridge
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            KmdreaderTheme {
                KmdReaderApp(
                    viewModel = readerViewModel,
                    runtimeBridge = readerViewModel.runtimeBridgeForHost
                )
            }
        }
    }
}
