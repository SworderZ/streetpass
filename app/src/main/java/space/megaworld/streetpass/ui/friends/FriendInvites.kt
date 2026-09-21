package space.megaworld.streetpass.ui.friends

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.WriterException
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import space.megaworld.streetpass.AppContainer
import space.megaworld.streetpass.MainActivity
import space.megaworld.streetpass.PendingInvite
import space.megaworld.streetpass.R
import space.megaworld.streetpass.core.FriendInvite
import space.megaworld.streetpass.core.Hex
import space.megaworld.streetpass.ui.AppViewModelProvider
import space.megaworld.streetpass.ui.components.displayName

/**
 * Приглашения в друзья: свой QR/ссылка, сканер и приём чужого приглашения. Приглашение,
 * пришедшее извне (ссылка, «Поделиться»), лежит в [AppContainer.pendingInvite] — диалог
 * подтверждения показывается из [AppRoot] поверх любой вкладки.
 */
class FriendInviteViewModel(private val container: AppContainer) : ViewModel() {

    /** Ссылка переподписывается при смене ника — он входит в приглашение. */
    val inviteLink: StateFlow<String?> = container.identityRepository.nickname
        .map { container.identityRepository.invitePayload()?.let(FriendInvite::appLink) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Куда скачать приложение — добавляется к тексту «Поделиться». */
    val releasesUrl: String = "${container.projectUrl}/releases"

    val qrCode: StateFlow<ImageBitmap?> = inviteLink
        .map { link -> link?.let { withContext(Dispatchers.Default) { renderQr(it) } } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val pending: StateFlow<PendingInvite?> = container.pendingInvite

    val ownId: StateFlow<String> = container.identityRepository.idHex
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    /** Текст из сканера или вставленный вручную. */
    fun submit(text: String) {
        viewModelScope.launch(Dispatchers.Default) {
            container.pendingInvite.value = FriendInvite.parse(text)?.let { PendingInvite.Valid(it) } ?: PendingInvite.Invalid
        }
    }

    fun confirm(invite: FriendInvite.Invite) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            container.encounterRepository.addFriend(invite, now)
            container.achievementRepository.check(now)
            container.pendingInvite.value = null
        }
    }

    fun dismissPending() {
        container.pendingInvite.value = null
    }

    private fun renderQr(text: String): ImageBitmap? {
        val matrix = try {
            QRCodeWriter().encode(
                text,
                BarcodeFormat.QR_CODE,
                QR_SIZE,
                QR_SIZE,
                mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M),
            )
        } catch (e: WriterException) {
            return null
        }
        val width = matrix.width
        val height = matrix.height
        val pixels = IntArray(width * height) { i -> if (matrix.get(i % width, i / width)) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()
    }

    private companion object {
        const val QR_SIZE = 512
    }
}

/** Свой QR-код и кнопка «Поделиться ссылкой». */
@Composable
fun MyInviteDialog(
    onDismiss: () -> Unit,
    viewModel: FriendInviteViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val link by viewModel.inviteLink.collectAsStateWithLifecycle()
    val qr by viewModel.qrCode.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val releasesUrl = viewModel.releasesUrl

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.invite_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(12.dp))
                        // QR всегда чёрный на белом, иначе в тёмной теме камера его не читает.
                        .background(androidx.compose.ui.graphics.Color.White),
                    contentAlignment = Alignment.Center,
                ) {
                    val image = qr
                    if (image != null) {
                        Image(
                            bitmap = image,
                            contentDescription = stringResource(R.string.invite_qr_description),
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(8.dp),
                        )
                    } else {
                        CircularProgressIndicator()
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.invite_text),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = link != null,
                onClick = { link?.let { shareLink(context, it, releasesUrl) } },
            ) { Text(stringResource(R.string.invite_share)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        },
    )
}

private fun shareLink(context: android.content.Context, link: String, releasesUrl: String) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, context.getString(R.string.invite_share_text, link, releasesUrl))
    val chooser = Intent.createChooser(send, null)
        // Само приложение принимает ссылки через «Поделиться» — себя из списка убираем.
        .putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(context, MainActivity::class.java)))
    context.startActivity(chooser)
}

/** Полноэкранный сканер QR с полем для вставки ссылки — на случай, если камеры нет или она запрещена. */
@Composable
fun AddFriendDialog(
    onDismiss: () -> Unit,
    viewModel: FriendInviteViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val context = LocalContext.current
    var cameraGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED,
        )
    }
    var asked by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        cameraGranted = granted
        asked = true
    }
    LaunchedEffect(Unit) {
        if (!cameraGranted) launcher.launch(Manifest.permission.CAMERA)
    }
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    var draft by remember { mutableStateOf("") }
    // Пока открыт диалог подтверждения, сканер продолжает видеть тот же код — не дёргаем повторно.
    val scannerIdle = pending == null

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 4.dp, top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.add_friend_title), style = MaterialTheme.typography.titleLarge)
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.close))
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(16.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    if (cameraGranted) {
                        QrScannerView(
                            onText = { text -> if (scannerIdle) viewModel.submit(text) },
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = stringResource(R.string.add_friend_camera_denied),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (asked) {
                                Spacer(modifier = Modifier.height(12.dp))
                                OutlinedButton(onClick = { launcher.launch(Manifest.permission.CAMERA) }) {
                                    Text(stringResource(R.string.add_friend_allow_camera))
                                }
                            }
                        }
                    }
                }
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        text = stringResource(if (cameraGranted) R.string.add_friend_scan_hint else R.string.add_friend_paste_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        label = { Text(stringResource(R.string.add_friend_link_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { viewModel.submit(draft) },
                        enabled = draft.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.add_friend_submit)) }
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}

/** Подтверждение приглашения — из сканера, вставки, ссылки или «Поделиться». */
@Composable
fun InviteConfirmDialog(
    viewModel: FriendInviteViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    val ownId by viewModel.ownId.collectAsStateWithLifecycle()

    when (val current = pending) {
        null -> Unit
        PendingInvite.Invalid -> AlertDialog(
            onDismissRequest = viewModel::dismissPending,
            title = { Text(stringResource(R.string.add_friend_title)) },
            text = { Text(stringResource(R.string.add_friend_link_invalid)) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissPending) { Text(stringResource(R.string.close)) }
            },
        )
        is PendingInvite.Valid -> {
            val invite = current.invite
            val self = invite.peerId == ownId
            AlertDialog(
                onDismissRequest = viewModel::dismissPending,
                title = { Text(stringResource(if (self) R.string.add_friend_title else R.string.confirm_invite_title)) },
                text = {
                    if (self) {
                        Text(stringResource(R.string.confirm_invite_self))
                    } else {
                        Column {
                            Text(displayName(null, invite.nickname, invite.peerId), style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = Hex.grouped(invite.peerId),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(stringResource(R.string.confirm_invite_text))
                        }
                    }
                },
                confirmButton = {
                    if (self) {
                        TextButton(onClick = viewModel::dismissPending) { Text(stringResource(R.string.close)) }
                    } else {
                        TextButton(onClick = { viewModel.confirm(invite) }) { Text(stringResource(R.string.confirm_invite_add)) }
                    }
                },
                dismissButton = if (self) {
                    null
                } else {
                    { TextButton(onClick = viewModel::dismissPending) { Text(stringResource(R.string.cancel)) } }
                },
            )
        }
    }
}
