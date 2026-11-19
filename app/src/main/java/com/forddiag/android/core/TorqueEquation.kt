package com.forddiag.android.core

/**
 * Minimal Torque equation evaluator for Ford extended PIDs.
 *
 * Supports: A–D (unsigned bytes), signed(X), + - * /, parentheses, literals,
 * Torque bit-shift `<` (e.g. `A<8` ≡ `A shl 8`), and optional BARO().
 */
object TorqueEquation {
    fun evaluate(
        equation: String,
        data: ByteArray,
        baro: Double? = null,
    ): Double? {
        val eq = equation.trim()
        if (eq.isEmpty()) return null
        return try {
            Parser(eq, data, baro).parseExpression()
        } catch (_: Exception) {
            null
        }
    }

    private class Parser(
        private val src: String,
        private val data: ByteArray,
        private val baro: Double?,
    ) {
        private var i = 0

        fun parseExpression(): Double {
            var v = parseTerm()
            while (true) {
                skipWs()
                when {
                    match('+') -> v += parseTerm()
                    match('-') -> v -= parseTerm()
                    else -> return v
                }
            }
        }

        private fun parseTerm(): Double {
            var v = parseFactor()
            while (true) {
                skipWs()
                when {
                    match('*') -> v *= parseFactor()
                    match('/') -> {
                        val d = parseFactor()
                        v = if (d == 0.0) Double.NaN else v / d
                    }
                    else -> return v
                }
            }
        }

        private fun parseFactor(): Double {
            skipWs()
            if (match('+')) return parseFactor()
            if (match('-')) return -parseFactor()

            // Torque bit-shift: A<8  (after primary)
            var v = parsePrimary()
            skipWs()
            // Only treat `<` as shift when followed by a digit (not comparison)
            if (peek() == '<' && peek(1)?.isDigit() == true) {
                i++ // consume <
                val shift = parsePrimary().toInt()
                v = (v.toLong() shl shift).toDouble()
            }
            return v
        }

        private fun parsePrimary(): Double {
            skipWs()
            if (match('(')) {
                val v = parseExpression()
                expect(')')
                return v
            }

            // Functions / identifiers
            if (peek()?.isLetter() == true) {
                val name = readIdent()
                skipWs()
                if (match('(')) {
                    return when (name.uppercase()) {
                        "SIGNED" -> {
                            val arg = parseExpression()
                            expect(')')
                            // signed byte: interpret low 8 bits as Int8
                            val b = arg.toInt() and 0xFF
                            (if (b >= 128) b - 256 else b).toDouble()
                        }
                        "BARO" -> {
                            expect(')')
                            baro ?: throw IllegalStateException("BARO() unavailable")
                        }
                        else -> throw IllegalArgumentException("unknown function $name")
                    }
                }
                return when (name.uppercase()) {
                    "A" -> byteAt(0)
                    "B" -> byteAt(1)
                    "C" -> byteAt(2)
                    "D" -> byteAt(3)
                    "E" -> byteAt(4)
                    "F" -> byteAt(5)
                    "L" -> byteAt(0) // Torque alias used in some sets
                    else -> throw IllegalArgumentException("unknown ident $name")
                }
            }

            // Number
            if (peek()?.isDigit() == true || peek() == '.') {
                return readNumber()
            }
            throw IllegalArgumentException("unexpected at $i in '$src'")
        }

        private fun byteAt(index: Int): Double {
            if (index !in data.indices) throw IllegalArgumentException("byte $index missing")
            return (data[index].toInt() and 0xFF).toDouble()
        }

        private fun readIdent(): String {
            val start = i
            while (i < src.length && (src[i].isLetterOrDigit() || src[i] == '_')) i++
            return src.substring(start, i)
        }

        private fun readNumber(): Double {
            val start = i
            while (i < src.length && (src[i].isDigit() || src[i] == '.')) i++
            return src.substring(start, i).toDouble()
        }

        private fun skipWs() {
            while (i < src.length && src[i].isWhitespace()) i++
        }

        private fun peek(offset: Int = 0): Char? =
            src.getOrNull(i + offset)

        private fun match(c: Char): Boolean {
            skipWs()
            if (peek() == c) {
                i++
                return true
            }
            return false
        }

        private fun expect(c: Char) {
            skipWs()
            if (peek() != c) throw IllegalArgumentException("expected '$c' at $i")
            i++
        }
    }
}

/** Format a decoded Ford extended value for display. */
fun formatFordValue(value: Double, unit: String): String {
    val text = if (value == value.toLong().toDouble()) {
        "%.0f".format(value)
    } else {
        "%.2f".format(value)
    }
    return if (unit.isBlank()) text else "$text $unit"
}
