package dev.patrickgold.florisboard.secure.core.e2ee

data class IdentityKeyPair(
    val privateKey: ByteArray,
    val publicKey: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is IdentityKeyPair) return false
        return publicKey.contentEquals(other.publicKey)
    }

    override fun hashCode(): Int = publicKey.contentHashCode()
}

data class SignedPreKey(
    val keyId: Int,
    val privateKey: ByteArray,
    val publicKey: ByteArray,
    val signature: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SignedPreKey) return false
        return keyId == other.keyId && publicKey.contentEquals(other.publicKey)
    }

    override fun hashCode(): Int = 31 * keyId + publicKey.contentHashCode()
}

data class X3DHResult(
    val sharedSecret: ByteArray,
    val ephemeralPublicKey: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is X3DHResult) return false
        return sharedSecret.contentEquals(other.sharedSecret)
    }

    override fun hashCode(): Int = sharedSecret.contentHashCode()
}

data class EncryptedMessage(
    val ciphertext: ByteArray,
    val nonce: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EncryptedMessage) return false
        return ciphertext.contentEquals(other.ciphertext) && nonce.contentEquals(other.nonce)
    }

    override fun hashCode(): Int = 31 * ciphertext.contentHashCode() + nonce.contentHashCode()
}

data class PackedMessageEnvelope(
    val ciphertext: ByteArray,
    val counter: Int,
    val nonce: ByteArray?,
) {
    val usesAead: Boolean
        get() = nonce != null
}
