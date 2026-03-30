package dev.patrickgold.florisboard.secure.core.e2ee

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.assertions.throwables.shouldThrow

class E2EEServiceTest : FunSpec({
    test("authenticated envelope round-trips with a non-zero counter") {
        val sharedSecret = ByteArray(32) { index -> (index + 1).toByte() }
        val counter = 42
        val plaintext = "message from march 2 that must still decrypt later".toByteArray()

        val encrypted = E2EEService.encryptBytes(sharedSecret, plaintext, counter)
        val packed = E2EEService.packCiphertextWithNonceAndCounter(
            ciphertext = encrypted.ciphertext,
            nonce = encrypted.nonce,
            counter = counter,
        )

        val envelope = E2EEService.unpackMessageEnvelope(packed)
        envelope.counter shouldBeExactly counter
        envelope.nonce shouldBe encrypted.nonce

        val decrypted = E2EEService.decryptToBytes(
            sharedSecret = sharedSecret,
            ciphertext = envelope.ciphertext,
            nonce = requireNotNull(envelope.nonce),
            counter = envelope.counter,
        )

        decrypted.contentEquals(plaintext) shouldBe true
    }

    test("legacy packed messages still unpack with counter-only format") {
        val ciphertext = byteArrayOf(0x11, 0x22, 0x33, 0x44)
        val counter = 513

        val packed = E2EEService.packCiphertextWithCounter(ciphertext, counter)
        val envelope = E2EEService.unpackMessageEnvelope(packed)

        envelope.ciphertext.contentEquals(ciphertext) shouldBe true
        envelope.counter shouldBeExactly counter
        envelope.nonce.shouldBeNull()
        envelope.usesAead shouldBe false
    }

    test("authenticated envelope rejects truncated payloads") {
        val truncated = byteArrayOf(
            0x53,
            0x4D,
            0x31,
            0x04,
        ) + ByteArray(16) { 0x00 }

        shouldThrow<IllegalArgumentException> {
            E2EEService.unpackMessageEnvelope(truncated)
        }.message shouldBe "Unsupported nonce length: 4"
    }

    test("messages encrypted under different counters remain independently decryptable") {
        val sharedSecret = ByteArray(32) { index -> (index * 3 + 7).toByte() }
        val firstPlaintext = "march 2".toByteArray()
        val laterPlaintext = "march 26".toByteArray()

        val firstEncrypted = E2EEService.encryptBytes(sharedSecret, firstPlaintext, counter = 1)
        val laterEncrypted = E2EEService.encryptBytes(sharedSecret, laterPlaintext, counter = 9)

        val firstEnvelope = E2EEService.unpackMessageEnvelope(
            E2EEService.packCiphertextWithNonceAndCounter(
                ciphertext = firstEncrypted.ciphertext,
                nonce = firstEncrypted.nonce,
                counter = 1,
            ),
        )
        val laterEnvelope = E2EEService.unpackMessageEnvelope(
            E2EEService.packCiphertextWithNonceAndCounter(
                ciphertext = laterEncrypted.ciphertext,
                nonce = laterEncrypted.nonce,
                counter = 9,
            ),
        )

        E2EEService.decryptToBytes(
            sharedSecret = sharedSecret,
            ciphertext = firstEnvelope.ciphertext,
            nonce = requireNotNull(firstEnvelope.nonce),
            counter = firstEnvelope.counter,
        ).contentEquals(firstPlaintext) shouldBe true

        E2EEService.decryptToBytes(
            sharedSecret = sharedSecret,
            ciphertext = laterEnvelope.ciphertext,
            nonce = requireNotNull(laterEnvelope.nonce),
            counter = laterEnvelope.counter,
        ).contentEquals(laterPlaintext) shouldBe true
    }
})
