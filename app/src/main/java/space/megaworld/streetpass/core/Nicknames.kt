package space.megaworld.streetpass.core

object Nicknames {

    /** Убирает управляющие символы и лишние пробелы, режет до лимита байт UTF-8 по границе символа. */
    fun sanitize(raw: String): String {
        val cleaned = raw
            .filter { it.isLetterOrDigit() || it == ' ' || it in ALLOWED_PUNCTUATION }
            .trim()
            .replace(Regex(" {2,}"), " ")
        return truncateToBytes(cleaned, BleConstants.NICKNAME_MAX_BYTES)
    }

    fun byteLength(value: String): Int = value.toByteArray(Charsets.UTF_8).size

    fun encode(value: String): ByteArray = value.toByteArray(Charsets.UTF_8)

    /** Из эфира может прийти что угодно — проверяем так же строго, как свой ввод. */
    fun decode(bytes: ByteArray): String? {
        if (bytes.isEmpty() || bytes.size > BleConstants.NICKNAME_MAX_BYTES) return null
        val text = try {
            String(bytes, Charsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            return null
        }
        if (text.contains('�')) return null
        return sanitize(text).ifEmpty { null }
    }

    private fun truncateToBytes(value: String, maxBytes: Int): String {
        if (byteLength(value) <= maxBytes) return value
        val out = StringBuilder()
        var used = 0
        var i = 0
        while (i < value.length) {
            val codePoint = value.codePointAt(i)
            val chars = Character.charCount(codePoint)
            val piece = value.substring(i, i + chars)
            val size = byteLength(piece)
            if (used + size > maxBytes) break
            out.append(piece)
            used += size
            i += chars
        }
        return out.toString().trim()
    }

    private const val ALLOWED_PUNCTUATION = "_-.!?'"
}
