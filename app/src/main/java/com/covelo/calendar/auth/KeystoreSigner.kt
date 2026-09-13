package com.covelo.calendar.auth

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.interfaces.ECPublicKey

/**
 * Device identity key: a non-exportable P-256 keypair in the Android Keystore. Mirrors what
 * the web app does with `crypto.subtle.generateKey` + IndexedDB, except the private key here
 * never leaves secure hardware — there's no equivalent of losing IndexedDB, only losing the
 * device itself, in which case re-enrollment is the recovery path (see handoff doc §7).
 */
object KeystoreSigner {
    private const val KEYSTORE_ALIAS = "calendar_device_key"
    private const val PROVIDER = "AndroidKeyStore"
    private const val FIELD_SIZE_BYTES = 32 // P-256 coordinate width

    private fun keyStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    fun hasKey(): Boolean = keyStore().containsAlias(KEYSTORE_ALIAS)

    /** Generates the device keypair if it doesn't already exist. Returns its public key as a JWK. */
    fun ensureKeyPair(): JSONObject {
        val ks = keyStore()
        if (!ks.containsAlias(KEYSTORE_ALIAS)) {
            val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER)
            val specBuilder = KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            )
                .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                try {
                    specBuilder.setIsStrongBoxBacked(true)
                    generator.initialize(specBuilder.build())
                    generator.generateKeyPair()
                } catch (e: Exception) {
                    // StrongBox unavailable on this device — fall back to the normal keystore.
                    specBuilder.setIsStrongBoxBacked(false)
                    generator.initialize(specBuilder.build())
                    generator.generateKeyPair()
                }
            } else {
                generator.initialize(specBuilder.build())
                generator.generateKeyPair()
            }
        }
        return publicKeyJwk()
    }

    private fun publicKeyJwk(): JSONObject {
        val entry = keyStore().getCertificate(KEYSTORE_ALIAS)
        val publicKey = entry.publicKey as ECPublicKey
        val x = unsignedFixedWidth(publicKey.w.affineX)
        val y = unsignedFixedWidth(publicKey.w.affineY)
        return JSONObject().apply {
            put("kty", "EC")
            put("crv", "P-256")
            put("x", base64Url(x))
            put("y", base64Url(y))
        }
    }

    /** Signs `message` and returns a base64url-encoded raw (P1363, r||s) ECDSA signature —
     * the format WebCrypto's `crypto.subtle.verify` expects, not Java's default DER encoding. */
    fun signToBase64Url(message: ByteArray): String {
        val ks = keyStore()
        val privateKey = ks.getKey(KEYSTORE_ALIAS, null) as java.security.PrivateKey
        val signature = Signature.getInstance("SHA256withECDSA").apply {
            initSign(privateKey)
            update(message)
        }.sign()
        return base64Url(derToP1363(signature))
    }

    private fun unsignedFixedWidth(value: BigInteger): ByteArray {
        val raw = value.toByteArray() // big-endian, may have a leading 0x00 sign byte or be short
        return when {
            raw.size == FIELD_SIZE_BYTES -> raw
            raw.size > FIELD_SIZE_BYTES -> raw.copyOfRange(raw.size - FIELD_SIZE_BYTES, raw.size)
            else -> ByteArray(FIELD_SIZE_BYTES - raw.size) + raw
        }
    }

    /** Converts a JCA DER-encoded ECDSA signature (SEQUENCE of two INTEGERs) into fixed-width r||s. */
    private fun derToP1363(der: ByteArray): ByteArray {
        var offset = 2 // skip SEQUENCE tag + length
        fun readInt(): BigInteger {
            require(der[offset] == 0x02.toByte()) { "Expected INTEGER tag in DER signature" }
            offset++
            val len = der[offset].toInt() and 0xFF
            offset++
            val bytes = der.copyOfRange(offset, offset + len)
            offset += len
            return BigInteger(bytes)
        }
        val r = readInt()
        val s = readInt()
        return unsignedFixedWidth(r) + unsignedFixedWidth(s)
    }

    private fun base64Url(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
}
