package space.megaworld.streetpass

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import space.megaworld.streetpass.core.FriendInvite
import space.megaworld.streetpass.ui.AppRoot
import space.megaworld.streetpass.ui.theme.StreetPassTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            StreetPassTheme {
                AppRoot()
            }
        }
        // Пересоздание активити (поворот) не должно повторно поднимать приглашение.
        if (savedInstanceState == null) handleInvite(intent)
    }

    override fun onNewIntent(intent: Intent) {
        // singleTask: ссылка или «Поделиться» при живом приложении приходят сюда.
        super.onNewIntent(intent)
        setIntent(intent)
        handleInvite(intent)
    }

    private fun handleInvite(intent: Intent?) {
        if (intent == null) return
        val text = when (intent.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            else -> null
        } ?: return
        val container = (application as StreetPassApp).container
        // Проверка подписи — ECDSA, ей не место в main-потоке.
        container.applicationScope.launch(Dispatchers.Default) {
            val invite = FriendInvite.parse(text)
            container.pendingInvite.value = when {
                invite != null -> PendingInvite.Valid(invite)
                // Открыли просто страницу проекта, а не приглашение — молча показываем приложение.
                intent.action == Intent.ACTION_VIEW && !text.contains(FriendInvite.PARAMETER) -> null
                else -> PendingInvite.Invalid
            }
        }
    }
}
