package dev.patrickgold.florisboard.secure.core.e2ee

import android.util.Base64
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.engines.ChaCha7539Engine
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom

object E2EEService {
    private val kdfInfoSharedSecret = "SecureMessaging_SharedSecret".toByteArray()
    private val kdfInfoMessageKey = "SecureMessaging_MessageKey".toByteArray()
    private const val keyLength = 32
    private const val nonceLength = 12
    private const val tagLength = 16
    private val random = SecureRandom()

    fun generateIdentityKeyPair(): IdentityKeyPair {
        val gen = Ed25519KeyPairGenerator()
        gen.init(Ed25519KeyGenerationParameters(random))
        val pair = gen.generateKeyPair()

        val priv = (pair.private as Ed25519PrivateKeyParameters).encoded
        val pub = (pair.public as Ed25519PublicKeyParameters).encoded
        return IdentityKeyPair(privateKey = priv, publicKey = pub)
    }

    fun generateSignedPreKey(keyId: Int, identityPrivateKey: ByteArray): SignedPreKey {
        val gen = X25519KeyPairGenerator()
        gen.init(X25519KeyGenerationParameters(random))
        val pair = gen.generateKeyPair()

        val priv = (pair.private as X25519PrivateKeyParameters).encoded
        val pub = (pair.public as X25519PublicKeyParameters).encoded
        val signature = ed25519Sign(identityPrivateKey, pub)

        return SignedPreKey(
            keyId = keyId,
            privateKey = priv,
            publicKey = pub,
            signature = signature,
        )
    }

