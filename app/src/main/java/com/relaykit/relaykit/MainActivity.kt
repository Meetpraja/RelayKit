package com.relaykit.relaykit

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.relaykit.demo.core.design.theme.RelayKitTheme
import com.relaykit.demo.core.navigation.RelayKitApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { RelayKitTheme { RelayKitApp() } }
    }
}
