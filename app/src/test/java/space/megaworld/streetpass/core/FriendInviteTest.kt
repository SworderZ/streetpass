package space.megaworld.streetpass.core

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FriendInviteTest {

    private val keys = generate()
    private val otherKeys = generate()
    private val page = "https://github.com/SworderZ/streetpass"

    private fun generate(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec(IdentityProof.CURVE_NAME)) }.generateKeyPair()

    private fun link(nickname: String = "Alice") =
        FriendInvite.link(page, FriendInvite.create(keys.private, keys.public, nickname))

    @Test
    fun roundTripsThroughLink() {
        val invite = FriendInvite.parse(link())

        assertNotNull(invite)
        assertEquals(Hex.encode(IdentityProof.peerId(keys.public)), invite!!.peerId)
        assertEquals("Alice", invite.nickname)
        assertArrayEquals(IdentityProof.compress(keys.public), invite.publicKey)
    }

    @Test
    fun appLinkUsesOwnSchemeAndParses() {
        val payload = FriendInvite.create(keys.private, keys.public, "Alice")
        val link = FriendInvite.appLink(payload)

        assertTrue(link.startsWith("streetpass://friend?invite="))
        assertEquals(Hex.encode(IdentityProof.peerId(keys.public)), FriendInvite.parse(link)!!.peerId)
        // Тот же payload в ссылке на страницу проекта даёт то же приглашение.
        assertEquals(FriendInvite.parse(link)!!.peerId, FriendInvite.parse(FriendInvite.link(page, payload))!!.peerId)
    }

    @Test
    fun linkIsUrlSafeAndPointsAtProjectPage() {
        val link = link("Ann Ж!")

        assertTrue(link.startsWith("$page#invite="))
        assertTrue(link.substringAfter("#invite=").matches(Regex("[A-Za-z0-9_-]+")))
    }

    @Test
    fun parsesInviteEmbeddedInSharedMessage() {
        val text = "Add me as a friend in StreetPass: ${link()} — see you!"

        assertNotNull(FriendInvite.parse(text))
    }

    @Test
    fun emptyNicknameBecomesNull() {
        assertNull(FriendInvite.parse(link(""))!!.nickname)
        assertNull(FriendInvite.parse(link("   "))!!.nickname)
    }

    @Test
    fun rejectsTamperedNickname() {
        val payload = FriendInvite.create(keys.private, keys.public, "Alice")
        val bytes = java.util.Base64.getUrlDecoder().decode(payload)
        // Байт ника «A» → «B»: подпись перестаёт сходиться.
        bytes[1 + IdentityProof.PUBLIC_KEY_BYTES + 1] = 'B'.code.toByte()
        val forged = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

        assertNull(FriendInvite.parse(FriendInvite.link(page, forged)))
    }

    @Test
    fun rejectsInviteSignedByAnotherKey() {
        // Чужой публичный ключ (значит, чужой ID) подписан своим ключом.
        val forged = FriendInvite.create(otherKeys.private, keys.public, "Alice")

        assertNull(FriendInvite.parse(FriendInvite.link(page, forged)))
    }

    @Test
    fun rejectsGarbage() {
        assertNull(FriendInvite.parse("hello"))
        assertNull(FriendInvite.parse("$page#invite="))
        assertNull(FriendInvite.parse("$page#invite=AAAA"))
        assertNull(FriendInvite.parse("$page#invite=***"))
        assertNull(FriendInvite.parse(link().dropLast(3)))
    }

    @Test
    fun proofAndInviteSignaturesAreNotInterchangeable() {
        // Домены разные: подпись доказательства ID нельзя переупаковать в приглашение.
        val proof = IdentityProof.sign(keys.private, keys.public, 1_700_000_000L)
        val key = proof.copyOfRange(1, 1 + IdentityProof.PUBLIC_KEY_BYTES)
        val signature = proof.copyOfRange(IdentityProof.PROOF_BYTES - IdentityProof.SIGNATURE_BYTES, IdentityProof.PROOF_BYTES)
        val fake = byteArrayOf(FriendInvite.VERSION) + key + 0.toByte() + signature
        val payload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(fake)

        assertNull(FriendInvite.parse(FriendInvite.link(page, payload)))
    }
}
