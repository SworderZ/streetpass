package space.megaworld.streetpass.core

import java.math.BigInteger
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECFieldFp
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.EllipticCurve

/**
 * Асимметричная подпись идентификатора.
 *
 * ID = первые 8 байт SHA-256 от сжатого публичного ключа P-256. Скопировать чужой ID
 * можно, но подтвердить его без приватного ключа владельца нельзя: подпись не сойдётся,
 * а подобрать другой ключ с тем же префиксом хеша — 2^64 операций.
 *
 * Доказательство (proof), которое уходит в эфир:
 * ```
 * version(1) | публичный ключ, сжатый(33) | unix-время подписи, секунды, BE(4) | ECDSA r||s (64)
 * ```
 * Подписывается `DOMAIN || version || ключ || время`. В legacy-рекламу 102 байта не
 * влезают, поэтому доказательство режется на куски по [CHUNK_DATA_BYTES] и передаётся
 * по очереди в scan-response; приёмник собирает их через [ProofAssembler].
 *
 * Время в подписи ограничивает повтор записанного эфира: чужое доказательство можно
 * воспроизвести только пока оно свежее ([BleConstants.PROOF_MAX_SKEW_MS]). Полностью
 * исключить повтор без соединения и challenge-response невозможно.
 *
 * Здесь только JCA и BigInteger: модуль тестируется на JVM без Android.
 */
object IdentityProof {

    const val VERSION: Byte = 1
    const val PUBLIC_KEY_BYTES = 33
    const val TIMESTAMP_BYTES = 4
    const val SIGNATURE_BYTES = 64
    const val PROOF_BYTES = 1 + PUBLIC_KEY_BYTES + TIMESTAMP_BYTES + SIGNATURE_BYTES

    /** Первый байт кадра — заголовок (поколение и индекс), остальное — данные. */
    const val CHUNK_DATA_BYTES = BleConstants.PROOF_FRAME_BYTES - 1
    const val CHUNK_COUNT = (PROOF_BYTES + CHUNK_DATA_BYTES - 1) / CHUNK_DATA_BYTES

    /** Поколение — 5 бит заголовка кадра, меняется при каждой новой подписи. */
    const val GENERATION_COUNT = 32

    const val CURVE_NAME = "secp256r1"

    private const val DOMAIN = "StreetPass-ID-v1"
    private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
    private const val COORDINATE_BYTES = 32
    private const val UINT32_MASK = 0xFFFF_FFFFL

    sealed interface Result {
        data class Verified(val timestampSeconds: Long) : Result
        data class Rejected(val reason: Reason) : Result
    }

    enum class Reason { MALFORMED, BAD_KEY, ID_MISMATCH, STALE, BAD_SIGNATURE }

    fun peerId(publicKey: PublicKey): ByteArray = peerId(compress(publicKey))

