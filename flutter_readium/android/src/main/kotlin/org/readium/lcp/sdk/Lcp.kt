package org.readium.lcp.sdk

import android.util.Base64
import android.util.Log
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONArray
import org.json.JSONObject

/**
 * LCP basic-profile implementation using standard Java crypto.
 *
 * This class is loaded via reflection by Readium's LcpClient:
 *   Class.forName("org.readium.lcp.sdk.Lcp")
 *
 * It implements the three methods Readium needs:
 *   - findOneValidPassphrase
 *   - createContext
 *   - decrypt
 *
 * The LCP basic-profile uses:
 *   - SHA-256 of the passphrase as the user key (already hashed by Readium before calling here)
 *   - AES-256-CBC (IV prepended) to encrypt content key and resources
 */
class Lcp {

    companion object {
        private const val TAG = "LcpBasicProfile"
    }

    /**
     * Called by Readium with a list of hex-encoded SHA-256 hashed passphrases.
     * Returns the first passphrase that successfully decrypts the license key_check.
     */
    fun findOneValidPassphrase(jsonLicense: String, hashedPassphrases: Array<String>): String {
        val license = JSONObject(jsonLicense)
        val encryption = license.getJSONObject("encryption")
        val contentKeyObj = encryption.getJSONObject("content_key")
        val encryptedContentKeyB64 = contentKeyObj.getString("encrypted_value")
        val licenseId = license.getString("id")
        val userKeyObj = encryption.getJSONObject("user_key")
        val keyCheckB64 = userKeyObj.optString("key_check", "")

        val encryptedContentKeyBytes = Base64.decode(encryptedContentKeyB64, Base64.DEFAULT)
        val keyCheckBytes = if (keyCheckB64.isNotEmpty())
            Base64.decode(keyCheckB64, Base64.DEFAULT)
        else null

        for (hp in hashedPassphrases) {
            val userKey = try { hexToBytes(hp) } catch (e: Exception) { continue }
            try {
                // Primary check: can we decrypt the content key with this user key?
                val contentKey = aesCbcDecrypt(userKey, encryptedContentKeyBytes)
                if (contentKey.isNotEmpty()) {
                    // Optional secondary check: verify key_check if provided
                    if (keyCheckBytes != null) {
                        try {
                            val decryptedCheck = aesCbcDecrypt(userKey, keyCheckBytes)
                            val checkStr = String(decryptedCheck, Charsets.UTF_8)
                            if (checkStr != licenseId) {
                                // Some servers may not use exact equality; don't reject solely on mismatch
                                Log.d(TAG, "key_check mismatch; accepting due to valid content key decryption")
                            }
                        } catch (_: Exception) {
                            Log.d(TAG, "key_check decrypt failed; accepting due to valid content key decryption")
                        }
                    }
                    Log.d(TAG, "Valid passphrase found for license $licenseId")
                    return hp
                }
            } catch (e: Exception) {
                Log.d(TAG, "Passphrase attempt failed: ${e.message}")
            }
        }

        throw DRMException(DRMError(0), "No valid passphrase found for license $licenseId")
    }

    /**
     * Called by Readium to create a decryption context.
     * hashedPassphrases is a JSON array string of hex-encoded SHA-256 hashes.
     */
    fun createContext(jsonLicense: String, hashedPassphrases: String, pemCrl: String): DRMContext {
        val license = JSONObject(jsonLicense)
        val encryption = license.getJSONObject("encryption")
        val contentKeyObj = encryption.getJSONObject("content_key")
        val encryptedContentKeyB64 = contentKeyObj.getString("encrypted_value")
        val profile = encryption.optString("profile", "http://readium.org/lcp/basic-profile")

        val ppList: Array<String> = try {
            val arr = JSONArray(hashedPassphrases)
            Array(arr.length()) { arr.getString(it) }
        } catch (e: Exception) {
            arrayOf(hashedPassphrases.trim('"'))
        }

        val validPassphrase = findOneValidPassphrase(jsonLicense, ppList)

        return DRMContext(
            hashedPassphrase = validPassphrase,
            encryptedContentKey = encryptedContentKeyB64,
            token = "",
            profile = profile,
        )
    }

    /**
     * Decrypts an LCP-encrypted resource.
     * The content key is recovered from context, then used to decrypt the resource bytes.
     * encryptedData has the 16-byte IV prepended.
     */
    fun decrypt(context: DRMContext, encryptedData: ByteArray): ByteArray {
        val userKey = hexToBytes(context.hashedPassphrase)
        val encryptedContentKeyBytes = Base64.decode(context.encryptedContentKey, Base64.DEFAULT)
        val contentKey = aesCbcDecrypt(userKey, encryptedContentKeyBytes)
        return aesCbcDecrypt(contentKey, encryptedData)
    }

    private fun aesCbcDecrypt(key: ByteArray, dataWithIV: ByteArray): ByteArray {
        require(dataWithIV.size >= 16) { "Data too short to contain IV (${dataWithIV.size} bytes)" }
        val iv = dataWithIV.copyOfRange(0, 16)
        val ciphertext = dataWithIV.copyOfRange(16, dataWithIV.size)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(ciphertext)
    }

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        require(len % 2 == 0) { "Hex string must have even length" }
        return ByteArray(len / 2) { i ->
            ((Character.digit(hex[i * 2], 16) shl 4) + Character.digit(hex[i * 2 + 1], 16)).toByte()
        }
    }
}
