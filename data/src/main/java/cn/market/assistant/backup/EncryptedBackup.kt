package cn.market.assistant.backup

import org.json.JSONException
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Password-protected, portable backup envelope. The password is never persisted. */
object EncryptedBackup {
    private const val FORMAT = "market-ledger-encrypted-backup"
    private const val VERSION = 1
    private const val KDF = "PBKDF2WithHmacSHA256"
    private const val CIPHER = "AES/GCM/NoPadding"
    private const val ITERATIONS = 310_000
    private const val MIN_ITERATIONS = 210_000
    private const val MAX_ITERATIONS = 400_000
    private const val SALT_BYTES = 16
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private const val KEY_BITS = 256
    private const val MAX_BACKUP_BYTES = 64 * 1024 * 1024
    const val MAX_FILE_BYTES = 128 * 1024 * 1024

    fun isEncrypted(payload: String): Boolean = runCatching {
        JSONObject(payload).optString("format") == FORMAT
    }.getOrDefault(false)

    fun encrypt(plaintext: String, password: CharArray): String {
        val cleartext = plaintext.toByteArray(StandardCharsets.UTF_8)
        return try {
            require(cleartext.size <= MAX_BACKUP_BYTES) { "账本备份超过 64 MB，暂不能导出" }
            require(password.size >= 12) { "备份密码至少需要 12 个字符" }
            val random = SecureRandom()
            val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
            val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
            val key = deriveKey(password, salt, ITERATIONS)
            try {
                val cipher = Cipher.getInstance(CIPHER)
                cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
                cipher.updateAAD(aad(ITERATIONS))
                val ciphertext = cipher.doFinal(cleartext)
                JSONObject()
                    .put("format", FORMAT)
                    .put("version", VERSION)
                    .put("kdf", KDF)
                    .put("iterations", ITERATIONS)
                    .put("cipher", CIPHER)
                    .put("key_bits", KEY_BITS)
                    .put("salt", encode(salt))
                    .put("nonce", encode(nonce))
                    .put("ciphertext", encode(ciphertext))
                    .toString()
            } finally {
                key.fill(0)
            }
        } finally {
            cleartext.fill(0)
            password.fill('\u0000')
        }
    }

    fun decrypt(payload: String, password: CharArray): String {
        return try {
            require(password.size >= 12) { "备份密码至少需要 12 个字符" }
            require(payload.length <= MAX_FILE_BYTES) { "备份文件超过允许大小" }
            val root = try {
                JSONObject(payload)
            } catch (error: JSONException) {
                throw IllegalArgumentException("备份文件格式无效", error)
            }
            require(root.optString("format") == FORMAT) { "文件不是加密账本备份" }
            require(root.optInt("version") == VERSION) { "加密备份版本不受支持" }
            require(root.optString("kdf") == KDF && root.optString("cipher") == CIPHER) { "加密参数不受支持" }
            require(root.optInt("key_bits") == KEY_BITS) { "加密密钥长度不受支持" }
            val iterations = root.optInt("iterations")
            require(iterations in MIN_ITERATIONS..MAX_ITERATIONS) { "备份密钥参数无效" }
            val salt = decode(root.optString("salt"))
            val nonce = decode(root.optString("nonce"))
            val ciphertext = decode(root.optString("ciphertext"))
            require(salt.size == SALT_BYTES && nonce.size == NONCE_BYTES) { "备份加密头无效" }
            require(ciphertext.size in 16..(MAX_BACKUP_BYTES + 16)) { "备份内容大小无效" }
            val key = deriveKey(password, salt, iterations)
            try {
                val cipher = Cipher.getInstance(CIPHER)
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
                cipher.updateAAD(aad(iterations))
                val cleartext = cipher.doFinal(ciphertext)
                try {
                    String(cleartext, StandardCharsets.UTF_8)
                } finally {
                    cleartext.fill(0)
                }
            } catch (error: AEADBadTagException) {
                throw IllegalArgumentException("密码错误或备份文件已损坏", error)
            } finally {
                key.fill(0)
            }
        } finally {
            password.fill('\u0000')
        }
    }

    private fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password, salt, iterations, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance(KDF).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun aad(iterations: Int): ByteArray = "$FORMAT|$VERSION|$KDF|$CIPHER|$KEY_BITS|$iterations"
        .toByteArray(StandardCharsets.UTF_8)

    private fun encode(value: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value)

    private fun decode(value: String): ByteArray = try {
        Base64.getUrlDecoder().decode(value)
    } catch (error: IllegalArgumentException) {
        throw IllegalArgumentException("备份加密内容格式无效", error)
    }
}
