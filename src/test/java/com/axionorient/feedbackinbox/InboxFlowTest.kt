package com.axionorient.feedbackinbox

import org.junit.Assert.*
import org.junit.Test

class InboxFlowTest {
    private val id="11111111-1111-1111-1111-111111111111"
    private fun apply(s:InboxState,a:InboxAction)=InboxFlow.reduce(s,a){id}.state
    @Test fun failedLoadDoesNotBecomeAnEmptyReadyInbox(){
        val loading=apply(InboxState(),InboxAction.Appear)
        val failed=apply(loading,InboxAction.Loaded(id,null,null,InboxFailure.Transport))
        assertEquals(InboxAvailability.Initial,failed.availability);assertNotNull(failed.issue)
        val ready=apply(loading,InboxAction.Loaded(id,ThreadPage(emptyList()),null,null));assertEquals(InboxAvailability.Ready,ready.availability)
    }
    @Test fun staleAndDismissedReadsDoNotChangeState(){
        val loading=apply(InboxState(),InboxAction.Appear);val hidden=apply(loading,InboxAction.Disappear)
        assertEquals(hidden,apply(hidden,InboxAction.Loaded(id,ThreadPage(emptyList()),null,null)))
        assertEquals(loading,apply(loading,InboxAction.Loaded("different",ThreadPage(emptyList()),null,null)))
    }
    @Test fun draftsSurviveNavigationAndSuccessfulThreadPaginationClearsError(){
        var s=InboxState(visible=true,availability=InboxAvailability.Ready)
        s=apply(s,InboxAction.Compose);s=apply(s,InboxAction.Edit("draft"));s=apply(s,InboxAction.Back).copy(work=InboxWork.Idle);s=apply(s,InboxAction.Compose)
        assertEquals("draft",s.draft)
        s=s.copy(route=InboxRoute.List,nextBefore="cursor",issue=InboxIssue.Read(InboxFailure.Transport))
        s=apply(s,InboxAction.MoreThreads);s=apply(s,InboxAction.ThreadsLoaded(id,ThreadPage(emptyList()),null));assertNull(s.issue)
    }
    @Test fun coldRestoredRejectedBodyRemainsEditable(){
        for(kind in PendingSend.Kind.entries){
            val pending=PendingSend(kind,id,id,"restored")
            var s=apply(InboxState(),InboxAction.Appear);s=apply(s,InboxAction.Loaded(id,null,pending,InboxFailure.Transport));assertTrue(s.canSend)
            s=apply(s,InboxAction.Send);s=apply(s,InboxAction.Sent(id,pending,null,InboxFailure.Server(429,"daily_thread_limit")))
            assertNull(s.pending);assertEquals("restored",s.draft);assertEquals("edited",apply(s,InboxAction.Edit("edited")).draft)
        }
    }
    @Test fun committedReplyStillShowsReceiptWhenTranscriptReadFails(){
        val pending=PendingSend(PendingSend.Kind.Reply,id,id,"saved")
        val message=InboxMessage(id,1,InboxMessage.Sender.User,"saved",1)
        var s=InboxState(visible=true,route=InboxRoute.Thread(id),pending=pending,work=InboxWork.Writing(id))
        s=apply(s,InboxAction.Sent(id,pending,SendReceipt(null,message),null));s=apply(s,InboxAction.ConversationLoaded(id,id,false,null,InboxFailure.Transport))
        assertEquals(message,s.lastCommit?.second);assertNull(s.pending);assertTrue(s.issue is InboxIssue.Read)
    }
    @Test fun lateWriteAfterDismissalKeepsKnownCommit(){
        val pending=PendingSend(PendingSend.Kind.NewThread,id,id,"saved");val message=InboxMessage(id,1,InboxMessage.Sender.User,"saved",1)
        var s=InboxState(visible=true,route=InboxRoute.Compose,newDraft="saved",pending=pending,work=InboxWork.Writing(id))
        s=apply(s,InboxAction.Disappear);s=apply(s,InboxAction.Sent(id,pending,SendReceipt(InboxThread(id,InboxThread.Status.Open,1,1,"saved"),message),null))
        assertFalse(s.visible);assertEquals(message,s.conversation?.messages?.single());assertEquals("",s.newDraft)
    }
}