    fun peerId(compressedKey: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(compressedKey).copyOf(BleConstants.PEER_ID_BYTES)

    @Throws(GeneralSecurityException::class)
    fun sign(privateKey: PrivateKey, publicKey: PublicKey, timestampSeconds: Long): ByteArray {
        val key = compress(publicKey)
        val timestamp = encodeTimestamp(timestampSeconds)
        val signer = Signature.getInstance(SIGNATURE_ALGORITHM)
        signer.initSign(privateKey)
        signer.update(message(key, timestamp))
        val signature = derToRaw(signer.sign())
            ?: throw GeneralSecurityException("Провайдер вернул подпись неожиданного формата")
        return byteArrayOf(VERSION) + key + timestamp + signature
    }

    /**
     * [nowSeconds] передаётся параметром, а не берётся из часов — так проверка свежести
     * детерминированно тестируется.
     */
    fun verify(
        proof: ByteArray,
        expectedPeerId: ByteArray,
        nowSeconds: Long,
        maxSkewSeconds: Long = BleConstants.PROOF_MAX_SKEW_MS / 1000,
    ): Result {
        if (proof.size != PROOF_BYTES || proof[0] != VERSION) return Result.Rejected(Reason.MALFORMED)
        val key = proof.copyOfRange(1, 1 + PUBLIC_KEY_BYTES)
        val timestamp = proof.copyOfRange(1 + PUBLIC_KEY_BYTES, 1 + PUBLIC_KEY_BYTES + TIMESTAMP_BYTES)
        val signature = proof.copyOfRange(PROOF_BYTES - SIGNATURE_BYTES, PROOF_BYTES)

        // Дешёвые проверки до дорогой ECDSA: чужой или протухший пакет отбрасываем сразу.
        if (!peerId(key).contentEquals(expectedPeerId)) return Result.Rejected(Reason.ID_MISMATCH)
        val timestampSeconds = decodeTimestamp(timestamp)
        if (Math.abs(nowSeconds - timestampSeconds) > maxSkewSeconds) return Result.Rejected(Reason.STALE)
        val publicKey = decompress(key) ?: return Result.Rejected(Reason.BAD_KEY)

        val valid = try {
            val verifier = Signature.getInstance(SIGNATURE_ALGORITHM)
            verifier.initVerify(publicKey)
            verifier.update(message(key, timestamp))
            verifier.verify(rawToDer(signature))
        } catch (e: GeneralSecurityException) {
            false
        }
        return if (valid) Result.Verified(timestampSeconds) else Result.Rejected(Reason.BAD_SIGNATURE)
    }

    /** Кадры scan-response для одного доказательства; порядок соответствует индексам. */
    fun chunks(proof: ByteArray, generation: Int): List<ByteArray> {
        require(proof.size == PROOF_BYTES) { "Неверная длина доказательства" }
        return (0 until CHUNK_COUNT).map { index ->
            val from = index * CHUNK_DATA_BYTES
            byteArrayOf(header(generation, index)) + proof.copyOfRange(from, from + chunkLength(index))
        }
    }

    fun chunkLength(index: Int): Int =
        minOf(CHUNK_DATA_BYTES, PROOF_BYTES - index * CHUNK_DATA_BYTES)

    fun header(generation: Int, index: Int): Byte {
        require(index in 0 until CHUNK_COUNT) { "Индекс куска вне диапазона" }
        return (((generation and (GENERATION_COUNT - 1)) shl 3) or index).toByte()
    }

    fun generationOf(header: Byte): Int = (header.toInt() and 0xFF) ushr 3

    fun indexOf(header: Byte): Int = header.toInt() and 0x07

    /** Сжатая форма точки: 0x02/0x03 по чётности Y плюс 32 байта X. */
    fun compress(publicKey: PublicKey): ByteArray {
        val point = (publicKey as? ECPublicKey)?.w
            ?: throw IllegalArgumentException("Ожидался ключ EC")
        val prefix: Byte = if (point.affineY.testBit(0)) 0x03 else 0x02
        return byteArrayOf(prefix) + fixedLength(point.affineX, COORDINATE_BYTES)
    }

    /** null — байты не описывают точку на кривой; такое из эфира приходить может. */
    fun decompress(bytes: ByteArray): PublicKey? {
        if (bytes.size != PUBLIC_KEY_BYTES) return null
        val prefix = bytes[0].toInt()
        if (prefix != 0x02 && prefix != 0x03) return null
        val x = BigInteger(1, bytes.copyOfRange(1, PUBLIC_KEY_BYTES))
        if (x >= P) return null

        // y^2 = x^3 + ax + b; p ≡ 3 (mod 4), поэтому корень — возведение в (p+1)/4.
        val rhs = x.modPow(BigInteger.valueOf(3), P).add(A.multiply(x)).add(B).mod(P)
        var y = rhs.modPow(SQRT_EXPONENT, P)
        if (y.multiply(y).mod(P) != rhs) return null
        if (y.testBit(0) != (prefix == 0x03)) y = P.subtract(y)

        return try {
            KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), P256))
        } catch (e: GeneralSecurityException) {
            null
        }
    }

    private fun message(compressedKey: ByteArray, timestamp: ByteArray): ByteArray =
        DOMAIN.toByteArray(Charsets.US_ASCII) + VERSION + compressedKey + timestamp

    private fun encodeTimestamp(seconds: Long): ByteArray {
        val value = seconds and UINT32_MASK
        return ByteArray(TIMESTAMP_BYTES) { i -> (value ushr (8 * (TIMESTAMP_BYTES - 1 - i))).toByte() }
    }

    private fun decodeTimestamp(bytes: ByteArray): Long {
        var value = 0L
        for (b in bytes) value = (value shl 8) or (b.toLong() and 0xFF)
        return value
    }

    /**
     * JCA отдаёт ECDSA в DER (`SEQUENCE { INTEGER r, INTEGER s }`, 70–72 байта), в эфир
     * идут ровно 64 байта r||s. null — если провайдер вернул что-то иное.
     */
    fun derToRaw(der: ByteArray): ByteArray? {
        var pos = 0
        if (der.size < 8 || der[pos++] != 0x30.toByte()) return null
        val sequenceLength = der[pos++].toInt() and 0xFF
        if (sequenceLength and 0x80 != 0 || sequenceLength != der.size - 2) return null

        val out = ByteArray(SIGNATURE_BYTES)
        for (part in 0 until 2) {
            if (pos >= der.size || der[pos++] != 0x02.toByte()) return null
            if (pos >= der.size) return null
            val length = der[pos++].toInt() and 0xFF
            if (length and 0x80 != 0 || pos + length > der.size || length == 0) return null
            val value = BigInteger(1, der.copyOfRange(pos, pos + length))
            if (value.bitLength() > COORDINATE_BYTES * 8) return null
            fixedLength(value, COORDINATE_BYTES).copyInto(out, part * COORDINATE_BYTES)
            pos += length
        }
        return if (pos == der.size) out else null
    }

    fun rawToDer(raw: ByteArray): ByteArray {
        require(raw.size == SIGNATURE_BYTES) { "Неверная длина подписи" }
        val r = derInteger(BigInteger(1, raw.copyOfRange(0, COORDINATE_BYTES)))
        val s = derInteger(BigInteger(1, raw.copyOfRange(COORDINATE_BYTES, SIGNATURE_BYTES)))
        return byteArrayOf(0x30, (r.size + s.size).toByte()) + r + s
    }

    private fun derInteger(value: BigInteger): ByteArray {
        // toByteArray даёт минимальное знаковое представление — ровно то, что нужно DER.
        val body = value.toByteArray()
        return byteArrayOf(0x02, body.size.toByte()) + body
    }

    private fun fixedLength(value: BigInteger, length: Int): ByteArray {
        val raw = value.toByteArray()
        val out = ByteArray(length)
        val copy = minOf(raw.size, length)
        // Старший байт может быть нулём знака — его отбрасываем, остальное выравниваем вправо.
        System.arraycopy(raw, raw.size - copy, out, length - copy, copy)
        return out
    }

    // Параметры secp256r1 заданы явно, а не через AlgorithmParameters("EC"):
    // на Android 8 этот сервис есть не у всех провайдеров, а константы — везде.
    private val P = BigInteger("FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFF", 16)
    private val A = BigInteger("FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFC", 16)
    private val B = BigInteger("5AC635D8AA3A93E7B3EBBD55769886BC651D06B0CC53B0F63BCE3C3E27D2604B", 16)
    private val GX = BigInteger("6B17D1F2E12C4247F8BCE6E563A440F277037D812DEB33A0F4A13945D898C296", 16)
    private val GY = BigInteger("4FE342E2FE1A7F9B8EE7EB4A7C0F9E162BCE33576B315ECECBB6406837BF51F5", 16)
    private val N = BigInteger("FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551", 16)
    private val SQRT_EXPONENT = P.add(BigInteger.ONE).shiftRight(2)

    val P256: ECParameterSpec = ECParameterSpec(EllipticCurve(ECFieldFp(P), A, B), ECPoint(GX, GY), N, 1)
}

