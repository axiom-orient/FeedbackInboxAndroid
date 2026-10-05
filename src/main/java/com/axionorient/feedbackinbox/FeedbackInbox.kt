package com.axionorient.feedbackinbox

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Complete app-themed inbox UI. Supply this app's HTTPS Worker origin; no account screen or copied UI is required. */
@Composable fun FeedbackInbox(serverUrl: String, modifier: Modifier = Modifier, onClose: (() -> Unit)? = null) {
    val context = LocalContext.current.applicationContext
    val config = remember(serverUrl) { runCatching { InboxConfiguration(serverUrl) } }
    if (config.isFailure) {
        Text(stringResource(R.string.fi_config_error), modifier.padding(16.dp)); return
    }
    val configuration = config.getOrThrow()
    val model: InboxViewModel = viewModel(key="FeedbackInbox:${context.packageName}:${configuration.serverUrl}", factory=remember(configuration) {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = InboxViewModel(FeedbackInboxClient(context,configuration)) as T
        }
    })
    val owner=LocalLifecycleOwner.current
    DisposableEffect(model,owner) {
        model.dispatch(InboxAction.Appear)
        val observer=LifecycleEventObserver { _,event -> if(event==Lifecycle.Event.ON_RESUME) model.dispatch(InboxAction.Refresh) }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer);model.dispatch(InboxAction.Disappear) }
    }
    FeedbackInboxContent(model.state,model::dispatch,modifier,onClose)
}

