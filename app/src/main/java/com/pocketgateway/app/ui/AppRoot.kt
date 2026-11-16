package com.pocketgateway.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Article
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pocketgateway.app.ui.screen.ChannelsScreen
import com.pocketgateway.app.ui.screen.DashboardScreen
import com.pocketgateway.app.ui.screen.LogsScreen
import com.pocketgateway.app.ui.screen.SettingsScreen
import com.pocketgateway.app.ui.viewmodel.GatewayViewModel

@Composable
fun AppRoot(vm: GatewayViewModel = viewModel()) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val tabs = listOf("总览" to Icons.Filled.Home, "渠道" to Icons.Filled.Settings, "日志" to Icons.Filled.Article)

    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { index, (label, icon) ->
                    NavigationBarItem(
                        selected = tab == index,
                        onClick = { tab = index },
                        icon = { Icon(icon, contentDescription = label) },
                        label = { Text(label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            when (tab) {
                0 -> DashboardScreen(vm)
                1 -> ChannelsScreen(vm)
                2 -> LogsScreen(vm)
            }
        }
    }
}
