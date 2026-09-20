package space.megaworld.streetpass.core

object Versions {

    /** «v1.2.3», «1.2.3-beta» → [1, 2, 3]; нечисловые хвосты отбрасываются. */
    fun parse(version: String): List<Int> =
        version.trim().removePrefix("v").removePrefix("V")
            .split('.', '-', '+')
            .map { part -> part.takeWhile { it.isDigit() }.toIntOrNull() ?: return@map -1 }
            .takeWhile { it >= 0 }

    /** Отрицательное — [a] старше [b], ноль — равны, положительное — [a] новее. */
    fun compare(a: String, b: String): Int {
        val pa = parse(a)
        val pb = parse(b)
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val diff = (pa.getOrNull(i) ?: 0) - (pb.getOrNull(i) ?: 0)
            if (diff != 0) return diff
        }
        return 0
    }

    fun isNewer(candidate: String, current: String): Boolean = compare(candidate, current) > 0
}