    fun ed25519Sign(privateKey: ByteArray, data: ByteArray): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(privateKey, 0))
        signer.update(data, 0, data.size)
        return signer.generateSignature()
    }

    fun ed25519Verify(publicKey: ByteArray, data: ByteArray, signature: ByteArray): Boolean {
        return try {
            val verifier = Ed25519Signer()
            verifier.init(false, Ed25519PublicKeyParameters(publicKey, 0))
            verifier.update(data, 0, data.size)
            verifier.verifySignature(signature)
        } catch (_: Exception) {
            false
        }
    }

    fun x3dhInitiate(recipientSignedPreKeyPublic: ByteArray): X3DHResult {
        val gen = X25519KeyPairGenerator()
        gen.init(X25519KeyGenerationParameters(random))
        val ephPair = gen.generateKeyPair()

        val ephPriv = ephPair.private as X25519PrivateKeyParameters
        val ephPub = (ephPair.public as X25519PublicKeyParameters).encoded
        val dh1 = x25519DH(ephPriv, X25519PublicKeyParameters(recipientSignedPreKeyPublic, 0))
        val sharedSecret = hkdfDerive(dh1, kdfInfoSharedSecret, keyLength)

        return X3DHResult(sharedSecret = sharedSecret, ephemeralPublicKey = ephPub)
    }

    fun x3dhRespond(signedPreKeyPrivate: ByteArray, ephemeralPublicKey: ByteArray): ByteArray {
        val dh1 = x25519DH(
            X25519PrivateKeyParameters(signedPreKeyPrivate, 0),
            X25519PublicKeyParameters(ephemeralPublicKey, 0),
        )
        return hkdfDerive(dh1, kdfInfoSharedSecret, keyLength)
    }

    fun encryptMessage(sharedSecret: ByteArray, plaintext: String): EncryptedMessage {
        val messageKey = hkdfDerive(sharedSecret, kdfInfoMessageKey, keyLength)
        val nonce = ByteArray(nonceLength).also { random.nextBytes(it) }
        val plaintextBytes = plaintext.toByteArray(Charsets.UTF_8)
        val ciphertext = chacha20Poly1305Encrypt(messageKey, nonce, plaintextBytes)
        return EncryptedMessage(ciphertext = ciphertext, nonce = nonce)
    }

    fun encryptBytes(sharedSecret: ByteArray, payload: ByteArray): EncryptedMessage {
        val messageKey = hkdfDerive(sharedSecret, kdfInfoMessageKey, keyLength)
        val nonce = ByteArray(nonceLength).also { random.nextBytes(it) }
        val ciphertext = chacha20Poly1305Encrypt(messageKey, nonce, payload)
        return EncryptedMessage(ciphertext = ciphertext, nonce = nonce)
    }

    fun decryptMessage(sharedSecret: ByteArray, ciphertext: ByteArray, nonce: ByteArray): String {
        val messageKey = hkdfDerive(sharedSecret, kdfInfoMessageKey, keyLength)
        val plaintext = chacha20Poly1305Decrypt(messageKey, nonce, ciphertext)
        return String(plaintext, Charsets.UTF_8)
    }

    fun decryptToBytes(sharedSecret: ByteArray, ciphertext: ByteArray, nonce: ByteArray): ByteArray {
        val messageKey = hkdfDerive(sharedSecret, kdfInfoMessageKey, keyLength)
        return chacha20Poly1305Decrypt(messageKey, nonce, ciphertext)
    }

    fun toBase64(data: ByteArray): String =
        Base64.encodeToString(data, Base64.NO_WRAP)

    fun fromBase64(encoded: String): ByteArray =
        Base64.decode(encoded, Base64.NO_WRAP)

    private fun x25519DH(
        privateKey: X25519PrivateKeyParameters,
        publicKey: X25519PublicKeyParameters,
    ): ByteArray {
        val agreement = X25519Agreement()
        agreement.init(privateKey)
        val shared = ByteArray(agreement.agreementSize)
        agreement.calculateAgreement(publicKey, shared, 0)
        return shared
    }

    private fun hkdfDerive(ikm: ByteArray, info: ByteArray, length: Int, salt: ByteArray? = null): ByteArray {
        val params = HKDFParameters(ikm, salt, info)
        val hkdf = HKDFBytesGenerator(SHA256Digest())
        hkdf.init(params)
        val output = ByteArray(length)
        hkdf.generateBytes(output, 0, length)
        return output
    }

    private fun chacha20Poly1305Encrypt(key: ByteArray, nonce: ByteArray, plaintext: ByteArray): ByteArray {
        val cipher = ChaCha20Poly1305()
        cipher.init(true, AEADParameters(KeyParameter(key), tagLength * 8, nonce))
        val output = ByteArray(cipher.getOutputSize(plaintext.size))
        var len = cipher.processBytes(plaintext, 0, plaintext.size, output, 0)
        len += cipher.doFinal(output, len)
        return output.copyOf(len)
    }

    private fun chacha20Poly1305Decrypt(key: ByteArray, nonce: ByteArray, ciphertextWithTag: ByteArray): ByteArray {
        val cipher = ChaCha20Poly1305()
        cipher.init(false, AEADParameters(KeyParameter(key), tagLength * 8, nonce))
        val output = ByteArray(cipher.getOutputSize(ciphertextWithTag.size))
        var len = cipher.processBytes(ciphertextWithTag, 0, ciphertextWithTag.size, output, 0)
        try {
            len += cipher.doFinal(output, len)
        } catch (e: Exception) {
            throw IllegalArgumentException("Decryption failed - wrong key or tampered data", e)
        }
        return output.copyOf(len)
    }

    fun deriveMessageKey(sharedSecret: ByteArray, counter: Int): Pair<ByteArray, ByteArray> {
        require(sharedSecret.size == keyLength) { "Shared secret must be $keyLength bytes" }
        require(counter in 0..65535) { "Counter must be 16-bit (0-65535)" }

        val counterBytes = byteArrayOf((counter shr 8).toByte(), (counter and 0xFF).toByte())
        val derived = hkdfDerive(
            ikm = sharedSecret,
            info = kdfInfoMessageKey,
            length = 48,
            salt = counterBytes,
        )
        return derived.copyOfRange(0, 32) to derived.copyOfRange(32, 48)
    }

    fun chacha20Encrypt(plaintext: ByteArray, sharedSecret: ByteArray, counter: Int): ByteArray {
        val (key, nonce16) = deriveMessageKey(sharedSecret, counter)
        return bareChaCha20(key, nonce16, plaintext)
    }

    fun chacha20Decrypt(ciphertext: ByteArray, sharedSecret: ByteArray, counter: Int): ByteArray =
        chacha20Encrypt(ciphertext, sharedSecret, counter)

    fun packCiphertextWithCounter(ciphertext: ByteArray, counter: Int): ByteArray {
        require(counter in 0..65535) { "Counter must be 16-bit (0-65535)" }
        return ciphertext + byteArrayOf((counter shr 8).toByte(), (counter and 0xFF).toByte())
    }

    fun unpackCiphertextAndCounter(data: ByteArray): Pair<ByteArray, Int> {
        require(data.size >= 3) { "Data too short - need at least 1 byte ciphertext + 2 bytes counter" }
        val ciphertext = data.copyOfRange(0, data.size - 2)
        val counter = ((data[data.size - 2].toInt() and 0xFF) shl 8) or
            (data[data.size - 1].toInt() and 0xFF)
        return ciphertext to counter
    }

    private fun bareChaCha20(key: ByteArray, nonce16: ByteArray, input: ByteArray): ByteArray {
        val initialCounter = ByteBuffer.wrap(nonce16, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
        val iv12 = nonce16.copyOfRange(4, 16)

        val engine = ChaCha7539Engine()
        engine.init(true, ParametersWithIV(KeyParameter(key), iv12))

        val counterLong = initialCounter.toLong() and 0xFFFFFFFFL
        if (counterLong > 0) {
            engine.seekTo(counterLong * 64L)
        }

        val output = ByteArray(input.size)
        engine.processBytes(input, 0, input.size, output, 0)
        return output
    }
}