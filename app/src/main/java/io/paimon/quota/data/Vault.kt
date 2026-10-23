package io.paimon.quota.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * API Key 加密存储。
 *
 * - 密钥：Android Keystore 内 AES-256（alias = cpa_vault_aes），私钥不可导出；
 *   设备有 TEE/StrongBox 时由硬件保护，应用自身也读不到明文密钥。
 * - 算法：AES/GCM/NoPadding，每次随机 IV，存 Base64(iv ‖ ciphertext)，带 "v1:" 前缀。
 * - 兼容：无前缀视为历史明文，读取时自动加密回写（见 Prefs.loadProviders）。
 */
object Vault {

    private const val PROVIDER = "AndroidKeyStore"
    private const val ALIAS = "cpa_vault_aes"
    private const val PREFIX = "v1:"
    private const val IV_LEN = 12
    private const val TAG_BITS = 128

    @Volatile
    private var cached: SecretKey? = null

    private fun secretKey(): SecretKey {
        cached?.let { return it }
        val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
        val existing = (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
        val key = existing ?: run {
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
            gen.init(
                KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            gen.generateKey()
        }
        cached = key
        return key
    }

    fun encrypt(plain: String): String {
        if (plain.isBlank()) return ""
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            PREFIX + Base64.encodeToString(cipher.iv + ct, Base64.NO_WRAP)
        }.getOrDefault("")
    }

    /** 返回明文；解不开时返回空串，绝不把密文当明文用。 */
    fun decrypt(stored: String): String {
        if (stored.isBlank()) return ""
        if (!stored.startsWith(PREFIX)) return stored // 旧版明文
        return runCatching {
            val raw = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
            if (raw.size <= IV_LEN) return ""
            val iv = raw.copyOfRange(0, IV_LEN)
            val ct = raw.copyOfRange(IV_LEN, raw.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        }.getOrDefault("")
    }

    fun isEncrypted(stored: String): Boolean = stored.startsWith(PREFIX)
}
