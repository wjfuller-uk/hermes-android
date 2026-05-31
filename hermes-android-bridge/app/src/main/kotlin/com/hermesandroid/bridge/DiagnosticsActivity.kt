package com.hermesandroid.bridge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.hermesandroid.bridge.ui.DiagnosticsScreen

class DiagnosticsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DiagnosticsScreen(
                onBack = { finish() }
            )
        }
    }
}
