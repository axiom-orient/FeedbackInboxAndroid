package com.axionorient.feedbackinbox

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

internal data class HttpRequest(val url: String, val body: ByteArray? = null, val authorization: String? = null)
internal data class HttpResponse(val status: Int, val body: ByteArray)
internal fun encodeQuery(value: String): String = URLEncoder.encode(value, "UTF-8")
internal object UrlConnectionTransport {
    suspend fun execute(request: HttpRequest): HttpResponse {
        currentCoroutineContext().ensureActive()
        val connection = URL(request.url).openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 30_000; connection.readTimeout = 45_000
            connection.requestMethod = if (request.body == null) "GET" else "POST"
            connection.setRequestProperty("Content-Type", "application/json"); connection.setRequestProperty("Accept", "application/json")
            request.authorization?.let { connection.setRequestProperty("Authorization", it) }
            request.body?.let { connection.doOutput = true; connection.setFixedLengthStreamingMode(it.size); connection.outputStream.use { stream -> stream.write(it) } }
            val status = connection.responseCode
            val response = ByteArrayOutputStream()
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            stream?.use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer); if (count < 0) break
                    if (response.size() + count > 2_000_000) throw InboxFailure.InvalidResponse
                    response.write(buffer, 0, count)
                }
            }
            return HttpResponse(status, response.toByteArray())
        } finally { connection.disconnect() }
    }
}
@Suppress("DEPRECATION")
internal fun captureContext(context: Context): ClientContext {
    val manager = context.packageManager
    val info = manager.getPackageInfo(context.packageName, 0)
    val build = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    val family = if (context.resources.configuration.smallestScreenWidthDp >= 600) "Android tablet" else "Android phone"
    return ClientContext(appName = manager.getApplicationLabel(context.applicationInfo).toString(), appID = context.packageName,
        appVersion = info.versionName, appBuild = build.toString(), osName = "Android", osVersion = Build.VERSION.RELEASE,
        deviceModel = family, language = Locale.getDefault().toLanguageTag())
}
