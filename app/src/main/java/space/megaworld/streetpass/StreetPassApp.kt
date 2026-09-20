package space.megaworld.streetpass

import android.app.Application
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import space.megaworld.streetpass.ble.DiscoveryState
import space.megaworld.streetpass.data.EncounterRepository
import space.megaworld.streetpass.data.appDataStore
import space.megaworld.streetpass.data.db.AppDatabase
import space.megaworld.streetpass.data.identity.IdentityRepository
import space.megaworld.streetpass.data.settings.SettingsRepository
import space.megaworld.streetpass.data.update.UpdateRepository

class StreetPassApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/** Единственный способ получения зависимостей в приложении — service locator без DI-фреймворка. */
class AppContainer(context: Context) {

    /** Живёт весь процесс: для записей, которые не должны отменяться вместе с сервисом или экраном. */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: AppDatabase = AppDatabase.create(context)

    val settingsRepository = SettingsRepository(context.appDataStore)

    val identityRepository = IdentityRepository(context.appDataStore)

    val encounterRepository = EncounterRepository(database)

    val updateRepository = UpdateRepository(
        context = context.applicationContext,
        repo = BuildConfig.GITHUB_REPO,
        currentVersion = BuildConfig.VERSION_NAME,
        scope = applicationScope,
    )

    /** Состояние сервиса обнаружения; UI читает его напрямую, без bind к сервису. */
    val discoveryState = MutableStateFlow(DiscoveryState())
}
