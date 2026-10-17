package com.voicechat.agent.audio

import android.media.audiofx.AcousticEchoCanceler
import com.voicechat.agent.log.AppLog

/**
 * What happened when the loop tried to suppress echo on a capture session.
 *
 * The assistant is audible on the same device that is listening, so a reply can
 * reach the microphone and look like user speech. Android's echo canceller is
 * **optional**: it is attached to one `AudioRecord` session, `isAvailable()` may
 * be false, and a device may accept the session and still not cancel anything.
 * The state is therefore reported explicitly and never assumed, so a VAD that
 * fires during playback is visible as "the canceller was not available" rather
 * than an unexplained self-interruption.
 */
enum class EchoCancellationState {
    /** The platform canceller was created for this session and reported enabled. */
    ENABLED,

    /** The capture session did not expose an audio session id to attach to. */
    NO_SESSION,

    /** The platform reports no echo canceller on this device or route. */
    UNAVAILABLE,

    /** A canceller was created but would not enable, or attaching threw. */
    FAILED,
}

/** The echo-cancellation outcome for one capture session. */
data class EchoCancellation(
    val state: EchoCancellationState,
    /** A short, non-sensitive explanation; never audio or transcript content. */
    val detail: String? = null,
) {
    /** True only when the platform confirmed the canceller is enabled. */
    val enabled: Boolean get() = state == EchoCancellationState.ENABLED
}

/**
 * Attaches and releases what suppresses echo for one capture session.
 *
 * [attach] is called after the recorder has started, with the session's audio
 * session id, and the returned [EchoCancellation] is held for the session's
 * lifetime. [release] must be idempotent and is safe to call from the capture
 * thread.
 */
interface EchoCanceller {
    /** Attaches for [audioSessionId], or reports why it could not. */
    fun attach(audioSessionId: Int?): EchoCancellation

    /** Releases a previously attached canceller. Idempotent. */
    fun release()

    /** Never attaches anything; the default for a non-platform capture path. */
    object NONE : EchoCanceller {
        override fun attach(audioSessionId: Int?): EchoCancellation =
            EchoCancellation(EchoCancellationState.UNAVAILABLE, "echo cancellation is not configured")

        override fun release() = Unit
    }
}

/**
 * [EchoCanceller] over the platform [AcousticEchoCanceler].
 *
 * It is deliberately the only Android-touching piece: the state vocabulary and
 * the decision of what a given platform answer means ([echoCancellationState])
 * are pure and JVM-tested, and the session attach/release is the thin part that
 * needs a device.
 */
class PlatformEchoCanceller : EchoCanceller {
    @Volatile
    private var canceller: AcousticEchoCanceler? = null

    override fun attach(audioSessionId: Int?): EchoCancellation {
        if (audioSessionId == null || audioSessionId == 0) {
            return EchoCancellation(
                state = EchoCancellationState.NO_SESSION,
                detail = "the recorder did not report an audio session id",
            )
        }
        if (!AcousticEchoCanceler.isAvailable()) {
            return EchoCancellation(
                state = EchoCancellationState.UNAVAILABLE,
                detail = "the platform reports no echo canceller on this device",
            )
        }
        return try {
            val created = AcousticEchoCanceler.create(audioSessionId)
            if (created == null) {
                EchoCancellation(EchoCancellationState.FAILED, "the platform refused an echo canceller for this session")
            } else {
                canceller = created
                val enabled = created.enabled
                val state = echoCancellationState(available = true, reportedEnabled = enabled)
                AppLog.i { "capture: echo canceller state=$state session=$audioSessionId" }
                EchoCancellation(state, if (enabled) null else "the canceller would not enable")
            }
        } catch (failure: Throwable) {
            AppLog.w(failure) { "capture: attaching the echo canceller failed" }
            EchoCancellation(EchoCancellationState.FAILED, "attaching the echo canceller threw")
        }
    }

    override fun release() {
        val attached = canceller ?: return
        canceller = null
        runCatching { attached.enabled = false }
        runCatching { attached.release() }
    }
}

/**
 * The state that follows from what the platform reported.
 *
 * Pure, so the three outcomes (no canceller, canceller that enables, canceller
 * that will not) are unit-tested without a device.
 */
internal fun echoCancellationState(
    available: Boolean,
    reportedEnabled: Boolean,
): EchoCancellationState =
    when {
        !available -> EchoCancellationState.UNAVAILABLE
        reportedEnabled -> EchoCancellationState.ENABLED
        else -> EchoCancellationState.FAILED
    }
