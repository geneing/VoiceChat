package com.voicechat.agent.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager

/**
 * Whether the app currently holds the microphone permission.
 *
 * The capture path checks this before it touches `AudioRecord`, so a denied or
 * revoked permission surfaces as the typed
 * [com.voicechat.agent.domain.ErrorCode.AUDIO_PERMISSION_DENIED] error instead
 * of a platform `SecurityException`. The check is behind an interface so the
 * capture tests can deny or revoke it without an Android device.
 */
fun interface MicrophonePermission {
    /** True only when `RECORD_AUDIO` is granted right now. */
    fun isGranted(): Boolean
}

/** [MicrophonePermission] backed by the platform permission state. */
class AndroidMicrophonePermission(
    private val context: Context,
) : MicrophonePermission {
    override fun isGranted(): Boolean = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    companion object {
        /** The permission this app requests at the point of mic capture. */
        const val PERMISSION: String = Manifest.permission.RECORD_AUDIO
    }
}
