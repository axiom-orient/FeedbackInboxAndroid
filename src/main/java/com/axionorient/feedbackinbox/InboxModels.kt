package com.axionorient.feedbackinbox

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import java.net.URI
import java.util.UUID

internal val inboxJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
internal val uuidPattern = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
internal fun newId() = UUID.randomUUID().toString()
internal fun sameId(a: String, b: String) = a.equals(b, ignoreCase = true)

class InboxConfiguration(serverUrl: String) {
    val serverUrl: String
    init {
        val uri = try { URI(serverUrl) } catch (_: Exception) { throw InboxFailure.InvalidConfiguration }
        if (uri.scheme != "https" || uri.host.isNullOrEmpty() || uri.userInfo != null || uri.query != null || uri.fragment != null || uri.path !in listOf("", "/")) throw InboxFailure.InvalidConfiguration
        this.serverUrl = "https://${uri.host.lowercase()}" + if (uri.port == -1 || uri.port == 443) "" else ":${uri.port}"
    }
}

sealed class InboxFailure : Exception() {
    data object InvalidConfiguration : InboxFailure()
    data object InvalidMessage : InboxFailure()
    data object InvalidContext : InboxFailure()
    data object PayloadTooLarge : InboxFailure()
    data object InvalidCursor : InboxFailure()
    data object InvalidResponse : InboxFailure()
    data object CorruptStorage : InboxFailure()
    data object StorageUnavailable : InboxFailure()
    data object PendingConflict : InboxFailure()
    data object WriteInProgress : InboxFailure()
    data object Transport : InboxFailure()
    data object RejectionCleanupFailed : InboxFailure()
    data class Server(val status: Int, val code: String?) : InboxFailure()
    val definitive: Boolean get() = when (this) {
        InvalidConfiguration, InvalidMessage, InvalidContext, PayloadTooLarge, InvalidCursor -> true
        is Server -> mapOf("daily_thread_limit" to 429, "write_burst" to 429, "registration_burst" to 429,
            "thread_closed" to 409, "installation_blocked" to 403, "invalid_message" to 400,
            "payload_too_large" to 413, "invalid_client_context" to 400, "app_not_supported" to 400, "app_context_mismatch" to 400)[code] == status
        else -> false
    }
}
object InboxPolicy {
    const val maximumCharacters = 5_000
    const val maximumPayloadBytes = 24_000
    fun accepts(body: String) = body.isNotBlank() && body.codePointCount(0, body.length) <= maximumCharacters
    internal fun validAppId(value: String) = Regex("[A-Za-z0-9][A-Za-z0-9.-]{2,127}").matches(value)
}

@Serializable data class ClientContext(
    val schemaVersion: Int = 1, val appName: String? = null, val appID: String? = null,
    val appVersion: String? = null, val appBuild: String? = null, val osName: String? = null,
    val osVersion: String? = null, val deviceModel: String? = null, val language: String? = null,
) {
    internal fun valid(): Boolean {
        val fields = listOf(appName to 128, appID to 128, appVersion to 64, appBuild to 64, osName to 32, osVersion to 64, deviceModel to 128, language to 64)
        return schemaVersion == 1 && fields.all { (value, max) -> value == null || (value.isNotBlank() && value.codePointCount(0, value.length) <= max && value.none { it.code < 32 || it.code == 127 }) } && inboxJson.encodeToString(serializer(), this).toByteArray().size <= 2048
    }
}
@Serializable data class InboxThread(val id: String, val status: Status, val createdAt: Long, val updatedAt: Long, val preview: String,
    val lastSender: InboxMessage.Sender? = null, val lastPreview: String? = null) {
    @Serializable enum class Status { @SerialName("open") Open, @SerialName("closed") Closed }
}
@Serializable data class InboxMessage(val id: String, val sequence: Int, val sender: Sender, val body: String, val createdAt: Long, val clientContext: ClientContext? = null) {
    @Serializable enum class Sender { @SerialName("user") User, @SerialName("operator") Operator }
}
@Serializable data class ThreadPage(val threads: List<InboxThread>, val nextBefore: String? = null)
@Serializable data class Conversation(val thread: InboxThread, val messages: List<InboxMessage>, val nextAfter: Int? = null, val previousBefore: Int? = null)
@Serializable data class PendingSend(val kind: Kind, val threadID: String, val messageID: String, val body: String, val clientContext: ClientContext? = null) {
    @Serializable enum class Kind { @SerialName("newThread") NewThread, @SerialName("reply") Reply }
    fun matchesRetry(other: PendingSend) = kind == other.kind && sameId(threadID, other.threadID) && sameId(messageID, other.messageID) && body == other.body && (other.clientContext == null || clientContext == other.clientContext)
    internal fun valid() = uuidPattern.matches(threadID) && uuidPattern.matches(messageID) && InboxPolicy.accepts(body) && (clientContext == null || clientContext.valid())
    companion object {
        fun inquiry(body: String) = PendingSend(Kind.NewThread, newId(), newId(), body)
        fun reply(threadID: String, body: String) = PendingSend(Kind.Reply, threadID, newId(), body)
    }
}
data class SendReceipt(val thread: InboxThread?, val message: InboxMessage, val cleanupIssue: InboxFailure? = null)
data class InboxSnapshot(val page: ThreadPage, val pending: PendingSend?)
@Serializable internal data class Credential(val id: String, val secret: String)
@Serializable internal data class StoredInbox(val origin: String, val credential: Credential, val pending: PendingSend? = null)
@Serializable internal data class WireReceipt(val thread: InboxThread? = null, val message: InboxMessage)