/** Stateless rendering for hosts that already own state and command execution. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun FeedbackInboxContent(state: InboxState, onAction: (InboxAction) -> Unit, modifier: Modifier = Modifier, onClose: (() -> Unit)? = null) {
    val route=state.route
    val canBack=state.pending==null&&state.work !is InboxWork.Writing
    val listState=rememberLazyListState()
    val shownMessages=state.conversation?.messages ?: state.lastCommit?.takeIf{it.first==state.selectedId}?.let{listOf(it.second)}.orEmpty()
    LaunchedEffect(state.selectedId,shownMessages.lastOrNull()?.id) {
        if(state.selectedId!=null&&shownMessages.isNotEmpty()) {
            withFrameNanos { }
            val count=listState.layoutInfo.totalItemsCount
            if(count>0)listState.scrollToItem(count-1)
        }
    }
    BackHandler(enabled=route!=InboxRoute.List&&canBack){onAction(InboxAction.Back)}
    Scaffold(modifier=modifier,topBar={
        TopAppBar(title={ Text(stringResource(when(route){InboxRoute.List->R.string.fi_title;InboxRoute.Compose->R.string.fi_compose;is InboxRoute.Thread->R.string.fi_thread})) },
            navigationIcon={ if(route!=InboxRoute.List)TextButton(onClick={onAction(InboxAction.Back)},enabled=canBack,modifier=Modifier.testTag("feedbackInbox.inbox")){Text(stringResource(R.string.fi_inbox))} else onClose?.let { TextButton(onClick=it){Text(stringResource(R.string.fi_close))} } },
            actions={if(route!=InboxRoute.Compose)TextButton(onClick={onAction(InboxAction.Refresh)},enabled=state.work==InboxWork.Idle,modifier=Modifier.testTag("feedbackInbox.refresh")){Text(stringResource(R.string.fi_refresh))}})
    },bottomBar={
        if(route==InboxRoute.Compose||state.conversation?.thread?.status==InboxThread.Status.Open||state.pending!=null||state.selectedId!=null&&state.draft.isNotEmpty()) {
            Surface(tonalElevation=2.dp){Column(Modifier.fillMaxWidth().imePadding().navigationBarsPadding().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                var details by remember(route){mutableStateOf(false)}
                TextButton(onClick={details=!details},modifier=Modifier.testTag("feedbackInbox.transfer")){Text(stringResource(R.string.fi_transfer))}
                if(details)Text(stringResource(R.string.fi_transfer_body),style=MaterialTheme.typography.bodySmall)
                OutlinedTextField(value=state.draft,onValueChange={onAction(InboxAction.Edit(it))},enabled=state.pending==null&&state.work !is InboxWork.Writing,
                    label={Text(stringResource(if(route==InboxRoute.Compose)R.string.fi_body else R.string.fi_reply))},minLines=2,maxLines=5,modifier=Modifier.fillMaxWidth().testTag("feedbackInbox.body"))
                if(state.draft.codePointCount(0,state.draft.length)>InboxPolicy.maximumCharacters)Text(stringResource(R.string.fi_limit),color=MaterialTheme.colorScheme.error)
                if(state.pending!=null)Text(stringResource(R.string.fi_pending),style=MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End){
                    Button(onClick={onAction(InboxAction.Send)},enabled=state.canSend,modifier=Modifier.testTag("feedbackInbox.send")) {
                        Text(stringResource(if(state.pending==null)R.string.fi_send else R.string.fi_reconcile))
                    }
                }
            }}
        }
    }){padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("feedbackInbox.content"),state=listState,contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            state.issue?.let { issue -> item {
                val saved=issue is InboxIssue.Read&&state.lastCommit?.first==state.selectedId&&state.lastCommit!=null
                Text(stringResource(if(saved)R.string.fi_saved_read_failed else failureCopy(issue.failure)),color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("feedbackInbox.error"))
                if(state.pending==null)TextButton(onClick={onAction(InboxAction.Refresh)}){Text(stringResource(R.string.fi_retry))}
            } }
            if(state.availability==InboxAvailability.Blocked&&state.issue==null)item{Text(stringResource(R.string.fi_blocked),color=MaterialTheme.colorScheme.error)}
            if(state.cleanupIssue!=null)item{Text(stringResource(R.string.fi_cleanup),style=MaterialTheme.typography.bodySmall)}
            if(state.work!=InboxWork.Idle)item{LinearProgressIndicator(Modifier.fillMaxWidth());Text(stringResource(if(state.work is InboxWork.Writing)R.string.fi_sending else R.string.fi_loading))}
            if(route==InboxRoute.List){
                item{Button(onClick={onAction(InboxAction.Compose)},enabled=state.availability==InboxAvailability.Ready&&state.work==InboxWork.Idle&&state.pending==null,modifier=Modifier.fillMaxWidth().testTag("feedbackInbox.new")){Text(stringResource(R.string.fi_new))}}
                item{Text(stringResource(R.string.fi_intro),style=MaterialTheme.typography.bodySmall)}
                if(state.availability==InboxAvailability.Ready&&state.threads.isEmpty())item{Text(stringResource(R.string.fi_empty),modifier=Modifier.testTag("feedbackInbox.empty"))}
                items(state.threads,key={it.id}){thread->
                    Card(onClick={onAction(InboxAction.Open(thread.id))},modifier=Modifier.fillMaxWidth().testTag("feedbackInbox.thread.${thread.id}")){
                        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                            Text(thread.preview,maxLines=2)
                            thread.lastPreview?.takeIf { it!=thread.preview }?.let{Text(it,maxLines=2,style=MaterialTheme.typography.bodySmall)}
                            Text(stringResource(if(thread.status==InboxThread.Status.Closed)R.string.fi_closed else if(thread.lastSender==InboxMessage.Sender.Operator)R.string.fi_answered else R.string.fi_open),style=MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                if(state.nextBefore!=null)item{TextButton(onClick={onAction(InboxAction.MoreThreads)},enabled=state.work==InboxWork.Idle){Text(stringResource(R.string.fi_more_threads))}}
                item{var help by remember{mutableStateOf(false)};TextButton(onClick={help=!help}){Text(stringResource(R.string.fi_help))};if(help)Text(stringResource(R.string.fi_help_body),style=MaterialTheme.typography.bodySmall)}
            }else{
                val page=state.conversation
                if(page?.previousBefore!=null)item{TextButton(onClick={onAction(InboxAction.MoreMessages)},enabled=state.work==InboxWork.Idle){Text(stringResource(R.string.fi_older))}}
                val messages=shownMessages
                items(messages,key={it.id}){message-> MessageCard(message)}
                if(page?.thread?.status==InboxThread.Status.Closed&&state.pending==null)item{Text(stringResource(R.string.fi_closed_body))}
            }
        }
    }
}
@Composable private fun MessageCard(message: InboxMessage){
    Card(Modifier.fillMaxWidth().testTag("feedbackInbox.message.${message.id}")) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(stringResource(if(message.sender==InboxMessage.Sender.User)R.string.fi_me else R.string.fi_developer),style=MaterialTheme.typography.titleSmall)
        SelectionContainer{Text(message.body)}
        Text(DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(Date(message.createdAt*1000)),style=MaterialTheme.typography.bodySmall)
    }}
}
private fun failureCopy(f:InboxFailure):Int=when(f){
    InboxFailure.InvalidConfiguration,InboxFailure.InvalidContext->R.string.fi_config_error
    InboxFailure.InvalidMessage,InboxFailure.PayloadTooLarge->R.string.fi_limit
    InboxFailure.CorruptStorage,InboxFailure.StorageUnavailable->R.string.fi_storage
    InboxFailure.PendingConflict,InboxFailure.WriteInProgress->R.string.fi_pending
    InboxFailure.RejectionCleanupFailed->R.string.fi_rejection_cleanup
    is InboxFailure.Server->when(f.code){"daily_thread_limit"->R.string.fi_daily;"write_burst","registration_burst"->R.string.fi_burst;"thread_closed"->R.string.fi_closed_body;"installation_blocked"->R.string.fi_blocked;"app_not_supported","app_context_mismatch","app_not_configured"->R.string.fi_config_error;else->R.string.fi_unknown}
    else->R.string.fi_unknown
}
private class InboxViewModel(private val client:FeedbackInboxClient):ViewModel(){
    var state by mutableStateOf(InboxState());private set
    private var read:Job?=null
    fun dispatch(action:InboxAction){
        val transition=InboxFlow.reduce(state,action);state=transition.state
        transition.commands.forEach { command ->
            if(command==InboxCommand.CancelRead){read?.cancel();read=null;return@forEach}
            val task=viewModelScope.launch{
                when(command){
                    is InboxCommand.Inbox->{var pending:PendingSend?=null;try{pending=client.pendingSend();val page=client.list();coroutineContext.ensureActive();dispatch(InboxAction.Loaded(command.id,page,pending,null))}catch(e:CancellationException){throw e}catch(e:InboxFailure){dispatch(InboxAction.Loaded(command.id,null,pending,e))}}
                    is InboxCommand.Threads->try{val page=client.list(command.before);coroutineContext.ensureActive();dispatch(InboxAction.ThreadsLoaded(command.id,page,null))}catch(e:CancellationException){throw e}catch(e:InboxFailure){dispatch(InboxAction.ThreadsLoaded(command.id,null,e))}
                    is InboxCommand.ReadConversation->try{val page=client.conversation(command.threadId,command.before);coroutineContext.ensureActive();dispatch(InboxAction.ConversationLoaded(command.id,command.threadId,command.append,page,null))}catch(e:CancellationException){throw e}catch(e:InboxFailure){dispatch(InboxAction.ConversationLoaded(command.id,command.threadId,command.append,null,e))}
                    is InboxCommand.Write->try{dispatch(InboxAction.Sent(command.id,command.pending,client.send(command.pending),null))}catch(e:CancellationException){throw e}catch(e:InboxFailure){dispatch(InboxAction.Sent(command.id,command.pending,null,e))}
                    InboxCommand.CancelRead->Unit
                }
            }
            if(command !is InboxCommand.Write)read=task
        }
    }
}