/**
 * Сборка доказательства одного peer'а из кусков, пришедших в произвольном порядке
 * и с повторами. Не потокобезопасен — живёт в consumer-корутине сервиса.
 */
class ProofAssembler {

    private val buffer = ByteArray(IdentityProof.PROOF_BYTES)
    private var generation = -1
    private var received = 0

    /** Собранное доказательство — ровно один раз, когда пришёл последний недостающий кусок. */
    fun accept(frame: ByteArray): ByteArray? {
        if (frame.size < 2 || frame.size > BleConstants.PROOF_FRAME_BYTES) return null
        val index = IdentityProof.indexOf(frame[0])
        if (index >= IdentityProof.CHUNK_COUNT) return null
        val length = IdentityProof.chunkLength(index)
        if (frame.size - 1 != length) return null

        val frameGeneration = IdentityProof.generationOf(frame[0])
        if (frameGeneration != generation) {
            generation = frameGeneration
            received = 0
        }
        if (received == COMPLETE) return null

        System.arraycopy(frame, 1, buffer, index * IdentityProof.CHUNK_DATA_BYTES, length)
        received = received or (1 shl index)
        return if (received == COMPLETE) buffer.copyOf() else null
    }

    /**
     * Сброс после неудачной проверки: поколение всего 5 бит, и после перезапуска
     * рекламы у соседа куски старого и нового доказательства могли смешаться.
     * Куски идут по кругу, так что честная пересборка исправит буфер.
     */
    fun reset() {
        received = 0
    }

