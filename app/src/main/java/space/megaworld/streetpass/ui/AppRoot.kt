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
import androidx.annotation.StringRes
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import space.megaworld.streetpass.R
import space.megaworld.streetpass.ui.history.HistoryScreen
import space.megaworld.streetpass.ui.home.HomeScreen
import space.megaworld.streetpass.ui.settings.SettingsScreen
import space.megaworld.streetpass.ui.stats.StatsScreen

enum class AppTab(@StringRes val titleRes: Int, val icon: ImageVector) {
    HOME(R.string.tab_home, Icons.Filled.Home),
    HISTORY(R.string.tab_history, Icons.Filled.History),
    STATS(R.string.tab_stats, Icons.Filled.BarChart),
    SETTINGS(R.string.tab_settings, Icons.Filled.Settings),
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
                        label = { Text(stringResource(item.titleRes)) },
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
