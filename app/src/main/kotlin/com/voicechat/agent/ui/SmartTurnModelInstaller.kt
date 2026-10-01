package com.voicechat.agent.ui

import com.voicechat.agent.log.AppLog
import com.voicechat.agent.turn.OkHttpSmartTurnModelSource
import com.voicechat.agent.turn.SmartTurnInstallResult
import com.voicechat.agent.turn.SmartTurnModelSource
import com.voicechat.agent.turn.SmartTurnModelStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File

/**
 * Downloads and installs the pinned Smart Turn v3.2 artifact from Settings (M10).
 *
 * The artifact is not bundled in the APK: it is fetched from the revision-pinned
 * URL on the user's explicit action, verified against the exact byte size and
 * SHA-256, and installed atomically in app-private storage by
 * [SmartTurnModelStore.install]. This seam keeps [SettingsViewModel] free of the
 * store/network types so the flow is a JVM test with a fake.
 */
fun interface SmartTurnModelInstaller {
    /**
     * Installs the pinned artifact, emitting [SmartTurnInstallStatus.Started] and
     * then exactly one terminal status. Cancelling collection cancels the
     * download; a failed attempt leaves no partial file behind.
     */
    fun install(): Flow<SmartTurnInstallStatus>
}

/** Progress of the user-approved Smart Turn model install. */
sealed interface SmartTurnInstallStatus {
    /** The download/verification started; [totalBytes] is the pinned size. */
    data class Started(
        val totalBytes: Long,
    ) : SmartTurnInstallStatus

    /** The artifact was verified and installed. */
    data object Completed : SmartTurnInstallStatus

    /** The install failed; [message] is a safe, typed explanation. */
    data class Failed(
        val message: String,
    ) : SmartTurnInstallStatus
}

/**
 * The production installer: the pinned artifact over HTTPS into app-private
 * storage, via the existing OkHttp source and store.
 */
class DefaultSmartTurnModelInstaller(
    private val directory: File,
    private val store: SmartTurnModelStore = SmartTurnModelStore(directory),
    private val source: SmartTurnModelSource = OkHttpSmartTurnModelSource(),
) : SmartTurnModelInstaller {
    override fun install(): Flow<SmartTurnInstallStatus> =
        flow {
            AppLog.i { "smart-turn: install start" }
            emit(SmartTurnInstallStatus.Started(totalBytes = store.pinnedSizeBytes()))
            when (val result = store.install(source)) {
                is SmartTurnInstallResult.Installed -> {
                    AppLog.i { "smart-turn: install completed bytes=${result.sizeBytes}" }
                    emit(SmartTurnInstallStatus.Completed)
                }

                is SmartTurnInstallResult.Failed -> {
                    AppLog.w { "smart-turn: install failed code=${result.error.code}" }
                    emit(SmartTurnInstallStatus.Failed(result.error.detail ?: "The Smart Turn model could not be installed."))
                }
            }
        }
}

/**
 * The honest default when no installer is wired: a single typed failure rather
 * than a silent no-op that would leave the UI waiting forever.
 */
object UnavailableSmartTurnModelInstaller : SmartTurnModelInstaller {
    override fun install(): Flow<SmartTurnInstallStatus> =
        flow { emit(SmartTurnInstallStatus.Failed("Smart Turn downloads are not available in this build.")) }
}
