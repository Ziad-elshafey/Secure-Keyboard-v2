package dev.patrickgold.florisboard.secure.core.e2ee

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class E2EEServiceCounterTest : FunSpec({
    test("pack and unpack preserve ciphertext and counter") {
        val ciphertext = byteArrayOf(0x10, 0x20, 0x30, 0x40)
        val counters = listOf(0, 1, 255, 1024, 65535)

        counters.forEach { counter ->
            val packed = E2EEService.packCiphertextWithCounter(ciphertext, counter)
            val (unpackedCiphertext, unpackedCounter) = E2EEService.unpackCiphertextAndCounter(packed)

            unpackedCiphertext shouldBe ciphertext
            unpackedCounter shouldBe counter
        }
    }

    test("counter-derived chacha20 round trip decrypts to original payload") {
        val sharedSecret = ByteArray(32) { index -> (index + 1).toByte() }
        val counter = 42
        val payload = "secure messaging round trip".toByteArray()

        val ciphertext = E2EEService.chacha20Encrypt(payload, sharedSecret, counter)
        val decrypted = E2EEService.chacha20Decrypt(ciphertext, sharedSecret, counter)

        decrypted shouldBe payload
    }
})
