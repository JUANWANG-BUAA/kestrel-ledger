package cn.market.assistant

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import net.zetetic.database.DatabaseErrorHandler
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Owns the local SQLCipher key and performs a recoverable one-time plaintext migration. */
internal object LedgerDatabaseEncryption {
    const val DATABASE_NAME = "market_ledger.db"

    private const val PREFS_NAME = "ledger_database_key"
    private const val WRAPPED_KEY_NAME = "wrapped_secret_v1"
    private const val KEY_ALIAS = "market_ledger_key_wrapper_v1"
    private const val WRAP_CIPHER = "AES/GCM/NoPadding"
    private const val WRAP_AAD = "market-ledger/database-key/v1"
    private const val WRAP_IV_BYTES = 12
    private const val SECRET_BYTES = 32
    private const val SQLITE_HEADER = "SQLite format 3\u0000"
    private const val TEMP_SUFFIX = ".sqlcipher-migration.tmp"
    private const val RECOVERY_SUFFIX = ".plaintext-recovery"
    private const val FAILED_SUFFIX = ".unreadable-preserved"

    private val lock = Any()
    private var sqlCipherLoaded = false
    private val preserveOnCorruption = DatabaseErrorHandler { _, _ ->
        // Keep the original database in place so a failed open cannot silently erase user data.
    }

