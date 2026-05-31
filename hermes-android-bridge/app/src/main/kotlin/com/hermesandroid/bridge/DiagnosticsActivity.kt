package com.hermesandroid.bridge

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.hermesandroid.bridge.ui.DiagnosticsScreen
import com.hermesandroid.bridge.ui.LogActivity

class DiagnosticsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DiagnosticsScreen(
                onBack = { finish() },
                onOpenLogs = {
                    startActivity(Intent(this, LogActivity::class.java))
                }
            )
        }
    }
}
