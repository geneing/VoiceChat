package com.voicechat.agent.stt.parakeet

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
 * Reads one model file's bytes into a destination file.
 *
 * The interface exists so the installer's integrity/atomic-install logic is
 * proven by JVM tests with a fake source, and so the network implementation can
 * be swapped. Implementations must be cancellable and must throw rather than
 * leaving a partial destination on failure.
 */
fun interface ParakeetEouModelSource {
    /** Downloads the pinned file at [url] into [destination], replacing it. */
    suspend fun downloadTo(
        url: String,
        destination: File,
    )
}

/**
 * Downloads a pinned Parakeet-EOU file with the existing OkHttp dependency.
 *
 * This is the only network implementation of [ParakeetEouModelSource]. The URLs
 * are public Hugging Face `resolve/main` URLs — **no secret, no auth**. The
 * download streams to a file, checks for cancellation between chunks, and maps a
 * transport failure to the typed `MODEL_DOWNLOAD_FAILED` code — never to a
 * success-shaped result.
 */
class OkHttpParakeetEouModelSource(
    private val client: OkHttpClient = defaultClient(),
) : ParakeetEouModelSource {
    override suspend fun downloadTo(
        url: String,
        destination: File,
    ) {
        val call = client.newCall(Request.Builder().url(url).build())
        try {
            val response = withContext(Dispatchers.IO) { call.execute() }
            response.use {
                if (!response.isSuccessful) {
                    throw VoiceAgentException(
                        VoiceAgentError(
                            ErrorCode.MODEL_DOWNLOAD_FAILED,
                            "Parakeet-EOU download returned HTTP ${response.code}",
                        ),
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
                VoiceAgentError(ErrorCode.MODEL_DOWNLOAD_FAILED, "Parakeet-EOU download failed"),
                failure,
            )
        }
    }

    private companion object {
        const val BUFFER_BYTES = 64 * 1024
        const val CONNECT_TIMEOUT_SECONDS = 15L

        // The encoder is ~132 MB, larger than any Smart Turn file; a longer read
        // timeout avoids aborting a slow-but-progressing CDN transfer.
        const val READ_TIMEOUT_SECONDS = 60L

        fun defaultClient(): OkHttpClient =
            OkHttpClient
                .Builder()
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                // A model download is one response. Redirects are followed because
                // the pinned Hugging Face URL answers 302 to a CDN; the exact size +
                // SHA-256 check after the download is what actually guarantees the
                // bytes are the reviewed artifact.
                .retryOnConnectionFailure(false)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
    }
}
