package com.voicechat.agent.turn

import com.voicechat.agent.domain.ErrorCode
import com.voicechat.agent.domain.VoiceAgentError
import com.voicechat.agent.domain.VoiceAgentException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * Downloads the pinned Smart Turn artifact with the existing OkHttp dependency.
 *
 * This is the only network implementation of [SmartTurnModelSource]; the interface
 * keeps the installer logic (integrity + atomic move) testable with a fake source
 * and **no network**. The download streams to a file, checks for cancellation
 * between chunks, and maps a transport failure to the typed
 * `MODEL_DOWNLOAD_FAILED` code — never to a success-shaped result.
 */
class OkHttpSmartTurnModelSource(
    private val url: String = SmartTurnArtifact.PINNED.downloadUrl,
    private val client: OkHttpClient = defaultClient(),
) : SmartTurnModelSource {
    override suspend fun downloadTo(destination: File) {
        val call = client.newCall(Request.Builder().url(url).build())
        try {
            val response = withContext(Dispatchers.IO) { call.execute() }
            response.use {
                if (!response.isSuccessful) {
                    throw VoiceAgentException(
                        VoiceAgentError(ErrorCode.MODEL_DOWNLOAD_FAILED, "Smart Turn download returned HTTP ${response.code}"),
                    )
                }
                val body = response.body
                withContext(Dispatchers.IO) {
                    body.byteStream().use { input ->
                        destination.outputStream().use { output ->
                            val buffer = ByteArray(BUFFER_BYTES)
                            while (true) {
                                coroutineContext.ensureActive()
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                }
            }
        } catch (cancellation: CancellationException) {
            call.cancel()
            throw cancellation
        } catch (failure: VoiceAgentException) {
            call.cancel()
            throw failure
        } catch (failure: IOException) {
            call.cancel()
            throw VoiceAgentException(
                VoiceAgentError(ErrorCode.MODEL_DOWNLOAD_FAILED, "Smart Turn download failed"),
                failure,
            )
        }
    }

    private companion object {
        const val BUFFER_BYTES = 64 * 1024
        const val CONNECT_TIMEOUT_SECONDS = 15L
        const val READ_TIMEOUT_SECONDS = 30L

        fun defaultClient(): OkHttpClient =
            OkHttpClient
                .Builder()
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                // A model download is one response. Redirects are followed because
                // the pinned Hugging Face URL answers 302 to a CDN; the destination
                // is pinned, so following it is intentional, and the exact size +
                // SHA-256 check after the download is what actually guarantees the
                // bytes are the reviewed artifact.
                .retryOnConnectionFailure(false)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
    }
}
