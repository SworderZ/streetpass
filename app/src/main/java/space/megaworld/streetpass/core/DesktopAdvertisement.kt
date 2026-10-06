package space.megaworld.streetpass.core

/**
 * Разбор дополнительного BLE-конверта, который используют desktop-клиенты.
 *
 * Company ID 0xFFFF здесь намеренно означает development-производителя: это
 * отдельный транспорт для совместимости с Windows, а не изменение Android-пакета.
 * Подпись внутри proof не меняется, меняется только размер кусков на внешнем слое.
 */
object DesktopAdvertisement {

    const val COMPANY_ID = 0xFFFF
    const val VERSION: Byte = 1
    const val KIND_NICKNAME: Byte = 0
    const val KIND_PROOF_CHUNK: Byte = 1

    const val MAX_NICKNAME_BYTES = 12
    const val PROOF_CHUNK_BYTES = 11
    const val PROOF_CHUNK_COUNT = 10
    const val PROOF_GENERATION_COUNT = 16
    const val MAX_ASSEMBLIES = 512
    const val STALE_ASSEMBLY_MS = 10 * 60_000L

    const val PREFIX_BYTES = 3
    const val COMMON_BYTES = PREFIX_BYTES + 1 + BleConstants.PEER_ID_BYTES

    /** Префикс без Bluetooth company ID: Android возвращает manufacturer data уже без него. */
    val PREFIX: ByteArray = byteArrayOf(0x53, 0x50, VERSION)

    sealed interface Packet {
        val peerId: ByteArray

        data class Nickname(
            override val peerId: ByteArray,
            val value: String,
        ) : Packet

        data class ProofChunk(
            override val peerId: ByteArray,
            val generation: Int,
            val index: Int,
            val data: ByteArray,
        ) : Packet
    }

    /**
     * Разбирает только полностью валидный envelope. В частности, UTF-8 и ник
     * проверяются без тихого исправления: иначе desktop мог бы передать строку,
     * отличающуюся от той, которую пользователь действительно рекламировал.
     */
    fun parse(payload: ByteArray): Packet? {
        if (payload.size < COMMON_BYTES || !hasPrefix(payload)) return null

        val kind = payload[PREFIX_BYTES]
        val peerId = payload.copyOfRange(PREFIX_BYTES + 1, COMMON_BYTES)
        return when (kind) {
            KIND_NICKNAME -> parseNickname(peerId, payload)
            KIND_PROOF_CHUNK -> parseProofChunk(peerId, payload)
            else -> null
        }
    }

    /** Кодирует nickname-пакет, обрезая UTF-8 только по границе code point. */
    fun encodeNickname(peerId: ByteArray, rawNickname: String): ByteArray {
        require(peerId.size == BleConstants.PEER_ID_BYTES) { "Неверная длина peer ID" }
        val nickname = truncateUtf8(Nicknames.sanitize(rawNickname), MAX_NICKNAME_BYTES)
        val bytes = nickname.toByteArray(Charsets.UTF_8)
        require(bytes.isNotEmpty()) { "Пустой nickname не рекламируется" }
        return prefix(KIND_NICKNAME, peerId) + bytes
    }

    /** Кодирует один из десяти desktop-кусков исходного 102-байтного proof. */
    fun encodeProofChunk(peerId: ByteArray, proof: ByteArray, generation: Int, index: Int): ByteArray {
        require(proof.size == IdentityProof.PROOF_BYTES) { "Неверная длина доказательства" }
        val length = chunkLength(index)
        val from = index * PROOF_CHUNK_BYTES
        return encodeProofData(peerId, proof.copyOfRange(from, from + length), generation, index)
    }

    /** Низкоуровневая форма для desktop-транспортов и тестов. */
    fun encodeProofData(peerId: ByteArray, data: ByteArray, generation: Int, index: Int): ByteArray {
        require(peerId.size == BleConstants.PEER_ID_BYTES) { "Неверная длина peer ID" }
        require(generation in 0 until PROOF_GENERATION_COUNT) { "Поколение вне диапазона" }
        val length = chunkLength(index)
        require(data.size == length) { "Неверная длина куска доказательства" }
        val header = ((generation shl 4) or index).toByte()
        return prefix(KIND_PROOF_CHUNK, peerId) + byteArrayOf(header) + data
    }

    fun chunkLength(index: Int): Int {
        require(index in 0 until PROOF_CHUNK_COUNT) { "Индекс куска вне диапазона" }
        return minOf(PROOF_CHUNK_BYTES, IdentityProof.PROOF_BYTES - index * PROOF_CHUNK_BYTES)
    }

