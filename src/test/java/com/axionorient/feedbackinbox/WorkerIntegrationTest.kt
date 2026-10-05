package com.axionorient.feedbackinbox

import java.net.URI
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class WorkerIntegrationTest {
    @Test fun realWorkerRecoversOneFrozenMessageAfterResponseLoss()=runBlocking{
        val base=System.getenv("FEEDBACK_ANDROID_TEST_URL")
        assumeTrue("An isolated local Worker is required",base!=null)
        val storage=MemoryInboxStorage();val credential=Credential(newId(),"c".repeat(64))
        val adapter=object:InboxStorage by storage { override fun credential()=credential }
        var drop=true
        val exchange:suspend(HttpRequest)->HttpResponse={request->
            val uri=URI(request.url);val live=request.copy(url=base+uri.rawPath+(uri.rawQuery?.let{"?$it"}?:""))
            val result=UrlConnectionTransport.execute(live)
            if(uri.path=="/threads"&&request.body!=null&&drop){drop=false;throw InboxFailure.Transport}
            result
        }
        val config=InboxConfiguration("https://fixture.example.test")
        val pending=PendingSend.inquiry("Android SDK isolated HTTP verification")
        val initial=FeedbackInboxClient(config,"com.example.fixture",adapter,{fixtureContext},exchange)
        try{initial.send(pending);fail("Response loss expected")}catch(e:InboxFailure){assertEquals(InboxFailure.Transport,e)}
        val restarted=FeedbackInboxClient(config,"com.example.fixture",adapter,{fixtureContext.copy(appVersion="9.9")},exchange)
        val receipt=restarted.send(pending);assertEquals(pending.messageID,receipt.message.id);assertEquals("2.0",receipt.message.clientContext?.appVersion)
        val page=restarted.conversation(pending.threadID);assertEquals(1,page.messages.size);assertEquals(pending.body,page.messages.single().body)
        assertEquals(pending.messageID,restarted.send(pending.copy(clientContext=receipt.message.clientContext)).message.id)
        assertEquals(1,restarted.inbox().page.threads.size)
    }
    @Test fun realTransportNeverForwardsEnrollmentThroughRedirect()=runBlocking{
        val base=System.getenv("FEEDBACK_ANDROID_REDIRECT_URL")
        assumeTrue("An isolated redirect fixture is required",base!=null)
        val response=UrlConnectionTransport.execute(HttpRequest(base+"/redirect","disposable-fixture".toByteArray(),"Bearer fixture"))
        assertEquals(307,response.status)
        val connection=URL(base+"/count").openConnection() as HttpURLConnection
        val count=connection.inputStream.use{it.readBytes().decodeToString()};connection.disconnect();assertEquals("0",count)
    }
}
