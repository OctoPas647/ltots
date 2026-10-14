package com.ltots.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.ltots.app.playback.PlayerConnection
import com.ltots.app.ui.AppRoot
import com.ltots.app.ui.theme.LToTSTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        PlayerConnection.connect(this)
        setContent {
            LToTSTheme { AppRoot() }
        }
    }
}