    private fun parseNickname(peerId: ByteArray, payload: ByteArray): Packet.Nickname? {
        val bytes = payload.copyOfRange(COMMON_BYTES, payload.size)
        if (bytes.isEmpty() || bytes.size > MAX_NICKNAME_BYTES) return null
        val value = Nicknames.decode(bytes) ?: return null
        // decode() sanitises input for the legacy protocol; desktop frames require
        // that the bytes on air already are the sanitised UTF-8 representation.
        if (!Nicknames.encode(value).contentEquals(bytes)) return null
        return Packet.Nickname(peerId, value)
    }

    private fun parseProofChunk(peerId: ByteArray, payload: ByteArray): Packet.ProofChunk? {
        if (payload.size < COMMON_BYTES + 1) return null
        val header = payload[COMMON_BYTES].toInt() and 0xFF
        val generation = header ushr 4
        val index = header and 0x0F
        if (index >= PROOF_CHUNK_COUNT) return null
        val length = chunkLength(index)
        if (payload.size != COMMON_BYTES + 1 + length) return null
        return Packet.ProofChunk(
            peerId = peerId,
            generation = generation,
            index = index,
            data = payload.copyOfRange(COMMON_BYTES + 1, payload.size),
        )
    }

    private fun hasPrefix(payload: ByteArray): Boolean =
        payload[0] == PREFIX[0] && payload[1] == PREFIX[1] && payload[2] == PREFIX[2]

    private fun prefix(kind: Byte, peerId: ByteArray): ByteArray = PREFIX + byteArrayOf(kind) + peerId

    private fun truncateUtf8(value: String, maxBytes: Int): String {
        if (value.toByteArray(Charsets.UTF_8).size <= maxBytes) return value
        val out = StringBuilder()
        var used = 0
        var offset = 0
        while (offset < value.length) {
            val codePoint = value.codePointAt(offset)
            val chars = Character.charCount(codePoint)
            val piece = value.substring(offset, offset + chars)
            val bytes = piece.toByteArray(Charsets.UTF_8).size
            if (used + bytes > maxBytes) break
            out.append(piece)
            used += bytes
            offset += chars
        }
        return out.toString().trim()
    }
}

/**
 * Сборка десяти desktop-кусков в стандартные Android-кадры proof.
 * Живёт внутри одного [BleScanner], поэтому синхронизация не требуется.
 */
class DesktopProofAssembler(
    private val maxEntries: Int = DesktopAdvertisement.MAX_ASSEMBLIES,
    private val staleAfterMs: Long = DesktopAdvertisement.STALE_ASSEMBLY_MS,
) {

    private class Entry(var touchedAt: Long, var generation: Int) {
        val buffer = ByteArray(IdentityProof.PROOF_BYTES)
        var received = 0
    }

    private val entries = HashMap<String, Entry>()

    val size: Int get() = entries.size

    /** Возвращает четыре обычных кадра только после полной desktop-сборки. */
    fun accept(packet: DesktopAdvertisement.Packet.ProofChunk, now: Long): List<ByteArray>? {
        if (maxEntries <= 0 || staleAfterMs < 0) return null
        if (packet.peerId.size != BleConstants.PEER_ID_BYTES) return null
        if (packet.generation !in 0 until DesktopAdvertisement.PROOF_GENERATION_COUNT) return null
        if (packet.index !in 0 until DesktopAdvertisement.PROOF_CHUNK_COUNT) return null
        if (packet.data.size != DesktopAdvertisement.chunkLength(packet.index)) return null

        prune(now)
        val key = Hex.encode(packet.peerId)
        val entry = entries[key] ?: run {
            while (entries.size >= maxEntries) removeOldest()
            Entry(now, packet.generation).also { entries[key] = it }
        }
        entry.touchedAt = now
        if (packet.generation != entry.generation) {
            entry.generation = packet.generation
            entry.received = 0
            entry.buffer.fill(0)
        }

        val from = packet.index * DesktopAdvertisement.PROOF_CHUNK_BYTES
        packet.data.copyInto(entry.buffer, from)
        entry.received = entry.received or (1 shl packet.index)
        if (entry.received != COMPLETE) return null

        val proof = entry.buffer.copyOf()
        // Начинаем новый цикл сразу: это позволяет восстановиться после полной,
        // но поддельной сборки с тем же 4-битным поколением.
        entry.received = 0
        return IdentityProof.chunks(proof, packet.generation)
    }

    fun reset(peerId: ByteArray) {
        if (peerId.size == BleConstants.PEER_ID_BYTES) entries.remove(Hex.encode(peerId))
    }

    fun clear() = entries.clear()

    private fun prune(now: Long) {
        entries.entries.removeAll { now - it.value.touchedAt > staleAfterMs }
    }

    private fun removeOldest() {
        val oldest = entries.minByOrNull { it.value.touchedAt } ?: return
        entries.remove(oldest.key)
    }

    private companion object {
        const val COMPLETE = (1 shl DesktopAdvertisement.PROOF_CHUNK_COUNT) - 1
    }
}
