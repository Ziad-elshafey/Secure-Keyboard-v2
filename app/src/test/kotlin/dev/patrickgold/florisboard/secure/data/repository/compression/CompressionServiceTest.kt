package dev.patrickgold.florisboard.secure.data.repository.compression

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class CompressionServiceTest : FunSpec({
    test("round trips ASCII text") {
        val input = "the quick brown fox jumps over the lazy dog".encodeToByteArray()

        val compressed = CompressionService.compress(input)
        val decompressed = CompressionService.decompress(compressed)

        decompressed shouldBe input
    }

    test("round trips mixed unicode and emoji") {
        val input = "Hello, नमस्ते, こんにちは, 🚀🔐".encodeToByteArray()

        val compressed = CompressionService.compress(input)
        val decompressed = CompressionService.decompress(compressed)

        decompressed shouldBe input
    }

    test("empty input stays empty") {
        CompressionService.compress(byteArrayOf()) shouldBe byteArrayOf()
        CompressionService.decompress(byteArrayOf()) shouldBe byteArrayOf()
    }

    test("corrupt compressed data fails deterministically") {
        val corrupt = byteArrayOf(254.toByte(), 4, 'o'.code.toByte())

        shouldThrow<IllegalArgumentException> {
            CompressionService.decompress(corrupt)
        }.message shouldBe "Malformed SMAZ data: literal run overruns input"
    }
})
