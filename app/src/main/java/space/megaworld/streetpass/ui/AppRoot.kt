package space.megaworld.streetpass.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import space.megaworld.streetpass.ui.history.HistoryScreen
import space.megaworld.streetpass.ui.home.HomeScreen
import space.megaworld.streetpass.ui.settings.SettingsScreen
import space.megaworld.streetpass.ui.stats.StatsScreen

enum class AppTab(val title: String, val icon: ImageVector) {
    HOME("Главная", Icons.Filled.Home),
    HISTORY("История", Icons.Filled.History),
    STATS("Статистика", Icons.Filled.BarChart),
    SETTINGS("Настройки", Icons.Filled.Settings),
}

@Composable
fun AppRoot() {
    var tab by rememberSaveable { mutableStateOf(AppTab.HOME) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                AppTab.entries.forEach { item ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { tab = item },
                        icon = { Icon(item.icon, contentDescription = null) },
                        label = { Text(item.title) },
                    )
                }
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (tab) {
                AppTab.HOME -> HomeScreen(onOpenHistory = { tab = AppTab.HISTORY })
                AppTab.HISTORY -> HistoryScreen()
                AppTab.STATS -> StatsScreen()
                AppTab.SETTINGS -> SettingsScreen()
            }
        }
    }
}
