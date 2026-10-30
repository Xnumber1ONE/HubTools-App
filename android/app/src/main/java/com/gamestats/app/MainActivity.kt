package com.gamestats.app

import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Surface
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onStart() {
        super.onStart()
        vm.refresh() // new data every time the app is opened
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )
        setContent { GameStatsTheme { App(vm) } }
    }
}

@Composable
fun App(vm: MainViewModel = viewModel()) {
    var selected by rememberSaveable { mutableStateOf<Long?>(null) }
    var showSettings by rememberSaveable { mutableStateOf(!vm.configured) }
    val game = vm.snapshot?.games?.firstOrNull { it.appId == selected }
    val listState = rememberLazyListState() // kept here so the list keeps its place after opening a game

    BackHandler(enabled = showSettings || game != null) {
        if (showSettings) showSettings = false else selected = null
    }

    // Surface supplies the text/icon color (without it, text defaults to black)
    Surface(Modifier.fillMaxSize(), color = Bg, contentColor = TextMain) {
        Box(Modifier.fillMaxSize().systemBarsPadding()) {
            when {
                showSettings -> SettingsScreen(vm, onClose = { showSettings = false })
                game != null -> GameScreen(game, onBack = { selected = null })
                else -> HomeScreen(
                    vm,
                    listState,
                    onOpen = { selected = it.appId },
                    onSettings = { showSettings = true },
                )
            }
        }
    }
}
