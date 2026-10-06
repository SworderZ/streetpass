package space.megaworld.streetpass.core

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopAdvertisementTest {

    private val keys = generate()
    private val otherKeys = generate()
    private val peerId = IdentityProof.peerId(keys.public)
    private val now = 1_700_000_000L

    private fun generate(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec(IdentityProof.CURVE_NAME))
        }.generateKeyPair()

    private fun proof(keyPair: KeyPair = keys, timestamp: Long = now): ByteArray =
        IdentityProof.sign(keyPair.private, keyPair.public, timestamp)

    @Test
    fun nicknameUsesSanitisedUtf8AndNeverSplitsUnicode() {
        val sixCyrillic = "Привет" // 12 UTF-8 bytes: exactly the desktop limit.
        val payload = DesktopAdvertisement.encodeNickname(peerId, sixCyrillic)

        val packet = DesktopAdvertisement.parse(payload) as DesktopAdvertisement.Packet.Nickname
        assertEquals(sixCyrillic, packet.value)
        assertEquals(12, payload.size - DesktopAdvertisement.COMMON_BYTES)

        val truncated = DesktopAdvertisement.encodeNickname(peerId, "Приветм")
        val truncatedPacket = DesktopAdvertisement.parse(truncated) as DesktopAdvertisement.Packet.Nickname
        assertEquals(sixCyrillic, truncatedPacket.value)
        assertTrue(truncatedPacket.value.toByteArray(Charsets.UTF_8).size <= 12)
        assertFalse(truncatedPacket.value.contains('\uFFFD'))
    }

    @Test
    fun parserRejectsMalformedKindsLengthsAndNicknameBytes() {
        val good = DesktopAdvertisement.encodeNickname(peerId, "Alice")

        assertNotNull(DesktopAdvertisement.parse(good.copyOf(good.size - 1)))
        assertNull(DesktopAdvertisement.parse(good.copyOf().also { it[0] = 0x54 }))
        assertNull(DesktopAdvertisement.parse(good.copyOf().also { it[3] = 2 }))
        assertNull(DesktopAdvertisement.parse(good.copyOfRange(0, DesktopAdvertisement.COMMON_BYTES)))

        val malformedUtf8 = good.copyOfRange(0, DesktopAdvertisement.COMMON_BYTES) +
            byteArrayOf(0xC3.toByte(), 0x28)
        assertNull(DesktopAdvertisement.parse(malformedUtf8))

        val unsanitised = DesktopAdvertisement.encodeNickname(peerId, "A  B")
        assertEquals("A B", (DesktopAdvertisement.parse(unsanitised) as DesktopAdvertisement.Packet.Nickname).value)
        val rawUnsanitised = unsanitised.copyOfRange(0, DesktopAdvertisement.COMMON_BYTES) + "A  B".toByteArray()
        assertNull(DesktopAdvertisement.parse(rawUnsanitised))
    }

    @Test
    fun proofAssemblyAcceptsOutOfOrderDesktopChunksAndConvertsToStandardFrames() {
        val signed = proof()
        val chunks = (0 until DesktopAdvertisement.PROOF_CHUNK_COUNT).map { index ->
            DesktopAdvertisement.encodeProofChunk(peerId, signed, generation = 7, index = index)
        }
        val assembler = DesktopProofAssembler()
        val order = listOf(9, 2, 0, 7, 1, 8, 3, 6, 4, 5)
        var frames: List<ByteArray>? = null
        order.forEachIndexed { offset, index ->
            frames = assembler.accept(
                DesktopAdvertisement.parse(chunks[index]) as DesktopAdvertisement.Packet.ProofChunk,
                now + offset,
            ) ?: frames
        }

        assertNotNull(frames)
        assertEquals(4, frames!!.size)
        val reconstructed = frames!!.fold(ByteArray(0)) { all, frame -> all + frame.copyOfRange(1, frame.size) }
        assertArrayEquals(signed, reconstructed)
        frames!!.forEachIndexed { index, frame ->
            assertEquals(7, IdentityProof.generationOf(frame[0]))
            assertEquals(index, IdentityProof.indexOf(frame[0]))
        }
    }

    @Test
    fun generationChangeDiscardsPartialProofAndForgedProofStillFailsSignature() {
        val assembler = DesktopProofAssembler()
        val old = proof(timestamp = now - 60)
        val fresh = proof(timestamp = now)
        val oldPacket = DesktopAdvertisement.parse(
            DesktopAdvertisement.encodeProofChunk(peerId, old, generation = 3, index = 0),
        ) as DesktopAdvertisement.Packet.ProofChunk
        assembler.accept(oldPacket, now)

        val forged = proof(otherKeys)
        val forgedChunks = (0 until DesktopAdvertisement.PROOF_CHUNK_COUNT).map { index ->
            DesktopAdvertisement.parse(
                DesktopAdvertisement.encodeProofChunk(peerId, forged, generation = 4, index = index),
            ) as DesktopAdvertisement.Packet.ProofChunk
        }
        val validChunks = (0 until DesktopAdvertisement.PROOF_CHUNK_COUNT).map { index ->
            DesktopAdvertisement.parse(
                DesktopAdvertisement.encodeProofChunk(peerId, fresh, generation = 5, index = index),
            ) as DesktopAdvertisement.Packet.ProofChunk
        }

        // A complete proof signed by another key assembles, but IdentityProof rejects it.
        val forgedFrames = forgedChunks.mapNotNull { assembler.accept(it, now + 1) }.last()
        val forgedProof = forgedFrames.fold(ByteArray(0)) { all, frame -> all + frame.copyOfRange(1, frame.size) }
        assertEquals(
            IdentityProof.Result.Rejected(IdentityProof.Reason.ID_MISMATCH),
            IdentityProof.verify(forgedProof, peerId, now),
        )

        // The partial generation 3 data cannot contaminate generation 5.
        val validFrames = validChunks.mapNotNull { assembler.accept(it, now + 2) }.last()
        val validProof = validFrames.fold(ByteArray(0)) { all, frame -> all + frame.copyOfRange(1, frame.size) }
        assertEquals(IdentityProof.Result.Verified(now), IdentityProof.verify(validProof, peerId, now))
    }

    @Test
    fun assemblyIsBoundedAndPrunesStalePeers() {
        val assembler = DesktopProofAssembler()
        val sampleProof = proof()
        repeat(DesktopAdvertisement.MAX_ASSEMBLIES + 20) { value ->
            val id = ByteArray(BleConstants.PEER_ID_BYTES) { index -> (value + index).toByte() }
            val packet = DesktopAdvertisement.parse(
                DesktopAdvertisement.encodeProofChunk(id, sampleProof, generation = 1, index = 0),
            ) as DesktopAdvertisement.Packet.ProofChunk
            assembler.accept(packet, value.toLong())
        }
        assertEquals(256, assembler.size) // Byte pattern in this fixture yields 256 unique IDs

        val freshId = ByteArray(BleConstants.PEER_ID_BYTES) { 0x7F.toByte() }
        val fresh = DesktopAdvertisement.parse(
            DesktopAdvertisement.encodeProofChunk(freshId, sampleProof, generation = 1, index = 0),
        ) as DesktopAdvertisement.Packet.ProofChunk
        assembler.accept(fresh, DesktopAdvertisement.STALE_ASSEMBLY_MS + 1)
        assertTrue(assembler.size <= DesktopAdvertisement.MAX_ASSEMBLIES)
    }
}
