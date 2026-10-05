package com.axionorient.feedbackinbox

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.io.RandomAccessFile
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal interface InboxStorage {
    fun credential(): Credential
    fun pending(): PendingSend?
    fun admit(candidate: PendingSend, context: ClientContext): PendingSend
    fun clear(candidate: PendingSend)
}
internal class EncryptedInboxStorage(context: Context, private val origin: String, suffix: String = "") : InboxStorage {
    private val appID = context.packageName
    private val directory = File(context.noBackupFilesDir, "feedback-inbox$suffix")
    private val file = AtomicFile(File(directory, "state.bin"))
    private val alias = "$appID.feedback.aes.v1$suffix"
    private val aad = "$appID|$origin".toByteArray(Charsets.UTF_8)
    companion object { private val monitor = Any() }
    override fun credential() = transaction { val record = read() ?: create(); record.credential }
    override fun pending() = transaction { read()?.pending }
    override fun admit(candidate: PendingSend, context: ClientContext) = transaction {
        val record = read() ?: create()
        record.pending?.let { if (!it.matchesRetry(candidate)) throw InboxFailure.PendingConflict; return@transaction it }
        val frozen = if (candidate.clientContext == null) candidate.copy(clientContext = context) else candidate
        write(record.copy(pending = frozen)); frozen
    }
    override fun clear(candidate: PendingSend) = transaction {
        val record = read() ?: return@transaction
        if (record.pending == candidate) write(record.copy(pending = null))
    }
    private fun create(): StoredInbox {
        val secret = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val record = StoredInbox(origin, Credential(newId(), secret))
        write(record); return record
    }
    private fun <T> transaction(work: () -> T): T = synchronized(monitor) {
        try {
            if (!directory.isDirectory && !directory.mkdirs()) throw InboxFailure.StorageUnavailable
            RandomAccessFile(File(directory, "state.lock"), "rw").use { lockFile -> lockFile.channel.lock().use { work() } }
        } catch (failure: InboxFailure) { throw failure } catch (_: Exception) { throw InboxFailure.StorageUnavailable }
    }
    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (store.containsAlias(alias)) return store.getKey(alias, null) as? SecretKey ?: throw InboxFailure.CorruptStorage
        if (!create) throw InboxFailure.CorruptStorage
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
    private fun read(): StoredInbox? {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
        try {
            val bytes = file.openRead().use { input -> if (input.channel.size() !in 32L..65536L) throw InboxFailure.CorruptStorage; input.readBytes() }
            if (bytes.size !in 32..65536 || bytes.take(4).toByteArray().decodeToString() != "FBI1") throw InboxFailure.CorruptStorage
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, bytes.copyOfRange(4, 16))); cipher.updateAAD(aad)
            val record = inboxJson.decodeFromString<StoredInbox>(cipher.doFinal(bytes.copyOfRange(16, bytes.size)).decodeToString())
            if (record.origin != origin || !uuidPattern.matches(record.credential.id) || !Regex("[0-9a-f]{64}").matches(record.credential.secret) || record.pending?.valid() == false) throw InboxFailure.CorruptStorage
            return record
        } catch (failure: InboxFailure) { throw failure } catch (_: Exception) { throw InboxFailure.CorruptStorage }
    }
    private fun write(record: StoredInbox) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key(true)); cipher.updateAAD(aad)
        val bytes = "FBI1".toByteArray() + cipher.iv + cipher.doFinal(inboxJson.encodeToString(StoredInbox.serializer(), record).toByteArray())
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) } catch (e: Exception) { file.failWrite(stream); throw e }
    }
}
