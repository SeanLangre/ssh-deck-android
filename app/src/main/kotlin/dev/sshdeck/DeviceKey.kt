package dev.sshdeck

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.sshdeck.core.authorizedKeysLine
import java.io.File
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * This device's SSH identity: an ECDSA P-256 key pair generated on the phone, used for
 * every server. The private key file is encrypted with an AES key that lives in the
 * Android Keystore, so the file alone is useless off the device.
 */
class DeviceKey(context: Context) {
    private val privateFile = File(context.filesDir, "id_ecdsa.enc")
    private val publicFile = File(context.filesDir, "id_ecdsa.pub.der")

    @Volatile private var cached: KeyPair? = null

    @Synchronized
    fun keyPair(): KeyPair {
        cached?.let { return it }
        val pair = if (privateFile.exists() && publicFile.exists()) load() else generate()
        cached = pair
        return pair
    }

    /** The line to append to the server's ~/.ssh/authorized_keys. */
    fun authorizedKeysLine(): String = authorizedKeysLine(keyPair().public, "ssh-deck@android")

    private fun generate(): KeyPair {
        val pair = KeyPairGenerator.getInstance("EC")
            .apply { initialize(ECGenParameterSpec("secp256r1")) }
            .generateKeyPair()
        val cipher = Cipher.getInstance(AES_MODE)
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey())
        val encrypted = cipher.doFinal(pair.private.encoded)
        privateFile.writeBytes(byteArrayOf(cipher.iv.size.toByte()) + cipher.iv + encrypted)
        publicFile.writeBytes(pair.public.encoded)
        return pair
    }

    private fun load(): KeyPair {
        val data = privateFile.readBytes()
        val ivLength = data[0].toInt()
        val iv = data.copyOfRange(1, 1 + ivLength)
        val cipher = Cipher.getInstance(AES_MODE)
        cipher.init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(128, iv))
        val privateBytes = cipher.doFinal(data, 1 + ivLength, data.size - 1 - ivLength)
        val factory = KeyFactory.getInstance("EC")
        return KeyPair(
            factory.generatePublic(X509EncodedKeySpec(publicFile.readBytes())),
            factory.generatePrivate(PKCS8EncodedKeySpec(privateBytes)),
        )
    }

    private fun wrappingKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEYSTORE_ALIAS, null) as SecretKey?)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(KEYSTORE_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEYSTORE_ALIAS = "ssh_deck_identity_wrapper"
        const val AES_MODE = "AES/GCM/NoPadding"
    }
}
