package com.splittesla.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.splittesla.app.AppViewModel
import com.splittesla.app.LinkState

private val tabs = listOf("대시보드", "차량", "S3XY 동작", "설정·로그")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SplitTeslaApp(vm: AppViewModel) {
    val ui by vm.ui.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()

    MaterialTheme(colorScheme = colors) {
        Scaffold(
            topBar = {
                Column {
                    TopAppBar(title = { Text("SplitTesla") })
                    Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LinkChip("S3XY", ui.commander) {
                            if (ui.commander == LinkState.CONNECTED) vm.disconnectCommander() else vm.connectCommander()
                        }
                        LinkChip("차량 BLE", ui.car) {
                            if (ui.car == LinkState.CONNECTED) vm.disconnectCar() else vm.connectCar()
                        }
                    }
                    if (ui.busy != null) LinearProgressIndicator(Modifier.padding(top = 4.dp))
                }
            },
            bottomBar = {
                NavigationBar {
                    tabs.forEachIndexed { index, title ->
                        NavigationBarItem(
                            selected = tab == index,
                            onClick = { tab = index },
                            icon = {},
                            label = { Text(title) },
                        )
                    }
                }
            },
        ) { padding ->
            val modifier = Modifier.padding(padding).fillMaxSize()
            when (tab) {
                0 -> DashboardScreen(ui, modifier)
                1 -> CarScreen(vm, ui, modifier)
                2 -> ActionsScreen(vm, ui, modifier)
                else -> SettingsScreen(vm, ui, modifier)
            }
        }
    }
}

@Composable
private fun LinkChip(name: String, state: LinkState, onClick: () -> Unit) {
    AssistChip(onClick = onClick, label = { Text("$name: ${state.label}") })
}
