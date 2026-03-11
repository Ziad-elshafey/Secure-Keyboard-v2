package dev.patrickgold.florisboard.secure.data.repository.compression.custom

class ArithmeticCoder(private val frequencies: Map<Int, Int>) {
    companion object {
        const val precisionBits = 32
        const val maxRange = (1L shl precisionBits) - 1
        const val half = 1L shl (precisionBits - 1)
        const val quarter = 1L shl (precisionBits - 2)
        const val threeQuarters = half + quarter
    }

    private val cumFreq = mutableMapOf<Int, Pair<Long, Long>>()
    private val totalFreq: Long

    init {
        var cumulative = 0L
        val sortedKeys = frequencies.keys.sorted()

        for (symbol in sortedKeys) {
            val freq = frequencies[symbol]!!.toLong()
            cumFreq[symbol] = Pair(cumulative, cumulative + freq)
            cumulative += freq
        }
        totalFreq = cumulative
    }

    fun encode(symbols: List<Int>): ByteArray {
        var low = 0L
        var high = maxRange
        var pendingBits = 0
        val bits = ArrayList<Int>()

        for (symbol in symbols) {
            if (!cumFreq.containsKey(symbol)) {
                throw IllegalArgumentException("Unknown symbol: $symbol")
            }

            val rangeSize = high - low + 1
            val (symLow, symHigh) = cumFreq[symbol]!!

            high = low + (rangeSize * symHigh) / totalFreq - 1
            low += (rangeSize * symLow) / totalFreq

            while (true) {
                if (high < half) {
                    bits.add(0)
                    repeat(pendingBits) { bits.add(1) }
                    pendingBits = 0
                } else if (low >= half) {
                    bits.add(1)
                    repeat(pendingBits) { bits.add(0) }
                    pendingBits = 0
                    low -= half
                    high -= half
                } else if (low >= quarter && high < threeQuarters) {
                    pendingBits++
                    low -= quarter
                    high -= quarter
                } else {
                    break
                }

                low = low shl 1
                high = (high shl 1) or 1
            }
        }

        pendingBits++
        if (low < quarter) {
            bits.add(0)
            repeat(pendingBits) { bits.add(1) }
        } else {
            bits.add(1)
            repeat(pendingBits) { bits.add(0) }
        }

        while (bits.size % 8 != 0) {
            bits.add(0)
        }

        val result = ByteArray(bits.size / 8)
        for (i in result.indices) {
            var byteVal = 0
            for (j in 0 until 8) {
                byteVal = (byteVal shl 1) or bits[i * 8 + j]
            }
            result[i] = byteVal.toByte()
        }

        return result
    }

    fun decode(data: ByteArray, numSymbols: Int): List<Int> {
        val bits = ArrayList<Int>()
        for (byte in data) {
            val b = byte.toInt() and 0xFF
            for (i in 7 downTo 0) {
                bits.add((b shr i) and 1)
            }
        }

        var low = 0L
        var high = maxRange
        var value = 0L

        for (i in 0 until precisionBits) {
            val bit = if (i < bits.size) bits[i] else 0
            value = (value shl 1) or bit.toLong()
        }

        var bitIndex = precisionBits
        val symbols = ArrayList<Int>()

        for (n in 0 until numSymbols) {
            val rangeSize = high - low + 1
            val scaledValue = ((value - low + 1) * totalFreq - 1) / rangeSize

            var foundSymbol: Int? = null
            for ((symbol, range) in cumFreq) {
                val (symLow, symHigh) = range
                if (scaledValue >= symLow && scaledValue < symHigh) {
                    foundSymbol = symbol
                    break
                }
            }

            if (foundSymbol == null) break

            symbols.add(foundSymbol)
            val (symLow, symHigh) = cumFreq[foundSymbol]!!

            high = low + (rangeSize * symHigh) / totalFreq - 1
            low += (rangeSize * symLow) / totalFreq

            while (true) {
                if (high < half) {
                    // no-op
                } else if (low >= half) {
                    low -= half
                    high -= half
                    value -= half
                } else if (low >= quarter && high < threeQuarters) {
                    low -= quarter
                    high -= quarter
                    value -= quarter
                } else {
                    break
                }

                low = low shl 1
                high = (high shl 1) or 1

                val nextBit = if (bitIndex < bits.size) bits[bitIndex] else 0
                value = (value shl 1) or nextBit.toLong()
                bitIndex++
            }
        }

        return symbols
    }
}