    /** Returns the passphrase bytes expected by SQLCipher after preparing the on-disk database. */
    fun prepareAndGetPassphrase(context: Context): ByteArray = synchronized(lock) {
        loadSqlCipher()
        val appContext = context.applicationContext
        val databaseFile = appContext.getDatabasePath(DATABASE_NAME)
        databaseFile.parentFile?.let { parent ->
            if (!parent.exists() && !parent.mkdirs()) throw IOException("无法创建账本数据库目录")
        }

        val secret = loadOrCreateSecret(appContext, databaseFile)
        val passphrase = Base64.encodeToString(secret, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        secret.fill(0)
        try {
            recoverOrMigrate(databaseFile, passphrase)
            passphrase.toByteArray(StandardCharsets.UTF_8)
        } finally {
            // Strings cannot be reliably wiped, but the binary key material is cleared above.
        }
    }

    internal fun errorHandler(): DatabaseErrorHandler = preserveOnCorruption

    internal fun <T> withInitializationLock(block: () -> T): T = synchronized(lock, block)

    private fun loadSqlCipher() {
        if (sqlCipherLoaded) return
        System.loadLibrary("sqlcipher")
        sqlCipherLoaded = true
    }

    private fun loadOrCreateSecret(context: Context, databaseFile: File): ByteArray {
        val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val wrappedValue = preferences.getString(WRAPPED_KEY_NAME, null)
        if (wrappedValue != null) {
            val key = getWrappingKey(createIfMissing = false)
                ?: throw IOException("本机账本密钥不可用；账本文件未修改")
            return runCatching { unwrapSecret(wrappedValue, key) }
                .getOrElse { throw IOException("无法解锁本机账本密钥；账本文件未修改", it) }
        }

        if (databaseFile.exists() && databaseFile.length() > 0 && !isPlaintextSqlite(databaseFile)) {
            throw IOException("账本已加密但本机密钥记录缺失；为保护数据，未重建密钥或覆盖账本")
        }

        val key = getWrappingKey(createIfMissing = true)
            ?: throw IOException("无法创建本机账本密钥")
        val secret = ByteArray(SECRET_BYTES).also(SecureRandom()::nextBytes)
        val wrapped = try {
            wrapSecret(secret, key)
        } catch (error: Exception) {
            secret.fill(0)
            throw IOException("无法安全保存本机账本密钥", error)
        }
        if (!preferences.edit().putString(WRAPPED_KEY_NAME, wrapped).commit()) {
            secret.fill(0)
            throw IOException("无法保存本机账本密钥；账本文件未修改")
        }
        return secret
    }

    private fun getWrappingKey(createIfMissing: Boolean): SecretKey? {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing
        if (!createIfMissing && keyStore.containsAlias(KEY_ALIAS)) {
            throw IOException("本机账本密钥不可用")
        }
        if (!createIfMissing) return null

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun wrapSecret(secret: ByteArray, key: SecretKey): String {
        val cipher = Cipher.getInstance(WRAP_CIPHER)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(WRAP_AAD.toByteArray(StandardCharsets.UTF_8))
        val encrypted = cipher.doFinal(secret)
        val record = cipher.iv + encrypted
        encrypted.fill(0)
        return Base64.encodeToString(record, Base64.NO_WRAP or Base64.NO_PADDING).also { record.fill(0) }
    }

    private fun unwrapSecret(recordValue: String, key: SecretKey): ByteArray {
        val record = Base64.decode(recordValue, Base64.NO_WRAP or Base64.NO_PADDING)
        try {
            require(record.size > WRAP_IV_BYTES + 16) { "本机账本密钥记录无效" }
            val iv = record.copyOfRange(0, WRAP_IV_BYTES)
            val ciphertext = record.copyOfRange(WRAP_IV_BYTES, record.size)
            try {
                val cipher = Cipher.getInstance(WRAP_CIPHER)
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
                cipher.updateAAD(WRAP_AAD.toByteArray(StandardCharsets.UTF_8))
                return cipher.doFinal(ciphertext).also { require(it.size == SECRET_BYTES) { "本机账本密钥长度无效" } }
            } finally {
                iv.fill(0)
                ciphertext.fill(0)
            }
        } finally {
            record.fill(0)
        }
    }

    private fun recoverOrMigrate(databaseFile: File, passphrase: String) {
        val temporaryFile = File(databaseFile.parentFile, databaseFile.name + TEMP_SUFFIX)
        val recoveryFile = File(databaseFile.parentFile, databaseFile.name + RECOVERY_SUFFIX)
        val failedFile = File(databaseFile.parentFile, databaseFile.name + FAILED_SUFFIX)

        if (!databaseFile.exists()) {
            if (!recoveryFile.exists() && !failedFile.exists() && hasSidecars(databaseFile)) {
                throw IOException("账本事务日志存在但主文件缺失；文件已保留")
            }
            if (failedFile.exists() && recoveryFile.exists() && hasSidecars(databaseFile)) {
                throw IOException("账本恢复日志归属不明；所有恢复文件均已保留")
            }
            when {
                failedFile.exists() -> moveSidecars(databaseFile, failedFile)
                recoveryFile.exists() -> moveSidecars(databaseFile, recoveryFile)
            }
            if (temporaryFile.exists() && isEncryptedDatabase(temporaryFile, passphrase)) {
                promoteWithoutOriginal(temporaryFile, databaseFile, recoveryFile, passphrase)
            } else if (recoveryFile.exists() && isPlaintextDatabase(recoveryFile)) {
                restoreRecovery(recoveryFile, databaseFile)
            } else if (recoveryFile.exists()) {
                throw IOException("账本恢复文件无法校验；文件已保留")
            } else if (temporaryFile.exists()) {
                throw IOException("账本加密迁移临时文件无法校验；原始账本文件未修改")
            } else if (failedFile.exists() && isEncryptedDatabase(failedFile, passphrase)) {
                if (!failedFile.renameTo(databaseFile)) throw IOException("无法恢复已校验的加密账本")
                restoreSidecars(failedFile, databaseFile)
            } else if (failedFile.exists() || hasSidecars(databaseFile)) {
                throw IOException("账本恢复文件仍在；文件已保留，未创建空账本")
            }
        }

        if (!databaseFile.exists()) return
        if (databaseFile.length() == 0L) {
            throw IOException("账本数据库文件为空；文件已保留，未创建空账本覆盖数据")
        }

        if (isPlaintextSqlite(databaseFile)) {
            if (!isPlaintextDatabase(databaseFile)) {
                throw IOException("旧账本完整性校验失败；原始文件已保留")
            }
            if (temporaryFile.exists() && isEncryptedDatabase(temporaryFile, passphrase)) {
                promoteWithOriginal(temporaryFile, databaseFile, recoveryFile, passphrase)
                return
            }
            if (!deleteFileAndSidecars(temporaryFile)) throw IOException("无法清理无效的迁移临时文件；旧账本未修改")
            migratePlaintextDatabase(databaseFile, temporaryFile, recoveryFile, passphrase)
            return
        }

        if (isEncryptedDatabase(databaseFile, passphrase)) {
            if (!deleteFileAndSidecars(temporaryFile)) throw IOException("无法清理迁移临时文件")
            if (recoveryFile.exists()) {
                if (!isPlaintextDatabase(recoveryFile)) throw IOException("明文恢复文件无法校验；文件已保留")
            }
            if (!deleteFileAndSidecars(recoveryFile)) throw IOException("加密账本可用，但旧明文恢复文件无法清理")
            return
        }

        if (recoveryFile.exists() && isPlaintextDatabase(recoveryFile)) {
            if (!failedFile.exists() && databaseFile.renameTo(failedFile)) {
                try {
                    moveSidecars(databaseFile, failedFile)
                    restoreRecovery(recoveryFile, databaseFile)
                    migratePlaintextDatabase(databaseFile, temporaryFile, recoveryFile, passphrase)
                    return
                } catch (error: Exception) {
                    if (!databaseFile.exists() && failedFile.exists()) {
                        failedFile.renameTo(databaseFile)
                        restoreSidecars(failedFile, databaseFile)
                    }
                    throw error
                }
            }
        }

        throw IOException("账本无法使用本机密钥打开；原始账本文件已保留，未创建空账本覆盖数据")
    }

    private fun migratePlaintextDatabase(
        sourceFile: File,
        temporaryFile: File,
        recoveryFile: File,
        passphrase: String
    ) {
        val source = SQLiteDatabase.openDatabase(
            sourceFile.absolutePath,
            ByteArray(0),
            null,
            SQLiteDatabase.OPEN_READWRITE,
            preserveOnCorruption,
            null
        )
        try {
            verifyIntegrity(source, "旧账本")
            val oldVersion = source.version
            val sourceObjects = schemaObjects(source, "main")
            val sourceRowCounts = tableRowCounts(source, "main")
            val sourceSequences = sqliteSequences(source, "main")
            val escapedPath = temporaryFile.absolutePath.replace("'", "''")
            val escapedKey = passphrase.replace("'", "''")
            // Pre-create the target file: some Android SQLCipher builds return SQLITE_CANTOPEN (ENOENT)
            // when ATTACH has to create the file itself on a freshly restored app data directory.
            if (temporaryFile.exists() && !temporaryFile.delete()) {
                throw IOException("无法清理旧的加密迁移临时文件")
            }
            if (!temporaryFile.createNewFile()) {
                throw IOException("无法创建加密迁移临时文件：${temporaryFile.absolutePath}")
            }
            android.util.Log.i(
                "LedgerMigration",
                "attach path=${temporaryFile.absolutePath} parent=${temporaryFile.parentFile?.absolutePath} " +
                    "parentExists=${temporaryFile.parentFile?.exists()} " +
                    "parentWritable=${temporaryFile.parentFile?.canWrite()} sourceLength=${sourceFile.length()}"
            )
            try {
                source.execSQL("ATTACH DATABASE '$escapedPath' AS encrypted KEY '$escapedKey'")
            } catch (error: Exception) {
                android.util.Log.e(
                    "LedgerMigration",
                    "ATTACH failed: ${error.message}; tempExists=${temporaryFile.exists()} " +
                        "tempLength=${temporaryFile.length()}"
                )
                throw error
            }
            try {
                source.rawQuery("SELECT sqlcipher_export('encrypted')", null).use { cursor ->
                    if (!cursor.moveToFirst()) throw IOException("SQLCipher 未能导出旧账本")
                }
                source.execSQL("PRAGMA encrypted.user_version = $oldVersion")
                if (schemaObjects(source, "encrypted") != sourceObjects) {
                    throw IOException("旧账本与加密账本的结构不一致")
                }
                if (tableRowCounts(source, "encrypted") != sourceRowCounts) {
                    throw IOException("旧账本与加密账本的记录数不一致")
                }
                if (sqliteSequences(source, "encrypted") != sourceSequences) {
                    source.execSQL("DELETE FROM encrypted.sqlite_sequence")
                    sourceSequences.forEach { (table, sequence) ->
                        source.execSQL(
                            "INSERT INTO encrypted.sqlite_sequence(name, seq) VALUES (?, ?)",
                            arrayOf<Any>(table, sequence)
                        )
                    }
                    if (sqliteSequences(source, "encrypted") != sourceSequences) {
                        throw IOException("旧账本与加密账本的流水序号不一致")
                    }
                }
            } finally {
                runCatching { source.execSQL("DETACH DATABASE encrypted") }
            }
        } finally {
            source.close()
        }

        syncFile(temporaryFile)
        if (!isEncryptedDatabase(temporaryFile, passphrase)) {
            throw IOException("新账本完整性校验失败；旧账本仍保留")
        }
        promoteWithOriginal(temporaryFile, sourceFile, recoveryFile, passphrase)
    }

    private fun promoteWithoutOriginal(
        temporaryFile: File,
        databaseFile: File,
        recoveryFile: File,
        passphrase: String
    ) {
        if (databaseFile.exists()) throw IOException("账本迁移恢复状态冲突；原始文件未修改")
        if (!temporaryFile.renameTo(databaseFile)) throw IOException("无法启用已校验的加密账本")
        try {
            moveSidecars(temporaryFile, databaseFile)
            if (!isEncryptedDatabase(databaseFile, passphrase)) throw IOException("恢复后的加密账本无法校验")
        } catch (error: Exception) {
            databaseFile.renameTo(temporaryFile)
            restoreSidecars(databaseFile, temporaryFile)
            throw IOException("恢复后的加密账本无法校验；恢复文件已保留", error)
        }
        if (recoveryFile.exists()) {
            if (!isPlaintextDatabase(recoveryFile)) throw IOException("旧明文恢复文件无法校验；文件已保留")
            if (!deleteFileAndSidecars(recoveryFile)) {
                throw IOException("加密账本已恢复，但旧明文账本清理失败")
            }
        }
    }

    private fun promoteWithOriginal(
        temporaryFile: File,
        databaseFile: File,
        recoveryFile: File,
        passphrase: String
    ) {
        if (!isPlaintextDatabase(databaseFile)) throw IOException("旧账本完整性校验失败；原始文件已保留")
        if (recoveryFile.exists() && !deleteFileAndSidecars(recoveryFile)) {
            throw IOException("旧明文恢复文件无法清理；账本文件未修改")
        }
        if (!databaseFile.renameTo(recoveryFile)) throw IOException("无法保留旧账本用于回滚")
        try {
            moveSidecars(databaseFile, recoveryFile)
            if (!temporaryFile.renameTo(databaseFile)) throw IOException("无法切换到加密账本")
            moveSidecars(temporaryFile, databaseFile)
            if (!isEncryptedDatabase(databaseFile, passphrase)) {
                throw IOException("加密账本切换后无法校验")
            }
        } catch (error: Exception) {
            if (databaseFile.exists()) databaseFile.renameTo(temporaryFile)
            restoreSidecars(databaseFile, temporaryFile)
            if (recoveryFile.exists()) recoveryFile.renameTo(databaseFile)
            restoreSidecars(recoveryFile, databaseFile)
            throw error
        }
        if (!deleteFileAndSidecars(recoveryFile)) {
            throw IOException("账本已加密，但旧明文账本清理失败；请重试启动以完成清理")
        }
    }

    private fun restoreRecovery(recoveryFile: File, databaseFile: File) {
        if (!recoveryFile.renameTo(databaseFile)) throw IOException("无法恢复旧账本；恢复文件仍保留")
        restoreSidecars(recoveryFile, databaseFile)
    }

    private fun moveSidecars(from: File, to: File) {
        val suffixes = listOf("-wal", "-shm", "-journal")
        val moved = mutableListOf<Pair<File, File>>()
        try {
            suffixes.forEach { suffix ->
                val source = File(from.path + suffix)
                if (!source.exists()) return@forEach
                val destination = File(to.path + suffix)
                if (destination.exists() || !source.renameTo(destination)) {
                    throw IOException("无法保留账本事务日志")
                }
                moved += source to destination
            }
        } catch (error: Exception) {
            moved.asReversed().forEach { (source, destination) -> destination.renameTo(source) }
            throw error
        }
    }

    private fun restoreSidecars(from: File, to: File) {
        listOf("-wal", "-shm", "-journal").forEach { suffix ->
            val source = File(from.path + suffix)
            val destination = File(to.path + suffix)
            if (source.exists() && !destination.exists()) source.renameTo(destination)
        }
    }

    private fun isPlaintextDatabase(file: File): Boolean = runCatching {
        val database = SQLiteDatabase.openDatabase(
            file.absolutePath,
            ByteArray(0),
            null,
            SQLiteDatabase.OPEN_READONLY,
            preserveOnCorruption,
            null
        )
        try {
            verifyIntegrity(database, "旧账本")
            true
        } finally {
            database.close()
        }
    }.getOrDefault(false)

    private fun isEncryptedDatabase(file: File, passphrase: String): Boolean {
        if (!file.exists() || file.length() == 0L) return false
        return runCatching {
            val password = passphrase.toByteArray(StandardCharsets.UTF_8)
            val database = try {
                SQLiteDatabase.openDatabase(
                    file.absolutePath,
                    password,
                    null,
                    SQLiteDatabase.OPEN_READONLY,
                    preserveOnCorruption,
                    null
                )
            } catch (error: Exception) {
                password.fill(0)
                throw error
            }
        try {
            verifyEncryptedIntegrity(database)
            true
        } finally {
            password.fill(0)
            database.close()
        }
        }.getOrDefault(false)
    }

    private fun verifyIntegrity(database: SQLiteDatabase, label: String) {
        database.rawQuery("PRAGMA integrity_check", null).use { cursor ->
            if (!cursor.moveToFirst() || cursor.getString(0) != "ok") {
                throw IOException("${label}完整性校验失败")
            }
        }
    }

    private fun verifyEncryptedIntegrity(database: SQLiteDatabase) {
        database.rawQuery("PRAGMA cipher_status", null).use { cursor ->
            if (!cursor.moveToFirst() || cursor.getInt(0) != 1) {
                throw IOException("数据库没有启用 SQLCipher 加密")
            }
        }
        verifyIntegrity(database, "加密账本")
    }

    private fun schemaObjects(database: SQLiteDatabase, schema: String): List<Pair<String, String>> {
        require(schema == "main" || schema == "encrypted")
        return buildList {
            database.rawQuery(
                "SELECT type, name FROM $schema.sqlite_master WHERE name NOT GLOB 'sqlite_*' ORDER BY type, name",
                null
            ).use { cursor ->
                while (cursor.moveToNext()) add(cursor.getString(0) to cursor.getString(1))
            }
        }
    }

    private fun tableRowCounts(database: SQLiteDatabase, schema: String): Map<String, Long> {
        require(schema == "main" || schema == "encrypted")
        val tables = database.rawQuery(
            "SELECT name FROM $schema.sqlite_master WHERE type = 'table' AND name NOT GLOB 'sqlite_*' ORDER BY name",
            null
        ).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        return tables.associateWith { table ->
            val quotedName = table.replace("\"", "\"\"")
            database.rawQuery("SELECT COUNT(*) FROM $schema.\"$quotedName\"", null).use { cursor ->
                if (!cursor.moveToFirst()) throw IOException("无法校验表 $table 的记录数")
                cursor.getLong(0)
            }
        }
    }

    private fun sqliteSequences(database: SQLiteDatabase, schema: String): List<Pair<String, Long>> {
        require(schema == "main" || schema == "encrypted")
        val hasSequenceTable = database.rawQuery(
            "SELECT 1 FROM $schema.sqlite_master WHERE type = 'table' AND name = 'sqlite_sequence' LIMIT 1",
            null
        ).use { it.moveToFirst() }
        if (!hasSequenceTable) return emptyList()
        return buildList {
            database.rawQuery("SELECT name, seq FROM $schema.sqlite_sequence ORDER BY name", null).use { cursor ->
                while (cursor.moveToNext()) add(cursor.getString(0) to cursor.getLong(1))
            }
        }
    }

    private fun isPlaintextSqlite(file: File): Boolean {
        if (!file.isFile || file.length() < SQLITE_HEADER.toByteArray(StandardCharsets.US_ASCII).size) return false
        val header = ByteArray(SQLITE_HEADER.length)
        return try {
            FileInputStream(file).use { input ->
                input.read(header) == header.size &&
                    header.contentEquals(SQLITE_HEADER.toByteArray(StandardCharsets.US_ASCII))
            }
        } finally {
            header.fill(0)
        }
    }

    private fun syncFile(file: File) {
        if (!file.exists()) throw IOException("加密账本临时文件未生成")
        java.io.RandomAccessFile(file, "rw").use { it.fd.sync() }
    }

    private fun deleteFileAndSidecars(file: File): Boolean {
        val files = listOf(file, File(file.path + "-wal"), File(file.path + "-shm"), File(file.path + "-journal"))
        var deleted = true
        files.forEach { candidate ->
            if (candidate.exists() && !candidate.delete()) deleted = false
        }
        return deleted
    }

    private fun hasSidecars(file: File): Boolean =
        listOf("-wal", "-shm", "-journal").any { File(file.path + it).exists() }
}
