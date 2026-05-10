package com.example.kmd_reader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.kmd_reader.ui.app.KmdReaderApp
import com.example.kmd_reader.ui.theme.KmdreaderTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            KmdreaderTheme {
                KmdReaderApp()
            }
        }
    }
}