    private companion object {
        const val COMPLETE = (1 shl IdentityProof.CHUNK_COUNT) - 1
    }
}

/**
 * Доверие к peer'ам по собранным доказательствам. Peer считается подтверждённым, пока
 * время его последней проверенной подписи укладывается в допустимый разброс часов.
 * Не потокобезопасен — живёт в consumer-корутине сервиса.
 */
class PeerVerifier(
    private val maxSkewMs: Long = BleConstants.PROOF_MAX_SKEW_MS,
) {

    private class Entry(var touchedAt: Long) {
        val assembler = ProofAssembler()
        var verifiedTimestampMs: Long? = null
    }

    private val peers = HashMap<String, Entry>()

    /** Кусок доказательства от [peerId]; результат есть только когда доказательство собралось. */
    fun onFrame(peerId: String, frame: ByteArray, now: Long): IdentityProof.Result? {
        val entry = peers.getOrPut(peerId) { Entry(now) }
        entry.touchedAt = now
        if (peers.size > BleConstants.DEBOUNCE_MAX_ENTRIES) prune(now)

        val proof = entry.assembler.accept(frame) ?: return null
        val expectedId = try {
            Hex.decode(peerId)
        } catch (e: IllegalArgumentException) {
            return IdentityProof.Result.Rejected(IdentityProof.Reason.MALFORMED)
        }
        val result = IdentityProof.verify(proof, expectedId, nowSeconds = now / 1000, maxSkewSeconds = maxSkewMs / 1000)
        when (result) {
            is IdentityProof.Result.Verified -> entry.verifiedTimestampMs = result.timestampSeconds * 1000
            is IdentityProof.Result.Rejected -> entry.assembler.reset()
        }
        return result
    }

    fun isTrusted(peerId: String, now: Long): Boolean {
        val verifiedAt = peers[peerId]?.verifiedTimestampMs ?: return false
        return Math.abs(now - verifiedAt) <= maxSkewMs
    }

    private fun prune(now: Long) {
        peers.entries.removeAll { now - it.value.touchedAt > BleConstants.DEBOUNCE_STALE_MS }
    }
}
