package space.megaworld.streetpass.ui

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import space.megaworld.streetpass.StreetPassApp
import space.megaworld.streetpass.ui.friends.FriendInviteViewModel
import space.megaworld.streetpass.ui.history.HistoryViewModel
import space.megaworld.streetpass.ui.home.HomeViewModel
import space.megaworld.streetpass.ui.peer.PeerViewModel
import space.megaworld.streetpass.ui.settings.SettingsViewModel
import space.megaworld.streetpass.ui.stats.StatsViewModel

object AppViewModelProvider {

    val Factory = viewModelFactory {
        initializer { HomeViewModel(app().container, app()) }
        initializer { HistoryViewModel(app().container) }
        initializer { StatsViewModel(app().container) }
        initializer { SettingsViewModel(app().container, app()) }
        initializer { PeerViewModel(app().container) }
        initializer { FriendInviteViewModel(app().container) }
    }

    private fun CreationExtras.app(): StreetPassApp =
        this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as StreetPassApp
}
