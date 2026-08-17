package viewforge.model

import kotlinx.serialization.Serializable
import kotlin.random.Random

/**
 * Stable identity for a [Node]. A ULID string: sortable, compact, time-ordered (DATA_MODEL §5).
 * Never reused, never reassigned; copy/paste generates a fresh one via [random].
 *
 * A value class so identity can't be confused with an arbitrary [String] at call sites, while
 * still serializing as a plain string for clean diffs.
 */
@JvmInline
@Serializable
value class NodeId(val value: String) {
    override fun toString(): String = value

    companion object {
        fun random(): NodeId = NodeId(Ulid.next())
    }
}

/**
 * Minimal ULID generator: 48-bit millisecond timestamp + 80 bits of randomness, Crockford base32.
 * Deliberately stdlib-only (`kotlin.random`, no `java.security`) so `core/model` keeps its
 * zero-dependency, KMP-friendly shape (ARCHITECTURE §3). IDs need uniqueness, not cryptographic
 * unpredictability, so a non-secure RNG is fine.
 */
object Ulid {
    // Crockford base32 alphabet — excludes I, L, O, U to avoid visual ambiguity.
    private const val ENCODING = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private const val TIME_LEN = 10
    private const val RANDOM_LEN = 16

    fun next(): String {
        val sb = StringBuilder(TIME_LEN + RANDOM_LEN)
        var time = System.currentTimeMillis() and 0xFFFFFFFFFFFFL // low 48 bits
        val timeChars = CharArray(TIME_LEN)
        for (i in TIME_LEN - 1 downTo 0) {
            timeChars[i] = ENCODING[(time and 0x1F).toInt()]
            time = time ushr 5
        }
        sb.append(timeChars)
        repeat(RANDOM_LEN) { sb.append(ENCODING[Random.nextInt(ENCODING.length)]) }
        return sb.toString()
    }
}
