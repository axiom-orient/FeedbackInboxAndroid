package com.axionorient.feedbackinbox

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.security.KeyStore

@RunWith(AndroidJUnit4::class)
class EncryptedStorageTest {
    @Test fun actualKeystoreKeepsExactPendingAcrossStorageRecreation()=runBlocking{withContext(Dispatchers.IO){
        val context=InstrumentationRegistry.getInstrumentation().targetContext;val suffix=".test.${UUID.randomUUID()}"
        val first=EncryptedInboxStorage(context,"https://fixture.example.test",suffix)
        try{
            val identity=first.credential();val metadata=fixtureContext(context.packageName)
            val pending=PendingSend.inquiry("disposable encrypted fixture")
            val frozen=first.admit(pending,metadata)
            val second=EncryptedInboxStorage(context,"https://fixture.example.test",suffix)
            assertEquals(identity,second.credential());assertEquals(frozen,second.pending())
            val bytes=File(context.noBackupFilesDir,"feedback-inbox$suffix/state.bin").readBytes().decodeToString()
            assertFalse(bytes.contains(identity.secret));assertFalse(bytes.contains(pending.body))
            assertEquals(frozen,second.admit(pending,metadata.copy(appVersion="new")))
            second.clear(frozen);assertNull(first.pending())
        }finally{
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry("${context.packageName}.feedback.aes.v1$suffix") }
            File(context.noBackupFilesDir,"feedback-inbox$suffix").deleteRecursively()
        }
    }}
    private fun fixtureContext(appID:String)=ClientContext(appID=appID,osName="Android",osVersion="fixture",deviceModel="Android phone",language="en")
}
