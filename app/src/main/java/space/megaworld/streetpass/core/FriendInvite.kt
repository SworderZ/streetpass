package space.megaworld.streetpass.core

import java.security.GeneralSecurityException
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.util.Base64

/**
 * Приглашение в друзья: подписанный публичный ключ плюс ник. Из ключа получатель
 * выводит ID, подпись доказывает, что приглашение сделал владелец ключа — подсунуть
 * чужой ID нельзя. Времени в подписи нет: приглашение можно хранить и пересылать.
 *
 * ```
 * version(1) | публичный ключ, сжатый(33) | длина ника(1) | ник UTF-8(0..24) | ECDSA r||s (64)
 * ```
 * Подписывается всё до подписи с доменом [DOMAIN]. Наружу уходит base64url без «=»
 * в параметре `invite=`. Основная форма — собственная схема `streetpass://friend?invite=…`:
 * она открывается только в приложении, без выбора браузера. Ссылка на страницу проекта
 * с тем же параметром тоже принимается — на случай, если приложения ещё нет.
 */
object FriendInvite {

    const val VERSION: Byte = 1
    const val PARAMETER = "invite"

    /** Схема и хост зарегистрированы в манифесте; менять только вместе с ним. */
    const val SCHEME = "streetpass"
    const val HOST = "friend"

    private const val DOMAIN = "StreetPass-invite-v1"
    private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
    private const val MIN_BYTES = 1 + IdentityProof.PUBLIC_KEY_BYTES + 1 + IdentityProof.SIGNATURE_BYTES

    private val parameterPattern = Regex("""(?:[#?&])$PARAMETER=([A-Za-z0-9_-]+)""")

    class Invite(val peerId: String, val nickname: String?, val publicKey: ByteArray)

    @Throws(GeneralSecurityException::class)
    fun create(privateKey: PrivateKey, publicKey: PublicKey, nickname: String): String {
        val key = IdentityProof.compress(publicKey)
        val nick = Nicknames.encode(Nicknames.sanitize(nickname))
        val body = byteArrayOf(VERSION) + key + nick.size.toByte() + nick
        val signer = Signature.getInstance(SIGNATURE_ALGORITHM)
        signer.initSign(privateKey)
        signer.update(message(body))
        val signature = IdentityProof.derToRaw(signer.sign())
            ?: throw GeneralSecurityException("Провайдер вернул подпись неожиданного формата")
        return Base64.getUrlEncoder().withoutPadding().encodeToString(body + signature)
    }

    /** Ссылка, которую открывает только приложение — для QR и «Поделиться». */
    fun appLink(payload: String): String = "$SCHEME://$HOST?$PARAMETER=$payload"

    /** Ссылка на страницу проекта с тем же приглашением — для тех, у кого приложения нет. */
    fun link(pageUrl: String, payload: String): String = "$pageUrl#$PARAMETER=$payload"

    /**
     * Ищет приглашение в любом тексте — ссылке из QR, сообщении из мессенджера, буфере
     * обмена. null — приглашения нет, оно повреждено или подпись не сходится.
     */
    fun parse(text: String): Invite? {
        val payload = parameterPattern.find(text)?.groupValues?.get(1) ?: return null
        val bytes = try {
            Base64.getUrlDecoder().decode(payload)
        } catch (e: IllegalArgumentException) {
            return null
        }
        if (bytes.size < MIN_BYTES || bytes[0] != VERSION) return null

        val key = bytes.copyOfRange(1, 1 + IdentityProof.PUBLIC_KEY_BYTES)
        val nickLength = bytes[1 + IdentityProof.PUBLIC_KEY_BYTES].toInt() and 0xFF
        val bodyLength = 1 + IdentityProof.PUBLIC_KEY_BYTES + 1 + nickLength
        if (nickLength > BleConstants.NICKNAME_MAX_BYTES || bytes.size != bodyLength + IdentityProof.SIGNATURE_BYTES) return null
        val body = bytes.copyOfRange(0, bodyLength)
        val signature = bytes.copyOfRange(bodyLength, bytes.size)
        val nickname = if (nickLength == 0) null else Nicknames.decode(bytes.copyOfRange(bodyLength - nickLength, bodyLength))

        val publicKey = IdentityProof.decompress(key) ?: return null
        val valid = try {
            val verifier = Signature.getInstance(SIGNATURE_ALGORITHM)
            verifier.initVerify(publicKey)
            verifier.update(message(body))
            verifier.verify(IdentityProof.rawToDer(signature))
        } catch (e: GeneralSecurityException) {
            false
        }
        if (!valid) return null
        return Invite(peerId = Hex.encode(IdentityProof.peerId(key)), nickname = nickname, publicKey = key)
    }

    private fun message(body: ByteArray): ByteArray = DOMAIN.toByteArray(Charsets.US_ASCII) + body
}
