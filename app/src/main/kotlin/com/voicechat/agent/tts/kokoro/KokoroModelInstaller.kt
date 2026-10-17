package com.voicechat.agent.tts.kokoro

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File

/** User-approved installation of the pinned distilled Kokoro voice graph. */
fun interface KokoroModelInstaller {
    /** Emits one [KokoroInstallStatus.Started] and exactly one terminal status. */
    fun install(): Flow<KokoroInstallStatus>
}

/** Progress and terminal result for one Kokoro voice install. */
sealed interface KokoroInstallStatus {
    /** The verified artifact download has begun. */
    data class Started(
        val totalBytes: Long,
    ) : KokoroInstallStatus

    /** The graph passed integrity verification and was installed. */
    data object Completed : KokoroInstallStatus

    /** Installation failed with a safe explanation. */
    data class Failed(
        val message: String,
    ) : KokoroInstallStatus
}

/** Production installer for the pinned, app-private Kokoro model. */
class DefaultKokoroModelInstaller(
    private val store: KokoroModelStore,
    private val source: KokoroModelSource = OkHttpKokoroModelSource(),
    private val voice: KokoroVoiceArtifact = KokoroArtifact.DEFAULT,
) : KokoroModelInstaller {
    /** Convenience constructor using the standard app-private model directory. */
    constructor(directory: File) :
        this(
            store = KokoroModelStore(directory),
        )

    override fun install(): Flow<KokoroInstallStatus> =
        flow {
            emit(KokoroInstallStatus.Started(store.pinnedSizeBytes(voice)))
            val terminal =
                try {
                    when (val result = store.install(voice, source)) {
                        is KokoroInstallResult.Installed -> {
                            KokoroInstallStatus.Completed
                        }

                        is KokoroInstallResult.Failed -> {
                            KokoroInstallStatus.Failed(
                                result.error.detail ?: "The Kokoro voice model could not be installed.",
                            )
                        }
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    KokoroInstallStatus.Failed("The Kokoro voice model could not be installed.")
                }
            emit(terminal)
        }
}

/** Honest fallback when the build has no model downloader wired. */
object UnavailableKokoroModelInstaller : KokoroModelInstaller {
    override fun install(): Flow<KokoroInstallStatus> =
        flow {
            emit(KokoroInstallStatus.Failed("Kokoro model downloads are not available in this build."))
        }
}
