package com.axionorient.feedbackinbox

sealed interface InboxRoute {
    data object List : InboxRoute
    data object Compose : InboxRoute
    data class Thread(val id: String) : InboxRoute
}
sealed interface InboxWork {
    data object Idle : InboxWork
    data class Reading(val id: String) : InboxWork
    data class Writing(val id: String) : InboxWork
}
enum class InboxAvailability { Initial, Ready, Blocked }
sealed interface InboxIssue {
    val failure: InboxFailure
    data class Read(override val failure: InboxFailure) : InboxIssue
    data class Send(override val failure: InboxFailure) : InboxIssue
}
data class InboxState(
    val visible: Boolean = false, val availability: InboxAvailability = InboxAvailability.Initial,
    val work: InboxWork = InboxWork.Idle, val route: InboxRoute = InboxRoute.List,
    val threads: List<InboxThread> = emptyList(), val nextBefore: String? = null,
    val conversation: Conversation? = null, val newDraft: String = "", val replyDrafts: Map<String, String> = emptyMap(),
    val pending: PendingSend? = null, val issue: InboxIssue? = null,
    val lastCommit: Pair<String, InboxMessage>? = null, val cleanupIssue: InboxFailure? = null,
) {
    val selectedId get() = (route as? InboxRoute.Thread)?.id
    val draft get() = pending?.body ?: when (route) { InboxRoute.Compose -> newDraft; is InboxRoute.Thread -> replyDrafts[route.id].orEmpty(); InboxRoute.List -> "" }
    val canSend get() = work == InboxWork.Idle && (pending != null || availability == InboxAvailability.Ready && InboxPolicy.accepts(draft) && (route == InboxRoute.Compose || conversation?.thread?.status == InboxThread.Status.Open))
}
sealed interface InboxAction {
    data object Appear : InboxAction
    data object Disappear : InboxAction
    data object Refresh : InboxAction
    data object Compose : InboxAction
    data object Back : InboxAction
    data object MoreThreads : InboxAction
    data object MoreMessages : InboxAction
    data object Send : InboxAction
    data class Open(val id: String) : InboxAction
    data class Edit(val body: String) : InboxAction
    data class Loaded(val id: String, val page: ThreadPage?, val pending: PendingSend?, val failure: InboxFailure?) : InboxAction
    data class ThreadsLoaded(val id: String, val page: ThreadPage?, val failure: InboxFailure?) : InboxAction
    data class ConversationLoaded(val id: String, val threadId: String, val append: Boolean, val page: Conversation?, val failure: InboxFailure?) : InboxAction
    data class Sent(val id: String, val request: PendingSend, val receipt: SendReceipt?, val failure: InboxFailure?) : InboxAction
}
sealed interface InboxCommand {
    data object CancelRead : InboxCommand
    data class Inbox(val id: String) : InboxCommand
    data class Threads(val id: String, val before: String) : InboxCommand
    data class ReadConversation(val id: String, val threadId: String, val before: Int?, val append: Boolean) : InboxCommand
    data class Write(val id: String, val pending: PendingSend) : InboxCommand
}
data class InboxTransition(val state: InboxState, val commands: List<InboxCommand> = emptyList())

