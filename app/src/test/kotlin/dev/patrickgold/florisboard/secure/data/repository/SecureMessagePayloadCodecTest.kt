package dev.patrickgold.florisboard.secure.data.repository

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class SecureMessagePayloadCodecTest : FunSpec({
    test("uses raw payload when compression is not smaller") {
        val payload = SecureMessagePayloadCodec.buildPayload("🚀")

        payload.first() shouldBe SecureMessagePayloadCodec.flagRaw
        SecureMessagePayloadCodec.parsePayload(payload) shouldBe "🚀"
    }

    test("uses smaz payload when compression is smaller") {
        val plaintext = "the theater in the theater in the theater"

        val payload = SecureMessagePayloadCodec.buildPayload(plaintext)

        payload.first() shouldBe SecureMessagePayloadCodec.flagSmaz
        SecureMessagePayloadCodec.parsePayload(payload) shouldBe plaintext
    }

    test("build and parse round trip plaintext") {
        val plaintext = "secure messaging payload\nwith emoji 🔐 and punctuation !?"

        SecureMessagePayloadCodec.parsePayload(
            SecureMessagePayloadCodec.buildPayload(plaintext),
        ) shouldBe plaintext
    }

    test("unknown payload flag fails fast") {
        shouldThrow<IllegalStateException> {
            SecureMessagePayloadCodec.parsePayload(byteArrayOf(0x7F, 0x41))
        }.message shouldBe "Unknown payload flag 0x7F"
    }
})
