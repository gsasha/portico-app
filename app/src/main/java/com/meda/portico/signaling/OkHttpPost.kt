package com.meda.portico.signaling

import com.meda.portico.core.auth.EnrollContract
import com.meda.portico.core.auth.HttpPost
import com.meda.portico.core.auth.HttpReply
import com.meda.portico.core.net.UrlCheck
import com.meda.portico.core.net.UrlPolicy
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * DEV-544: one HTTP POST to Home Assistant over OkHttp (the redemption of a one-time code, and HA's `/auth/token`).
 * No `android.*` imports, so it runs in JVM unit tests against MockWebServer.
 *
 * - The URL is checked against [UrlPolicy] again here (https always, plain http only to a private host), as
 *   [OkHttpConnector] does for the socket, because Android permits cleartext for the whole app.
 * - A redirect is never followed (the client is built with `followRedirects(false)`) and is reported as an [IOException]: the
 *   body holds a credential, and it must not go anywhere `ha_url` did not name.
 * - Nothing is logged: no request, no answer, no header. An exception's message is fixed text, never the URL or a body.
 * - [retryOnConnectionFailure] is off: the caller decides when to send the same request again (the redemption contract allows
 *   it for two minutes), so the number of requests that reach the server is the number the caller meant.
 */
class OkHttpPost(private val client: OkHttpClient = defaultClient()) : HttpPost {

    override fun post(url: String, contentType: String, body: String): HttpReply {
        if (UrlPolicy.check(url) is UrlCheck.Refused) throw IOException("refused by the network policy")
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .post(body.toRequestBody(contentType.toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            if (response.isRedirect) throw IOException("redirect not followed")
            val text = response.peekBody(EnrollContract.MAX_RESPONSE_CHARS.toLong()).string()
            return HttpReply(response.code, text, response.header("Retry-After"))
        }
    }

    override fun toString(): String = "OkHttpPost"

    companion object {
        /** Seconds, deliberately short of the WebSocket's patience: a request that cannot finish in 30 s falls to the other path. */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .connectTimeout(8, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
