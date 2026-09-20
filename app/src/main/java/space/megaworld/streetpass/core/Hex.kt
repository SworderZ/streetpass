package space.megaworld.streetpass.core

object Hex {
    private const val DIGITS = "0123456789abcdef"

    fun encode(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            out.append(DIGITS[v ushr 4]).append(DIGITS[v and 0x0F])
        }
        return out.toString()
    }

    fun decode(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "Нечётная длина hex-строки" }
        return ByteArray(hex.length / 2) { i ->
            val hi = Character.digit(hex[i * 2], 16)
            val lo = Character.digit(hex[i * 2 + 1], 16)
            require(hi >= 0 && lo >= 0) { "Недопустимый символ в hex-строке" }
            ((hi shl 4) or lo).toByte()
        }
    }

    fun short(peerId: String): String = peerId.take(8).uppercase()

    fun grouped(peerId: String): String = peerId.uppercase().chunked(4).joinToString(" ")
}
