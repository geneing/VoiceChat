package com.voicechat.agent.tts.kokoro

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
 * Downloads a revision-pinned Teacher-in-the-Loop Kokoro graph over OkHttp (M28).
 *
 * This is the network implementation behind [KokoroModelSource]; the model store
 * owns size/hash verification and atomic installation. Hugging Face redirects
 * the immutable revision URL to a CDN, so redirects are followed and the pinned
 * SHA-256 remains the byte-identity check (`docs/model-runtime.md`).
 */
class OkHttpKokoroModelSource(
    private val voice: KokoroVoiceArtifact = KokoroArtifact.DEFAULT,
    private val client: OkHttpClient = defaultClient(),
) : KokoroModelSource {
    override suspend fun downloadTo(destination: File) {
        val url =
            "https://huggingface.co/${KokoroArtifact.UPSTREAM_REPOSITORY}/resolve/" +
                "${KokoroArtifact.UPSTREAM_REVISION}/${voice.upstreamPath}"
        val call = client.newCall(Request.Builder().url(url).build())
        try {
            val response = withContext(Dispatchers.IO) { call.execute() }
            response.use {
                if (!response.isSuccessful) {
                    throw VoiceAgentException(
                        VoiceAgentError(
                            ErrorCode.MODEL_DOWNLOAD_FAILED,
                            "Kokoro model download returned HTTP ${response.code}",
                        ),
                    )
                }
                val body = response.body
                if (body.contentLength() > voice.sizeBytes) {
                    throw VoiceAgentException(
                        VoiceAgentError(ErrorCode.MODEL_DOWNLOAD_FAILED, "Kokoro model response exceeded pinned size"),
                    )
                }
                withContext(Dispatchers.IO) {
                    body.byteStream().use { input ->
                        destination.outputStream().use { output ->
                            val buffer = ByteArray(BUFFER_BYTES)
                            var receivedBytes = 0L
                            while (true) {
                                coroutineContext.ensureActive()
                                val read = input.read(buffer)
                                if (read < 0) break
                                if (read.toLong() > voice.sizeBytes - receivedBytes) {
                                    throw VoiceAgentException(
                                        VoiceAgentError(
                                            ErrorCode.MODEL_DOWNLOAD_FAILED,
                                            "Kokoro model response exceeded pinned size",
                                        ),
                                    )
                                }
                                output.write(buffer, 0, read)
                                receivedBytes += read
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
                VoiceAgentError(ErrorCode.MODEL_DOWNLOAD_FAILED, "Kokoro model download failed"),
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
                // Hugging Face redirects this revision-pinned URL to a CDN. The exact hash
                // verification performed by KokoroModelStore is the byte-identity guarantee.
                .retryOnConnectionFailure(false)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
    }
}
