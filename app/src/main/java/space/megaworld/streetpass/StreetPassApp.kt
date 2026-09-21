package space.megaworld.streetpass

import android.app.Application
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import space.megaworld.streetpass.ble.DiscoveryState
import space.megaworld.streetpass.data.EncounterRepository
import space.megaworld.streetpass.core.FriendInvite
import space.megaworld.streetpass.data.achievements.AchievementRepository
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
        // ID — производная от ключа подписи. Сверяем запись в DataStore с ключом сразу,
        // чтобы UI не показывал устаревший ID до первого запуска сервиса (например,
        // после обновления со сборки, где ID был просто случайными байтами).
        container.applicationScope.launch { container.identityRepository.getOrCreate() }
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

    val achievementRepository = AchievementRepository(database)

    val updateRepository = UpdateRepository(
        context = context.applicationContext,
        repo = BuildConfig.GITHUB_REPO,
        currentVersion = BuildConfig.VERSION_NAME,
        scope = applicationScope,
    )

    /** Состояние сервиса обнаружения; UI читает его напрямую, без bind к сервису. */
    val discoveryState = MutableStateFlow(DiscoveryState())

    /** Приглашение, пришедшее через ссылку или «Поделиться»; UI показывает подтверждение и сбрасывает. */
    val pendingInvite = MutableStateFlow<PendingInvite?>(null)

    /** Страница проекта — сюда ведут ссылки-приглашения, здесь же лежат релизы. */
    val projectUrl: String = "https://github.com/${BuildConfig.GITHUB_REPO}"
}

sealed interface PendingInvite {
    data class Valid(val invite: FriendInvite.Invite) : PendingInvite

    /** Текст пришёл, но приглашения в нём нет или подпись не сходится. */
    data object Invalid : PendingInvite
}
