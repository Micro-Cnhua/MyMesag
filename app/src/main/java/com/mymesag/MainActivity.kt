package com.mymesag

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.mymesag.ui.AppSelectScreen
import com.mymesag.ui.HomeScreen
import com.mymesag.ui.MqttTestScreen
import com.mymesag.ui.MyMesagTheme
import com.mymesag.ui.PairScreen
import com.mymesag.ui.SyncSettingsScreen
import com.mymesag.ui.UserManualScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestRuntimePermissions()
        setContent {
            val themeMode by com.mymesag.ui.ThemeManager.themeMode.collectAsState()
            MyMesagTheme(themeMode = themeMode) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var screen by remember { mutableStateOf(Screen.HOME) }
                    var tab by remember { mutableStateOf(MainTab.HOME) }

                    BackHandler(enabled = screen != Screen.HOME || tab != MainTab.HOME) {
                        if (screen != Screen.HOME) {
                            screen = Screen.HOME
                        } else {
                            tab = MainTab.HOME
                        }
                    }

                    when (screen) {
                        Screen.HOME -> Scaffold(
                            bottomBar = {
                                NavigationBar(
                                    containerColor = MaterialTheme.colorScheme.surface,
                                    tonalElevation = 3.dp
                                ) {
                                    NavigationBarItem(
                                        selected = tab == MainTab.HOME,
                                        onClick = { tab = MainTab.HOME },
                                        icon = { Icon(Icons.Default.Home, contentDescription = null) },
                                        label = { Text("主页") }
                                    )
                                    NavigationBarItem(
                                        selected = tab == MainTab.SYNC,
                                        onClick = { tab = MainTab.SYNC },
                                        icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                                        label = { Text("设置操作") }
                                    )
                                    NavigationBarItem(
                                        selected = tab == MainTab.APPS,
                                        onClick = { tab = MainTab.APPS },
                                        icon = { Icon(Icons.Default.List, contentDescription = null) },
                                        label = { Text("应用选择") }
                                    )
                                }
                            }
                        ) { padding ->
                            Box(modifier = Modifier.padding(padding)) {
                                when (tab) {
                                    MainTab.HOME -> HomeScreen(
                                        onOpenMqtt = { screen = Screen.MQTT },
                                        onOpenPair = { screen = Screen.PAIR }
                                    )
                                    MainTab.SYNC -> SyncSettingsScreen(
                                        onOpenMqtt = { screen = Screen.MQTT },
                                        onOpenPair = { screen = Screen.PAIR },
                                        onOpenManual = { screen = Screen.MANUAL }
                                    )
                                    MainTab.APPS -> AppSelectScreen()
                                }
                            }
                        }
                        Screen.MQTT -> MqttTestScreen(onBack = { screen = Screen.HOME })
                        Screen.PAIR -> PairScreen(onBack = { screen = Screen.HOME })
                        Screen.MANUAL -> UserManualScreen(onBack = { screen = Screen.HOME })
                    }
                }
            }
        }
    }

    private fun requestRuntimePermissions() {
        val permissions = mutableListOf<String>()
        fun need(p: String) =
            ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED

        if (need(Manifest.permission.READ_PHONE_STATE)) permissions.add(Manifest.permission.READ_PHONE_STATE)
        if (need(Manifest.permission.READ_CONTACTS)) permissions.add(Manifest.permission.READ_CONTACTS)
        if (Build.VERSION.SDK_INT >= 33 && need(Manifest.permission.POST_NOTIFICATIONS)) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 1)
        }
    }
}

private enum class Screen { HOME, MQTT, PAIR, MANUAL }
private enum class MainTab { HOME, SYNC, APPS }
