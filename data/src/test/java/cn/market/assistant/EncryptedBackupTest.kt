package cn.market.assistant

import cn.market.assistant.backup.EncryptedBackup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class EncryptedBackupTest {
    private val plaintext = """{"format":"market-ledger-backup","version":7,"transactions":[]}"""
    private val password = "correct horse battery staple".toCharArray()

    @Test
    fun roundTripsAndClearsPasswordArrays() {
        val encryptPassword = "correct horse battery staple".toCharArray()
        val payload = EncryptedBackup.encrypt(plaintext, encryptPassword)
        assertTrue(EncryptedBackup.isEncrypted(payload))
        assertTrue(encryptPassword.all { it == '\u0000' })

        val decryptPassword = "correct horse battery staple".toCharArray()
        assertEquals(plaintext, EncryptedBackup.decrypt(payload, decryptPassword))
        assertTrue(decryptPassword.all { it == '\u0000' })
    }

    @Test
    fun wrongPasswordFailsWithoutLeakingPlaintext() {
        val payload = EncryptedBackup.encrypt(plaintext, password.copyOf())
        val error = assertThrows(IllegalArgumentException::class.java) {
            EncryptedBackup.decrypt(payload, "wrong password 123".toCharArray())
        }
        assertTrue(!error.message.orEmpty().contains(plaintext))
    }

    @Test
    fun shortPasswordIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            EncryptedBackup.encrypt(plaintext, "short".toCharArray())
        }
        assertThrows(IllegalArgumentException::class.java) {
            EncryptedBackup.decrypt("{}", "short".toCharArray())
        }
    }

    @Test
    fun tamperedCiphertextIsRejected() {
        val payload = EncryptedBackup.encrypt(plaintext, password.copyOf())
        val tampered = payload.dropLast(2) + "AA"
        assertThrows(IllegalArgumentException::class.java) {
            EncryptedBackup.decrypt(tampered, password.copyOf())
        }
    }
}
