package com.voicechat.agent.ui

import com.voicechat.agent.log.AppLog
import com.voicechat.agent.stt.parakeet.OkHttpParakeetEouModelSource
import com.voicechat.agent.stt.parakeet.ParakeetEouInstallResult
import com.voicechat.agent.stt.parakeet.ParakeetEouModelSource
import com.voicechat.agent.stt.parakeet.ParakeetEouModelStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File

/**
 * Downloads and installs the five pinned Parakeet-EOU files from Settings (M10+,
 * app-managed).
 *
 * The model set is not bundled in the APK: it is fetched from the revision-pinned
 * URL on the user's explicit action, each file verified against the exact byte
 * size and SHA-256, and installed atomically in app-private storage by
 * [ParakeetEouModelStore.install]. This seam keeps [SettingsViewModel] free of the
 * store/network types so the flow is a JVM test with a fake.
 *
 * It mirrors [SmartTurnModelInstaller]; the difference is the five-file pinned
 * set and the app-private `parakeet-eou/` directory.
 */
fun interface ParakeetEouModelInstaller {
    /**
     * Installs the pinned model set, emitting [ParakeetEouInstallStatus.Started]
     * and then exactly one terminal status. Cancelling collection cancels the
     * download; a failed attempt leaves no partial file behind.
     */
    fun install(): Flow<ParakeetEouInstallStatus>
}

/** Progress of the user-approved Parakeet-EOU model install. */
sealed interface ParakeetEouInstallStatus {
    /** The download/verification started; [totalBytes] is the pinned set size. */
    data class Started(
        val totalBytes: Long,
    ) : ParakeetEouInstallStatus

    /** All five files were verified and installed. */
    data object Completed : ParakeetEouInstallStatus

    /** The install failed; [message] is a safe, typed explanation. */
    data class Failed(
        val message: String,
    ) : ParakeetEouInstallStatus
}

/**
 * The production installer: the five pinned files over HTTPS into app-private
 * storage, via the existing OkHttp source and store.
 */
class DefaultParakeetEouModelInstaller(
    private val directory: File,
    private val store: ParakeetEouModelStore = ParakeetEouModelStore(directory),
    private val source: ParakeetEouModelSource = OkHttpParakeetEouModelSource(),
) : ParakeetEouModelInstaller {
    override fun install(): Flow<ParakeetEouInstallStatus> =
        flow {
            AppLog.i { "parakeet-eou: install start" }
            emit(ParakeetEouInstallStatus.Started(totalBytes = store.pinnedTotalBytes()))
            when (val result = store.install(source)) {
                is ParakeetEouInstallResult.Installed -> {
                    AppLog.i { "parakeet-eou: install completed bytes=${result.totalBytes}" }
                    emit(ParakeetEouInstallStatus.Completed)
                }

                is ParakeetEouInstallResult.Failed -> {
                    AppLog.w { "parakeet-eou: install failed code=${result.error.code}" }
                    emit(ParakeetEouInstallStatus.Failed(result.error.detail ?: "The Parakeet-EOU model could not be installed."))
                }
            }
        }
}

/**
 * The honest default when no installer is wired: a single typed failure rather
 * than a silent no-op that would leave the UI waiting forever.
 */
object UnavailableParakeetEouModelInstaller : ParakeetEouModelInstaller {
    override fun install(): Flow<ParakeetEouInstallStatus> =
        flow { emit(ParakeetEouInstallStatus.Failed("Parakeet-EOU downloads are not available in this build.")) }
}
