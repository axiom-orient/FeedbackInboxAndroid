package com.axionorient.feedbackinbox

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** App-bound private inbox client. Keep one instance; retry an uncertain write with its exact PendingSend. */
class FeedbackInboxClient internal constructor(
    private val configuration: InboxConfiguration,
    val appID: String,
    private val storage: InboxStorage,
    private val capture: suspend () -> ClientContext,
    private val exchange: suspend (HttpRequest) -> HttpResponse,
) {
    constructor(context: Context, configuration: InboxConfiguration) : this(configuration, context.applicationContext.packageName,
        EncryptedInboxStorage(context.applicationContext, configuration.serverUrl),
        { captureContext(context.applicationContext) }, { UrlConnectionTransport.execute(it) })
    private val write = Mutex()
    @Volatile private var enrolled = false
    init { if (!InboxPolicy.validAppId(appID)) throw InboxFailure.InvalidConfiguration }

    suspend fun pendingSend(): PendingSend? = withContext(Dispatchers.IO) { storage.pending() }
    suspend fun inbox(): InboxSnapshot {
        val pending = pendingSend()
        return InboxSnapshot(list(), pending)
    }
    suspend fun list(before: String? = null): ThreadPage = request("threads", before?.let { "before=${encodeQuery(it)}" })
    suspend fun conversation(id: String, before: Int? = null): Conversation {
        if (!uuidPattern.matches(id) || before != null && before <= 0) throw InboxFailure.InvalidCursor
        val page: Conversation = request("threads/$id", if (before == null) "latest=1" else "before=$before")
        if (!sameId(page.thread.id, id) || !uuidPattern.matches(page.thread.id) || page.messages.any { !uuidPattern.matches(it.id) || it.sequence <= 0 }) throw InboxFailure.InvalidResponse
        return page
    }
    suspend fun send(candidate: PendingSend): SendReceipt {
        currentCoroutineContext().ensureActive()
        if (!candidate.valid()) throw InboxFailure.InvalidMessage
        if (!write.tryLock()) throw InboxFailure.WriteInProgress
        try {
            return withContext(Dispatchers.IO) {
                val previous = storage.pending()
                if (previous != null && !previous.matchesRetry(candidate)) throw InboxFailure.PendingConflict
                val context = candidate.clientContext ?: try { capture() } catch (e: CancellationException) { throw e } catch (_: Exception) { throw InboxFailure.InvalidContext }
                if (context.appID != appID || !context.valid()) throw InboxFailure.InvalidContext
                currentCoroutineContext().ensureActive()
                // From durable admission onward, cancellation is not evidence of remote rollback.
                withContext(NonCancellable) {
                    val frozen = storage.admit(candidate, context)
                    try {
                        val body = buildJsonObject {
                            if (frozen.kind == PendingSend.Kind.NewThread) { put("id", frozen.threadID); put("messageID", frozen.messageID) } else put("id", frozen.messageID)
                            put("body", frozen.body)
                            frozen.clientContext?.let { put("clientContext", inboxJson.encodeToJsonElement(it)) }
                        }.toString().toByteArray(Charsets.UTF_8)
                        if (body.size > InboxPolicy.maximumPayloadBytes) throw InboxFailure.PayloadTooLarge
                        val path = if (frozen.kind == PendingSend.Kind.NewThread) "threads" else "threads/${frozen.threadID}/messages"
                        val result: WireReceipt = request(path, body = body)
                        if (!sameId(result.message.id, frozen.messageID) || result.message.sender != InboxMessage.Sender.User || result.message.body != frozen.body || result.message.clientContext != frozen.clientContext || result.message.sequence <= 0 || frozen.kind == PendingSend.Kind.NewThread && result.thread?.id?.let { !sameId(it, frozen.threadID) } != false) throw InboxFailure.InvalidResponse
                        val issue = try { storage.clear(frozen); null } catch (failure: InboxFailure) { failure }
                        SendReceipt(result.thread, result.message, issue)
                    } catch (failure: InboxFailure) {
                        if (failure.definitive) try { storage.clear(frozen) } catch (_: InboxFailure) { throw InboxFailure.RejectionCleanupFailed }
                        throw failure
                    }
                }
            }
        } finally { write.unlock() }
    }
    private suspend inline fun <reified T> request(path: String, query: String? = null, body: ByteArray? = null): T = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        val credential = storage.credential()
        if (!enrolled) {
            val enrollment = inboxJson.encodeToString(Registration(credential.id, credential.secret, appID)).toByteArray()
            val registered: RegistrationReceipt = perform(HttpRequest("${configuration.serverUrl}/installations", enrollment))
            if (registered.id != credential.id) throw InboxFailure.InvalidResponse
            enrolled = true
        }
        perform(HttpRequest("${configuration.serverUrl}/$path" + (query?.let { "?$it" } ?: ""), body, "Bearer ${credential.id}:${credential.secret}"))
    }
    private suspend inline fun <reified T> perform(request: HttpRequest): T {
        val result = try { exchange(request) } catch (e: CancellationException) { throw e } catch (e: InboxFailure) { throw e } catch (_: Exception) { throw InboxFailure.Transport }
        if (result.status !in 200..299) {
            val code = try { inboxJson.parseToJsonElement(result.body.decodeToString()).jsonObject["error"]?.jsonPrimitive?.contentOrNull } catch (_: Exception) { null }
            throw InboxFailure.Server(result.status, code)
        }
        return try { inboxJson.decodeFromString<T>(result.body.decodeToString()) } catch (_: Exception) { throw InboxFailure.InvalidResponse }
    }
    @Serializable private data class Registration(val id: String, val secret: String, val appID: String)
    @Serializable private data class RegistrationReceipt(val id: String)
}