/** Deterministic flow; injected IDs and explicit effects let consumers retain their existing state owner. */
object InboxFlow {
    fun reduce(state: InboxState, action: InboxAction, id: () -> String = ::newId): InboxTransition {
        var s = state
        fun done() = InboxTransition(s)
        fun refresh(): InboxTransition {
            if (s.work is InboxWork.Writing) return done()
            val request = id(); s = s.copy(work = InboxWork.Reading(request), issue = null)
            return InboxTransition(s, listOf(InboxCommand.CancelRead, s.selectedId?.let { InboxCommand.ReadConversation(request, it, null, false) } ?: InboxCommand.Inbox(request)))
        }
        fun restore(pending: PendingSend?) { s = s.copy(pending = pending); if (pending != null) s = s.copy(route = if (pending.kind == PendingSend.Kind.NewThread) InboxRoute.Compose else InboxRoute.Thread(pending.threadID)) }
        when (action) {
            InboxAction.Appear -> { s = s.copy(visible = true); return refresh() }
            InboxAction.Disappear -> return InboxTransition(s.copy(visible = false, work = if (s.work is InboxWork.Reading) InboxWork.Idle else s.work), listOf(InboxCommand.CancelRead))
            InboxAction.Refresh -> return refresh()
            InboxAction.Compose -> if (s.availability == InboxAvailability.Ready && s.pending == null && s.work == InboxWork.Idle) s = s.copy(route = InboxRoute.Compose, conversation = null, issue = null, cleanupIssue = null)
            InboxAction.Back -> if (s.pending == null && s.work !is InboxWork.Writing) { s = s.copy(route = InboxRoute.List, conversation = null, issue = null, cleanupIssue = null); return refresh() }
            is InboxAction.Open -> if (s.work !is InboxWork.Writing && (s.pending == null || s.pending?.threadID == action.id)) { s = s.copy(route = InboxRoute.Thread(action.id), conversation = null); return refresh() }
            is InboxAction.Edit -> if (s.pending == null && s.work !is InboxWork.Writing) {
                s = when (val route = s.route) { InboxRoute.Compose -> s.copy(newDraft = action.body); is InboxRoute.Thread -> s.copy(replyDrafts = s.replyDrafts + (route.id to action.body)); InboxRoute.List -> s }
                s = s.copy(issue = null)
            }
            InboxAction.MoreThreads -> { val before=s.nextBefore; if (before != null && s.work == InboxWork.Idle) { val request=id(); return InboxTransition(s.copy(work=InboxWork.Reading(request),issue=null),listOf(InboxCommand.Threads(request,before))) } }
            InboxAction.MoreMessages -> { val selected=s.selectedId; val before=s.conversation?.previousBefore; if (selected != null && before != null && s.work == InboxWork.Idle) { val request=id(); return InboxTransition(s.copy(work=InboxWork.Reading(request),issue=null),listOf(InboxCommand.ReadConversation(request,selected,before,true))) } }
            is InboxAction.Loaded -> {
                if (!s.visible || s.work != InboxWork.Reading(action.id)) return done()
                s=s.copy(work=InboxWork.Idle,issue=action.failure?.let { InboxIssue.Read(it) })
                action.page?.let { s=s.copy(threads=it.threads,nextBefore=it.nextBefore,availability=if(s.availability==InboxAvailability.Initial) InboxAvailability.Ready else s.availability) }
                restore(action.pending)
                if (action.page != null && s.pending?.kind == PendingSend.Kind.Reply) return refresh()
            }
            is InboxAction.ThreadsLoaded -> {
                if (!s.visible || s.work != InboxWork.Reading(action.id)) return done()
                s=s.copy(work=InboxWork.Idle,issue=action.failure?.let { InboxIssue.Read(it) })
                action.page?.let { page -> s=s.copy(threads=(s.threads+page.threads).distinctBy { it.id },nextBefore=page.nextBefore) }
            }
            is InboxAction.ConversationLoaded -> {
                if (!s.visible || s.work != InboxWork.Reading(action.id) || s.selectedId != action.threadId) return done()
                s=s.copy(work=InboxWork.Idle,issue=action.failure?.let { InboxIssue.Read(it) })
                action.page?.let { page ->
                    val messages=if(action.append) (s.conversation?.messages.orEmpty()+page.messages).distinctBy { it.id }.sortedBy { it.sequence } else page.messages
                    s=s.copy(conversation=page.copy(messages=messages),lastCommit=null,availability=if(s.availability==InboxAvailability.Initial) InboxAvailability.Ready else s.availability)
                }
            }
            InboxAction.Send -> if (s.canSend) {
                val request=s.pending ?: PendingSend(if(s.route==InboxRoute.Compose) PendingSend.Kind.NewThread else PendingSend.Kind.Reply,s.selectedId ?: id(),id(),s.draft)
                val effect=id();return InboxTransition(s.copy(pending=request,work=InboxWork.Writing(effect),issue=null,cleanupIssue=null),listOf(InboxCommand.CancelRead,InboxCommand.Write(effect,request)))
            }
            is InboxAction.Sent -> {
                if(s.work!=InboxWork.Writing(action.id)||s.pending!=action.request)return done()
                val pending=action.request;s=s.copy(work=InboxWork.Idle)
                action.receipt?.let { receipt ->
                    s=s.copy(lastCommit=pending.threadID to receipt.message,pending=if(receipt.cleanupIssue==null)null else pending,route=InboxRoute.Thread(pending.threadID),cleanupIssue=receipt.cleanupIssue,availability=if(s.availability==InboxAvailability.Initial) InboxAvailability.Ready else s.availability)
                    if(receipt.cleanupIssue==null){
                        if(pending.kind==PendingSend.Kind.NewThread && s.newDraft==pending.body)s=s.copy(newDraft="")
                        if(pending.kind==PendingSend.Kind.Reply && s.replyDrafts[pending.threadID]==pending.body)s=s.copy(replyDrafts=s.replyDrafts-pending.threadID)
                    }
                    when {
                        receipt.thread!=null -> s=s.copy(conversation=Conversation(receipt.thread,listOf(receipt.message)),threads=listOf(receipt.thread)+s.threads.filterNot { it.id==receipt.thread.id })
                        s.conversation!=null -> s.conversation?.let { existing -> s=s.copy(conversation=existing.copy(messages=(existing.messages+receipt.message).distinctBy { it.id }.sortedBy { it.sequence })) }
                        s.visible -> return refresh()
                    }
                }
                action.failure?.let { failure ->
                    s=s.copy(issue=InboxIssue.Send(failure))
                    if(failure.definitive){
                        if(pending.kind==PendingSend.Kind.NewThread&&s.newDraft.isEmpty())s=s.copy(newDraft=pending.body)
                        if(pending.kind==PendingSend.Kind.Reply&&s.replyDrafts[pending.threadID].isNullOrEmpty())s=s.copy(replyDrafts=s.replyDrafts+(pending.threadID to pending.body))
                        s=s.copy(pending=null)
                    }
                    if(failure==InboxFailure.Server(403,"installation_blocked"))s=s.copy(availability=InboxAvailability.Blocked)
                    if(failure==InboxFailure.Server(409,"thread_closed"))s.conversation?.let { s=s.copy(conversation=it.copy(thread=it.thread.copy(status=InboxThread.Status.Closed))) }
                    if(failure==InboxFailure.PendingConflict){s=s.copy(pending=null,route=InboxRoute.List);return refresh()}
                }
            }
        }
        return done()
    }
}
