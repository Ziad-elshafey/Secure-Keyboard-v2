package dev.patrickgold.florisboard.secure.data.repository.compression

object CompressionService {
    private const val verbatimMarker = 254

    private val codebook = listOf(
        " ", "the", "e", "t", "a", "of", "o", "and", "i", "n", "s", "e ", "r", " th", " t", "in",
        "he", "th", "h", "he ", "to", "\r\n", "l", "s ", "d", " a", "an", "er", "c", " o", "d ", "on",
        " of", "re", "of ", "t ", ", ", "is", "u", "at", "   ", "n ", "or", "which", "f", "m", "as", "it",
        "that", "\n", "was", "en", "  ", " w", "es", " an", " i", "\r", "f ", "g", "p", "nd", " s", "nd ",
        "ed ", "w", "ed", "http://", "https://", "for", "te", "ing", "y ", "The", " c", "ti", "r ", "his",
        "st", " in", "ar", "nt", ",", " to", "y", "ng", " h", "with", "le", "al", "to ", "b", "ou", "be",
        "were", " b", "se", "o ", "ent", "ha", "ng ", "their", "\"", "hi", "from", " f", "in ", "de", "ion",
        "me", "v", ".", "ve", "all", "re ", "ri", "ro", "is ", "co", "f t", "are", "ea", ". ", "her", " m",
        "er ", " p", "es ", "by", "they", "di", "ra", "ic", "not", "s, ", "d t", "at ", "ce", "la", "h ",
        "ne", "as ", "tio", "on ", "n t", "io", "we", " a ", "om", ", a", "s o", "ur", "li", "ll", "ch",
        "had", "this", "e t", "g ", " wh", "ere", " co", "e o", "a ", "us", " d", "ss", "\n\r\n", "\r\n\r",
        "=\"", " be", " e", "s a", "ma", "one", "t t", "or ", "but", "el", "so", "l ", "e s", "s,", "no",
        "ter", " wa", "iv", "ho", "e a", " r", "hat", "s t", "ns", "ch ", "wh", "tr", "ut", "/", "have",
        "ly ", "ta", " ha", " on", "tha", "-", " l", "ati", "en ", "pe", " re", "there", "ass", "si", " fo",
        "wa", "ec", "our", "who", "its", "z", "fo", "rs", "ot", "un", "<", "im", "th ", "\u0000",
    ).dropLast(1).map { it.encodeToByteArray() }

    private val candidatesByFirstByte: Map<Int, List<Int>> = buildMap {
        codebook.indices.forEach { index ->
            val key = codebook[index].first().toInt() and 0xFF
            put(key, getOrDefault(key, emptyList()) + index)
        }
    }.mapValues { (_, indices) ->
        indices.sortedByDescending { codebook[it].size }
    }

    init {
        check(codebook.size == verbatimMarker) {
            "Expected $verbatimMarker SMAZ codebook entries, got ${codebook.size}"
        }
    }

    fun compress(input: ByteArray): ByteArray {
        if (input.isEmpty()) return byteArrayOf()

        val bestCost = IntArray(input.size + 1)
        val choiceType = IntArray(input.size)
        val choiceValue = IntArray(input.size)

        for (index in input.size - 1 downTo 0) {
            var best = Int.MAX_VALUE
            var bestType = 1
            var bestValue = 1

            candidatesByFirstByte[input[index].toInt() and 0xFF].orEmpty().forEach { tokenIndex ->
                val token = codebook[tokenIndex]
                if (matchesAt(input, index, token)) {
                    val cost = 1 + bestCost[index + token.size]
                    if (cost < best) {
                        best = cost
                        bestType = 0
                        bestValue = tokenIndex
                    }
                }
            }

            val maxLiteralLength = minOf(255, input.size - index)
            for (length in 1..maxLiteralLength) {
                val cost = 2 + length + bestCost[index + length]
                if (cost < best) {
                    best = cost
                    bestType = 1
                    bestValue = length
                }
            }

            bestCost[index] = best
            choiceType[index] = bestType
            choiceValue[index] = bestValue
        }

        val encoded = ArrayList<Byte>(bestCost[0])
        var cursor = 0
        while (cursor < input.size) {
            when (choiceType[cursor]) {
                0 -> {
                    encoded += choiceValue[cursor].toByte()
                    cursor += codebook[choiceValue[cursor]].size
                }

                else -> {
                    val length = choiceValue[cursor]
                    encoded += verbatimMarker.toByte()
                    encoded += length.toByte()
                    repeat(length) { offset ->
                        encoded += input[cursor + offset]
                    }
                    cursor += length
                }
            }
        }
        return encoded.toByteArray()
    }

    fun decompress(input: ByteArray): ByteArray {
        if (input.isEmpty()) return byteArrayOf()

        val decoded = ArrayList<Byte>(input.size * 2)
        var cursor = 0
        while (cursor < input.size) {
            val code = input[cursor].toInt() and 0xFF
            cursor += 1
            if (code == verbatimMarker) {
                require(cursor < input.size) { "Malformed SMAZ data: missing literal length" }
                val length = input[cursor].toInt() and 0xFF
                cursor += 1
                require(length > 0) { "Malformed SMAZ data: literal length must be > 0" }
                require(cursor + length <= input.size) {
                    "Malformed SMAZ data: literal run overruns input"
                }
                repeat(length) { offset ->
                    decoded += input[cursor + offset]
                }
                cursor += length
            } else {
                require(code in codebook.indices) { "Malformed SMAZ data: unknown code $code" }
                codebook[code].forEach { decoded += it }
            }
        }
        return decoded.toByteArray()
    }

    private fun matchesAt(input: ByteArray, offset: Int, token: ByteArray): Boolean {
        if (offset + token.size > input.size) return false
        for (index in token.indices) {
            if (input[offset + index] != token[index]) return false
        }
        return true
    }
}
