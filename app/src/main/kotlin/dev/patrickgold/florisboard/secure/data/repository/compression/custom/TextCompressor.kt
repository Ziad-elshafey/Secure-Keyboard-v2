package dev.patrickgold.florisboard.secure.data.repository.compression.custom

class TextCompressor(
    private val maxVocabSize: Int = 16384,
) {
    companion object {
        const val escapeSymbol = 0
    }

    private var wordToId = mutableMapOf<String, Int>()
    private var idToWord = mutableMapOf<Int, String>()
    private var wordFrequencies = mutableMapOf<Int, Int>()
    private var coder: ArithmeticCoder? = null
    var entropy = 0.0
        private set

    private fun tokenize(text: String): List<String> {
        val regex = Regex("[\\w']+|\\S")
        return regex.findAll(text.lowercase()).map { it.value }.toList()
    }

    fun loadVocab(content: String) {
        try {
            val wtiStart = content.indexOf("\"word_to_id\"")
            val wtiOpen = content.indexOf("{", wtiStart)
            val wtiClose = content.indexOf("}", wtiOpen)
            val wtiContent = content.substring(wtiOpen + 1, wtiClose)

            val kvRegex = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"\\s*:\\s*(\\d+)")

            wordToId.clear()
            kvRegex.findAll(wtiContent).forEach { match ->
                val key = unescapeJson(match.groupValues[1])
                val value = match.groupValues[2].toInt()
                wordToId[key] = value
            }

            idToWord.clear()
            idToWord[escapeSymbol] = "<UNK>"
            wordToId.forEach { (k, v) -> idToWord[v] = k }

            val wfStart = content.indexOf("\"word_frequencies\"")
            val wfOpen = content.indexOf("{", wfStart)
            val wfClose = content.indexOf("}", wfOpen)
            val wfContent = content.substring(wfOpen + 1, wfClose)

            val kwfRegex = Regex("\"(\\d+)\"\\s*:\\s*(\\d+)")

            wordFrequencies.clear()
            kwfRegex.findAll(wfContent).forEach { match ->
                val key = match.groupValues[1].toInt()
                val value = match.groupValues[2].toInt()
                wordFrequencies[key] = value
            }

            val entStart = content.indexOf("\"entropy\"")
            if (entStart != -1) {
                val entColon = content.indexOf(":", entStart)
                val entEnd = content.indexOfAny(charArrayOf('\n', ',', '}'), entColon)
                val entVal = content.substring(
                    entColon + 1,
                    if (entEnd != -1) entEnd else content.lastIndexOf("}"),
                ).trim()
                entropy = entVal.toDoubleOrNull() ?: 0.0
            }

            coder = ArithmeticCoder(wordFrequencies)
        } catch (e: Exception) {
            e.printStackTrace()
            throw e
        }
    }

    private fun unescapeJson(s: String): String {
        return s.replace("\\\"", "\"").replace("\\\\", "\\")
    }

    fun compress(text: String): ByteArray {
        coder?.let { c ->
            val words = tokenize(text)
            val symbols = ArrayList<Int>()
            val unknownWords = ArrayList<String>()

            for (word in words) {
                if (wordToId.containsKey(word)) {
                    symbols.add(wordToId[word]!!)
                } else {
                    symbols.add(escapeSymbol)
                    unknownWords.add(word)
                }
            }

            val encoded = c.encode(symbols)
            val unknownData = ArrayList<Byte>()
            for (word in unknownWords) {
                val bytes = word.toByteArray(Charsets.UTF_8)
                if (bytes.size > 255) {
                    unknownData.add(255.toByte())
                    bytes.take(255).forEach { unknownData.add(it) }
                } else {
                    unknownData.add(bytes.size.toByte())
                    bytes.forEach { unknownData.add(it) }
                }
            }

            val numSymbols = symbols.size
            val encodedLen = encoded.size
            val header = ByteArray(6)
            header[0] = ((numSymbols shr 16) and 0xFF).toByte()
            header[1] = ((numSymbols shr 8) and 0xFF).toByte()
            header[2] = (numSymbols and 0xFF).toByte()
            header[3] = ((encodedLen shr 16) and 0xFF).toByte()
            header[4] = ((encodedLen shr 8) and 0xFF).toByte()
            header[5] = (encodedLen and 0xFF).toByte()

            val result = ByteArray(header.size + encoded.size + unknownData.size)
            System.arraycopy(header, 0, result, 0, header.size)
            System.arraycopy(encoded, 0, result, header.size, encoded.size)
            for (i in unknownData.indices) {
                result[header.size + encoded.size + i] = unknownData[i]
            }

            return result
        } ?: throw IllegalStateException("No vocabulary loaded.")
    }

    fun decompress(compressed: ByteArray): String {
        coder?.let { c ->
            if (compressed.size < 6) return ""

            val numSymbols = ((compressed[0].toInt() and 0xFF) shl 16) or
                ((compressed[1].toInt() and 0xFF) shl 8) or
                (compressed[2].toInt() and 0xFF)

            val encodedLen = ((compressed[3].toInt() and 0xFF) shl 16) or
                ((compressed[4].toInt() and 0xFF) shl 8) or
                (compressed[5].toInt() and 0xFF)

            if (6 + encodedLen > compressed.size) return ""

            val encodedData = ByteArray(encodedLen)
            System.arraycopy(compressed, 6, encodedData, 0, encodedLen)

            val unknownDataOffset = 6 + encodedLen
            val unknownData = ByteArray(compressed.size - unknownDataOffset)
            System.arraycopy(compressed, unknownDataOffset, unknownData, 0, unknownData.size)

            val symbols = c.decode(encodedData, numSymbols)
            var unknownOffset = 0
            val words = ArrayList<String>()

            for (symbol in symbols) {
                if (symbol == escapeSymbol) {
                    if (unknownOffset < unknownData.size) {
                        val length = unknownData[unknownOffset].toInt() and 0xFF
                        if (unknownOffset + 1 + length <= unknownData.size) {
                            val wordBytes = ByteArray(length)
                            System.arraycopy(unknownData, unknownOffset + 1, wordBytes, 0, length)
                            words.add(String(wordBytes, Charsets.UTF_8))
                            unknownOffset += 1 + length
                        } else {
                            words.add("<UNK>")
                            unknownOffset = unknownData.size
                        }
                    } else {
                        words.add("<UNK>")
                    }
                } else {
                    words.add(idToWord.getOrDefault(symbol, "<UNK>"))
                }
            }

            val sb = StringBuilder()
            val punctuation = ".,!?;:\"')"
            val openPunctuation = "(\"'"

            for (i in words.indices) {
                val word = words[i]
                if (i > 0 && word !in punctuation && (words[i - 1].isEmpty() || words[i - 1] !in openPunctuation)) {
                    sb.append(" ")
                }
                sb.append(word)
            }

            return sb.toString()
        } ?: throw IllegalStateException("No vocabulary loaded.")
    }
}