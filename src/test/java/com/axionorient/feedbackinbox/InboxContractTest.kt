package com.axionorient.feedbackinbox

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.serialization.json.*

private const val tid="11111111-1111-1111-1111-111111111111"
private const val mid="22222222-2222-2222-2222-222222222222"
internal val fixtureContext=ClientContext(appName="Fixture",appID="com.example.fixture",appVersion="2.0",appBuild="3",osName="Android",osVersion="16",deviceModel="Android phone",language="ko-KR")
internal class MemoryInboxStorage:InboxStorage {
    var saved:PendingSend?=null;var failClear=false
    override fun credential()=Credential(tid,"b".repeat(64))
    override fun pending()=saved
    override fun admit(candidate:PendingSend,context:ClientContext):PendingSend {
        val existing=saved
        if(existing!=null){if(!existing.matchesRetry(candidate))throw InboxFailure.PendingConflict;return existing}
        return candidate.copy(clientContext=candidate.clientContext ?: context).also{saved=it}
    }
    override fun clear(candidate:PendingSend){if(failClear)throw InboxFailure.StorageUnavailable;if(saved==candidate)saved=null}
}
private fun response(p:PendingSend)=HttpResponse(201,"""{"thread":{"id":"${p.threadID}","status":"open","createdAt":1,"updatedAt":1,"preview":"${p.body}"},"message":{"id":"${p.messageID}","sequence":1,"sender":"user","body":"${p.body}","createdAt":1,"clientContext":${inboxJson.encodeToString(ClientContext.serializer(),p.clientContext!!)}}}""".toByteArray())
private fun client(storage:MemoryInboxStorage,exchange:suspend(HttpRequest)->HttpResponse,capture:suspend()->ClientContext={fixtureContext})=FeedbackInboxClient(InboxConfiguration("https://support.example.test"),fixtureContext.appID!!,storage,capture,exchange)
private fun registration()=HttpResponse(200,"""{"id":"$tid"}""".toByteArray())
private inline fun fails(expected:InboxFailure,block:()->Unit){try{block();fail("Expected typed failure")}catch(e:InboxFailure){assertEquals(expected,e)}}

class InboxContractTest {
    @Test fun scalarAndConfigBoundaries(){
        assertTrue(InboxPolicy.accepts("😀".repeat(5000)));assertFalse(InboxPolicy.accepts("😀".repeat(5001)));assertFalse(InboxPolicy.accepts(" \n\t"))
        assertTrue(InboxPolicy.validAppId("a".repeat(128)));assertFalse(InboxPolicy.validAppId("a".repeat(129)));assertFalse(InboxPolicy.validAppId("앱.foo"))
        for(url in listOf("http://example.test","https://example.test/path","https://user:pw@example.test","https://example.test?x=1"))fails(InboxFailure.InvalidConfiguration){InboxConfiguration(url)}
    }
    @Test fun lostResponseRecreatesClientAndReplaysFrozenSnapshot()=runBlocking{
        val store=MemoryInboxStorage();val request=PendingSend(PendingSend.Kind.NewThread,tid,mid,"original")
        val first=client(store,{if(it.url.endsWith("installations"))registration() else throw InboxFailure.Transport})
        fails(InboxFailure.Transport){first.send(request)};assertEquals(fixtureContext,store.saved?.clientContext)
        var replayBody:ByteArray?=null
        val restarted=client(store,{if(it.url.endsWith("installations"))registration() else {replayBody=it.body;response(store.saved!!)}},{fixtureContext.copy(appVersion="9.0")})
        val receipt=restarted.send(request);assertEquals(mid,receipt.message.id);assertNull(store.saved)
        val wire=inboxJson.parseToJsonElement(replayBody!!.decodeToString()).jsonObject
        assertEquals("2.0",wire["clientContext"]!!.jsonObject["appVersion"]!!.jsonPrimitive.content)
    }
    @Test fun unknownFailureKeepsPendingAndDefinitiveRejectionRestoresAdmission()=runBlocking{
        val store=MemoryInboxStorage();val request=PendingSend(PendingSend.Kind.NewThread,tid,mid,"original")
        val unknown=client(store,{if(it.url.endsWith("installations"))registration()else HttpResponse(500,"{}".toByteArray())})
        fails(InboxFailure.Server(500,null)){unknown.send(request)};assertNotNull(store.saved)
        val rejected=client(store,{if(it.url.endsWith("installations"))registration()else HttpResponse(429,"""{"error":"daily_thread_limit"}""".toByteArray())})
        fails(InboxFailure.Server(429,"daily_thread_limit")){rejected.send(request)};assertNull(store.saved)
    }
    @Test fun mismatchedReceiptDoesNotClearPending()=runBlocking{
        val store=MemoryInboxStorage();val request=PendingSend(PendingSend.Kind.NewThread,tid,mid,"original")
        val live=client(store,{if(it.url.endsWith("installations"))registration()else response(store.saved!!.copy(messageID=tid))})
        fails(InboxFailure.InvalidResponse){live.send(request)};assertNotNull(store.saved)
    }
    @Test fun committedReceiptSurvivesStorageCleanupFailure()=runBlocking{
        val store=MemoryInboxStorage();store.failClear=true
        val live=client(store,{if(it.url.endsWith("installations"))registration()else response(store.saved!!)})
        val result=live.send(PendingSend(PendingSend.Kind.NewThread,tid,mid,"original"));assertEquals(mid,result.message.id);assertEquals(InboxFailure.StorageUnavailable,result.cleanupIssue);assertNotNull(store.saved)
    }
    @Test fun concurrentWriteCannotOverwriteAnAdmittedWrite()=runBlocking{
        val store=MemoryInboxStorage();val admitted=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val live=client(store,{if(it.url.endsWith("installations"))registration()else{admitted.complete(Unit);release.await();response(store.saved!!)}})
        val first=async{live.send(PendingSend(PendingSend.Kind.NewThread,tid,mid,"original"))};admitted.await()
        fails(InboxFailure.WriteInProgress){live.send(PendingSend.inquiry("different"))};release.complete(Unit);assertEquals(mid,first.await().message.id)
    }
    @Test fun malformedJsonAndWrongAppContextAreExplicit()=runBlocking{
        val store=MemoryInboxStorage();val bad=client(store,{HttpResponse(200,"not-json".toByteArray())})
        fails(InboxFailure.InvalidResponse){bad.list()}
        val wrong=client(store,{registration()},{fixtureContext.copy(appID="com.example.other")})
        fails(InboxFailure.InvalidContext){wrong.send(PendingSend.inquiry("message"))};assertNull(store.saved)
    }
}
