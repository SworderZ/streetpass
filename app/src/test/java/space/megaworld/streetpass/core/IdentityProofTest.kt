package space.megaworld.streetpass.core

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentityProofTest {

    private val keys = generate()
    private val otherKeys = generate()
    private val peerId = IdentityProof.peerId(keys.public)
    private val now = 1_700_000_000L

    private fun generate(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec(IdentityProof.CURVE_NAME)) }.generateKeyPair()

    private fun proof(timestamp: Long = now) = IdentityProof.sign(keys.private, keys.public, timestamp)

    @Test
    fun proofHasFixedLayout() {
        val proof = proof()

        assertEquals(IdentityProof.PROOF_BYTES, proof.size)
        assertEquals(102, proof.size)
        assertEquals(IdentityProof.VERSION, proof[0])
        assertArrayEquals(IdentityProof.compress(keys.public), proof.copyOfRange(1, 34))
    }

    @Test
    fun peerIdIsEightBytesOfKeyHash() {
        assertEquals(BleConstants.PEER_ID_BYTES, peerId.size)
        assertArrayEquals(peerId, IdentityProof.peerId(IdentityProof.compress(keys.public)))
        assertFalse(peerId.contentEquals(IdentityProof.peerId(otherKeys.public)))
    }

    @Test
    fun verifiesFreshProof() {
        val result = IdentityProof.verify(proof(), peerId, nowSeconds = now + 30)

        assertEquals(IdentityProof.Result.Verified(now), result)
    }

    @Test
    fun rejectsProofForAnotherId() {
        val result = IdentityProof.verify(proof(), IdentityProof.peerId(otherKeys.public), nowSeconds = now)

        assertEquals(IdentityProof.Result.Rejected(IdentityProof.Reason.ID_MISMATCH), result)
    }

    @Test
    fun rejectsStaleAndFutureProofs() {
        val skew = BleConstants.PROOF_MAX_SKEW_MS / 1000

        assertEquals(IdentityProof.Result.Verified(now), IdentityProof.verify(proof(), peerId, now + skew))
        assertEquals(
            IdentityProof.Result.Rejected(IdentityProof.Reason.STALE),
            IdentityProof.verify(proof(), peerId, now + skew + 1),
        )
        assertEquals(
            IdentityProof.Result.Rejected(IdentityProof.Reason.STALE),
            IdentityProof.verify(proof(), peerId, now - skew - 1),
        )
    }

    @Test
    fun rejectsTamperedTimestamp() {
        val proof = proof()
        // Сдвигаем время на секунду — ID тот же, свежесть в норме, подпись уже не сходится.
        proof[1 + IdentityProof.PUBLIC_KEY_BYTES + IdentityProof.TIMESTAMP_BYTES - 1] =
            (proof[1 + IdentityProof.PUBLIC_KEY_BYTES + IdentityProof.TIMESTAMP_BYTES - 1] + 1).toByte()

        val result = IdentityProof.verify(proof, peerId, nowSeconds = now)

        assertEquals(IdentityProof.Result.Rejected(IdentityProof.Reason.BAD_SIGNATURE), result)
    }

    @Test
    fun rejectsTamperedSignature() {
        val proof = proof()
        proof[proof.size - 1] = (proof[proof.size - 1].toInt() xor 0x01).toByte()

        assertEquals(
            IdentityProof.Result.Rejected(IdentityProof.Reason.BAD_SIGNATURE),
            IdentityProof.verify(proof, peerId, nowSeconds = now),
        )
    }

    @Test
    fun rejectsSignatureByAnotherKeyOverSameId() {
        // Атакующий знает ID и публичный ключ жертвы, но подписывает своим ключом.
        val forged = IdentityProof.sign(otherKeys.private, keys.public, now)

        assertEquals(
            IdentityProof.Result.Rejected(IdentityProof.Reason.BAD_SIGNATURE),
            IdentityProof.verify(forged, peerId, nowSeconds = now),
        )
    }

    @Test
    fun rejectsMalformedProof() {
        assertEquals(
            IdentityProof.Result.Rejected(IdentityProof.Reason.MALFORMED),
            IdentityProof.verify(proof().copyOf(IdentityProof.PROOF_BYTES - 1), peerId, now),
        )
        val wrongVersion = proof().also { it[0] = 2 }
        assertEquals(
            IdentityProof.Result.Rejected(IdentityProof.Reason.MALFORMED),
            IdentityProof.verify(wrongVersion, peerId, now),
        )
    }

    @Test
    fun rejectsKeyThatIsNotOnCurve() {
        // Подбираем X, для которого x^3 + ax + b не квадратичный вычет: точки с таким X нет.
        var key: ByteArray? = null
        var candidate = ByteArray(IdentityProof.PUBLIC_KEY_BYTES).also { it[0] = 0x02 }
        for (i in 1..200) {
            candidate = candidate.copyOf().also { it[32] = i.toByte() }
            if (IdentityProof.decompress(candidate) == null) {
                key = candidate
                break
            }
        }
        assertNotNull("среди первых 200 X должен найтись невычет", key)

        val proof = proof()
        key!!.copyInto(proof, 1)
        assertEquals(
            IdentityProof.Result.Rejected(IdentityProof.Reason.BAD_KEY),
            IdentityProof.verify(proof, IdentityProof.peerId(key), now),
        )
    }

    @Test
    fun decompressRejectsBadPrefixAndLength() {
        val compressed = IdentityProof.compress(keys.public)

        assertNull(IdentityProof.decompress(compressed.copyOf(32)))
        assertNull(IdentityProof.decompress(compressed.copyOf().also { it[0] = 0x04 }))
        assertNull(IdentityProof.decompress(ByteArray(33) { 0xFF.toByte() }.also { it[0] = 0x02 }))
    }

    @Test
    fun compressionRoundTrips() {
        repeat(20) {
            val pair = generate()
            val original = pair.public as ECPublicKey
            val restored = IdentityProof.decompress(IdentityProof.compress(original)) as ECPublicKey

            assertEquals(original.w.affineX, restored.w.affineX)
            assertEquals(original.w.affineY, restored.w.affineY)
        }
    }

    @Test
    fun signatureEncodingRoundTrips() {
        val random = Random(42)
        repeat(50) {
            val raw = random.nextBytes(IdentityProof.SIGNATURE_BYTES)
            // Старшие биты выставляем нарочно: DER требует ведущий нулевой байт для таких чисел.
            raw[0] = 0xFF.toByte()
            raw[32] = 0x80.toByte()

            assertArrayEquals(raw, IdentityProof.derToRaw(IdentityProof.rawToDer(raw)))
        }
        val zeros = ByteArray(IdentityProof.SIGNATURE_BYTES)
        assertArrayEquals(zeros, IdentityProof.derToRaw(IdentityProof.rawToDer(zeros)))
    }

    @Test
    fun chunksCoverProofExactly() {
        val proof = proof()
        val chunks = IdentityProof.chunks(proof, generation = 7)

        assertEquals(4, chunks.size)
        assertTrue(chunks.all { it.size <= BleConstants.PROOF_FRAME_BYTES })
        chunks.forEachIndexed { index, chunk ->
            assertEquals(7, IdentityProof.generationOf(chunk[0]))
            assertEquals(index, IdentityProof.indexOf(chunk[0]))
        }
        val joined = chunks.fold(ByteArray(0)) { acc, chunk -> acc + chunk.copyOfRange(1, chunk.size) }
        assertArrayEquals(proof, joined)
    }

    @Test
    fun assemblerCollectsChunksInAnyOrderWithDuplicates() {
        val proof = proof()
        val chunks = IdentityProof.chunks(proof, generation = 3)
        val assembler = ProofAssembler()

        assertNull(assembler.accept(chunks[2]))
        assertNull(assembler.accept(chunks[2]))
        assertNull(assembler.accept(chunks[0]))
        assertNull(assembler.accept(chunks[3]))
        assertArrayEquals(proof, assembler.accept(chunks[1]))
        // Собранное отдаётся один раз: повторные куски того же поколения игнорируются.
        assertNull(assembler.accept(chunks[1]))
        assertNull(assembler.accept(chunks[0]))
    }

    @Test
    fun assemblerRestartsOnNewGeneration() {
        val old = IdentityProof.chunks(proof(now), generation = 3)
        val fresh = IdentityProof.chunks(proof(now + 300), generation = 4)
        val assembler = ProofAssembler()

        assertNull(assembler.accept(old[0]))
        assertNull(assembler.accept(old[1]))
        assertNull(assembler.accept(fresh[0]))
        assertNull(assembler.accept(fresh[1]))
        assertNull(assembler.accept(fresh[2]))
        val expected = fresh.fold(ByteArray(0)) { acc, chunk -> acc + chunk.copyOfRange(1, chunk.size) }
        assertArrayEquals(expected, assembler.accept(fresh[3]))
    }

    @Test
    fun assemblerRejectsMalformedFrames() {
        val assembler = ProofAssembler()
        val chunks = IdentityProof.chunks(proof(), generation = 1)

        assertNull(assembler.accept(byteArrayOf()))
        assertNull(assembler.accept(byteArrayOf(IdentityProof.header(1, 0))))
        assertNull(assembler.accept(chunks[0].copyOf(chunks[0].size - 1)))
        assertNull(assembler.accept(chunks[3] + byteArrayOf(0)))
        // Индекс 4..7 не существует при четырёх кусках.
        assertNull(assembler.accept(byteArrayOf((1 shl 3 or 5).toByte()) + ByteArray(26)))
    }

    @Test
    fun verifierTrustsPeerOnlyWhileProofIsFresh() {
        val peerHex = Hex.encode(peerId)
        val chunks = IdentityProof.chunks(proof(now), generation = 9)
        val verifier = PeerVerifier()
        val nowMs = now * 1000

        assertFalse(verifier.isTrusted(peerHex, nowMs))
        chunks.dropLast(1).forEach { assertNull(verifier.onFrame(peerHex, it, nowMs)) }
        assertFalse(verifier.isTrusted(peerHex, nowMs))
        assertEquals(IdentityProof.Result.Verified(now), verifier.onFrame(peerHex, chunks.last(), nowMs))
        assertTrue(verifier.isTrusted(peerHex, nowMs))
        assertTrue(verifier.isTrusted(peerHex, nowMs + BleConstants.PROOF_MAX_SKEW_MS))
        assertFalse(verifier.isTrusted(peerHex, nowMs + BleConstants.PROOF_MAX_SKEW_MS + 1))
    }

    @Test
    fun verifierDoesNotTrustCopiedIdWithForeignProof() {
        val victim = Hex.encode(peerId)
        val forged = IdentityProof.chunks(IdentityProof.sign(otherKeys.private, otherKeys.public, now), generation = 2)
        val verifier = PeerVerifier()

        val results = forged.map { verifier.onFrame(victim, it, now * 1000) }

        assertEquals(IdentityProof.Result.Rejected(IdentityProof.Reason.ID_MISMATCH), results.last())
        assertFalse(verifier.isTrusted(victim, now * 1000))
    }

    @Test
    fun verifierRecoversFromMixedGenerationsAfterRejection() {
        // Сосед перезапустил рекламу с тем же поколением: два старых куска плюс два новых —
        // подпись не сойдётся, но после сброса честная пересборка должна пройти.
        val peerHex = Hex.encode(peerId)
        val old = IdentityProof.chunks(proof(now - 100), generation = 5)
        val fresh = IdentityProof.chunks(proof(now), generation = 5)
        val verifier = PeerVerifier()
        val nowMs = now * 1000

        assertNull(verifier.onFrame(peerHex, old[0], nowMs))
        assertNull(verifier.onFrame(peerHex, old[1], nowMs))
        assertNull(verifier.onFrame(peerHex, fresh[2], nowMs))
        assertEquals(
            IdentityProof.Result.Rejected(IdentityProof.Reason.BAD_SIGNATURE),
            verifier.onFrame(peerHex, fresh[3], nowMs),
        )
        assertFalse(verifier.isTrusted(peerHex, nowMs))

        fresh.dropLast(1).forEach { assertNull(verifier.onFrame(peerHex, it, nowMs)) }
        assertEquals(IdentityProof.Result.Verified(now), verifier.onFrame(peerHex, fresh[3], nowMs))
        assertTrue(verifier.isTrusted(peerHex, nowMs))
    }
}
