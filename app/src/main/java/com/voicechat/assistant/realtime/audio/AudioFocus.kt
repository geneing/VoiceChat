package com.voicechat.assistant.realtime.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.voicechat.assistant.core.AppLog
import com.voicechat.assistant.core.LogCategory

/**
 * Audio-focus, communication-route and capture-device handling for voice
 * sessions.
 *
 * A Bluetooth headset only supplies the microphone once the system is in
 * communication mode *and* the headset link is requested, so [enterVoiceMode]
 * selects the communication device (API 31+) or starts SCO (older releases) and
 * then reports the resulting input device so the recorder can pin to it.
 */
class AudioFocus(private val context: Context) {

    private var request: AudioFocusRequest? = null

    @Volatile
    private var voiceMode = false

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Reports the capture device to use, or null for the platform default. Fires
     * again when a headset connects or disconnects during a session, so capture
     * follows the microphone the user expects.
     */
    @Volatile
    var onInputRouteChanged: ((AudioDeviceInfo?) -> Unit)? = null

    /**
     * Reports the communication output device so the player can pin playback to
     * it. Without this the assistant keeps talking through the phone speaker
     * while the microphone is already on the headset.
     */
    @Volatile
    var onOutputRouteChanged: ((AudioDeviceInfo?) -> Unit)? = null

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refreshRoute()

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) =
            refreshRoute()
    }

    private val communicationDeviceListener =
        AudioManager.OnCommunicationDeviceChangedListener { device ->
            if (voiceMode) {
                AppLog.info(
                    LogCategory.AUDIO,
                    "Communication route changed",
                    device?.let { "${it.typeName()} (${it.productName}) id=${it.id}" }
                        ?: "system default",
                )
                val manager = context.getSystemService(AudioManager::class.java)
                    ?: return@OnCommunicationDeviceChangedListener
                // The platform can drop the SCO route mid-session (idle link,
                // another app taking the mode). Put it back when a headset is
                // still connected, otherwise answers fall out of the earpiece.
                if (shouldReassert(manager, device)) {
                    AppLog.warn(
                        LogCategory.AUDIO,
                        "Communication route lost; re-applying headset route",
                    )
                    runCatching {
                        if (manager.mode != AudioManager.MODE_IN_COMMUNICATION) {
                            manager.mode = AudioManager.MODE_IN_COMMUNICATION
                        }
                    }
                    applyRoute(manager)
                }
                notifyInputRoute(manager)
            }
        }

    private fun shouldReassert(manager: AudioManager, current: AudioDeviceInfo?): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        val headsetAvailable = manager.availableCommunicationDevices.any {
            it.type in HEADSET_TYPES && it.isSink
        }
        if (!headsetAvailable) return false
        return current == null || current.type !in HEADSET_TYPES
    }

    fun acquire() {
        if (request != null) return
        val manager = context.getSystemService(AudioManager::class.java) ?: return
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes)
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener { change ->
                AppLog.debug(LogCategory.AUDIO, "Audio focus changed: $change")
            }
            .build()
        val result = manager.requestAudioFocus(focusRequest)
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            request = focusRequest
        } else {
            AppLog.warn(LogCategory.AUDIO, "Audio focus not granted ($result)")
        }
    }

    /**
     * Put the platform in communication mode so the speaker output is used as
     * the AEC reference and the microphone gets the hardware echo canceller,
     * while a connected headset keeps (or takes over) both directions.
     */
    fun enterVoiceMode() {
        val manager = context.getSystemService(AudioManager::class.java) ?: return
        if (voiceMode) {
            applyRoute(manager)
            notifyInputRoute(manager)
            return
        }
        voiceMode = true
        runCatching {
            manager.mode = AudioManager.MODE_IN_COMMUNICATION
            manager.registerAudioDeviceCallback(deviceCallback, mainHandler)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                manager.addOnCommunicationDeviceChangedListener(
                    context.mainExecutor,
                    communicationDeviceListener,
                )
            }
        }.onFailure {
            AppLog.warn(LogCategory.AUDIO, "Could not enter voice audio mode", it.message)
        }
        applyRoute(manager)
        notifyInputRoute(manager)
    }

    fun exitVoiceMode() {
        val manager = context.getSystemService(AudioManager::class.java) ?: return
        if (!voiceMode) return
        voiceMode = false
        runCatching { manager.unregisterAudioDeviceCallback(deviceCallback) }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                manager.removeOnCommunicationDeviceChangedListener(communicationDeviceListener)
                if (manager.communicationDevice != null) manager.clearCommunicationDevice()
            } else {
                stopLegacySco(manager)
                manager.isSpeakerphoneOn = false
            }
            if (manager.mode == AudioManager.MODE_IN_COMMUNICATION) {
                manager.mode = AudioManager.MODE_NORMAL
            }
        }.onFailure {
            AppLog.warn(LogCategory.AUDIO, "Could not leave voice audio mode", it.message)
        }
        AppLog.info(LogCategory.AUDIO, "Voice audio mode off")
        onOutputRouteChanged?.invoke(null)
    }

    /** Capture device the recorder should pin to, or null for the default. */
    fun preferredInputDevice(): AudioDeviceInfo? {
        val manager = context.getSystemService(AudioManager::class.java) ?: return null
        if (!voiceMode) return null
        return headsetInput(manager)
    }

    fun release() {
        val focusRequest = request
        val manager = context.getSystemService(AudioManager::class.java)
        if (focusRequest != null) {
            manager?.abandonAudioFocusRequest(focusRequest)
            request = null
        }
        exitVoiceMode()
    }

    private fun refreshRoute() {
        val manager = context.getSystemService(AudioManager::class.java) ?: return
        if (!voiceMode) return
        applyRoute(manager)
        notifyInputRoute(manager)
    }

    private fun notifyInputRoute(manager: AudioManager) {
        val actualInput = headsetInput(manager)
        val requestedOutput = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            manager.communicationDevice
        } else {
            manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .firstOrNull { it.type in LEGACY_BLUETOOTH_HEADSET_OUTPUT_TYPES }
        }
        val requestedInput = actualInput?.takeIf { candidate ->
            requestedOutput == null ||
                candidate.type == requestedOutput.type ||
                (requestedOutput.address.isNotBlank() &&
                    candidate.address == requestedOutput.address) ||
                (requestedOutput.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP &&
                    candidate.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
        }
        AppLog.info(
            LogCategory.AUDIO,
            "Capture route",
            actualInput?.let { "${it.typeName()} (${it.productName})" } ?: "system default",
        )
        if (requestedOutput != null && requestedInput == null) {
            AppLog.warn(
                LogCategory.AUDIO,
                "Communication route has no matching input",
                "output=${requestedOutput.typeName()} (${requestedOutput.productName})",
            )
        }
        onInputRouteChanged?.invoke(requestedInput)
        onOutputRouteChanged?.invoke(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) manager.communicationDevice else null,
        )
    }

    private fun applyRoute(manager: AudioManager) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val target = communicationDevice(manager)
                if (target != null) {
                    val current = manager.communicationDevice
                    if (current?.id != target.id) {
                        val accepted = manager.setCommunicationDevice(target)
                        AppLog.info(
                            LogCategory.AUDIO,
                            if (accepted) "Communication device selected" else "Communication device rejected",
                            "${target.typeName()} (${target.productName}) id=${target.id}",
                        )
                    }
                } else {
                    if (manager.communicationDevice != null) manager.clearCommunicationDevice()
                    if (manager.availableCommunicationDevices.any {
                            it.type in EXTERNAL_OUTPUT_TYPES && it.isSink
                        }
                    ) {
                        if (manager.isSpeakerphoneOn) manager.isSpeakerphoneOn = false
                        AppLog.info(LogCategory.AUDIO, "External output has no communication route")
                    } else {
                        // Nothing external: the loudspeaker is both the output and
                        // the AEC reference.
                        if (!manager.isSpeakerphoneOn) manager.isSpeakerphoneOn = true
                        AppLog.info(LogCategory.AUDIO, "No headset communication device available")
                    }
                }
            } else {
                routeLegacy(manager)
            }
        }.onFailure {
            AppLog.warn(LogCategory.AUDIO, "Audio route change failed", it.message)
        }
    }

    /**
     * API 26-30: there is no `setCommunicationDevice`, so a Bluetooth
     * microphone needs an explicit SCO request. Every call is gated on the
     * current state so the device callback cannot bounce between routes.
     */
    private fun routeLegacy(manager: AudioManager) {
        val outputs = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        if (outputs.any { it.type in LEGACY_BLUETOOTH_HEADSET_OUTPUT_TYPES }) {
            // Classic headphones normally appear as A2DP before SCO is active.
            // Requesting SCO is what makes their HFP microphone available.
            startLegacySco(manager)
            if (manager.isSpeakerphoneOn) manager.isSpeakerphoneOn = false
            return
        }
        if (headsetInput(manager) != null) {
            stopLegacySco(manager)
            if (manager.isSpeakerphoneOn) manager.isSpeakerphoneOn = false
            return
        }
        stopLegacySco(manager)
        val speaker = outputs.none { it.type in EXTERNAL_OUTPUT_TYPES }
        if (manager.isSpeakerphoneOn != speaker) manager.isSpeakerphoneOn = speaker
    }

    private fun startLegacySco(manager: AudioManager) {
        @Suppress("DEPRECATION")
        if (!manager.isBluetoothScoOn && manager.isBluetoothScoAvailableOffCall) {
            manager.startBluetoothSco()
            manager.isBluetoothScoOn = true
            AppLog.info(LogCategory.AUDIO, "Bluetooth SCO started")
        }
    }

    private fun stopLegacySco(manager: AudioManager) {
        @Suppress("DEPRECATION")
        if (manager.isBluetoothScoOn) {
            manager.stopBluetoothSco()
            manager.isBluetoothScoOn = false
            AppLog.info(LogCategory.AUDIO, "Bluetooth SCO stopped")
        }
    }

    /** API 31+: pick the communication device the framework should route to. */
    private fun communicationDevice(manager: AudioManager): AudioDeviceInfo? {
        val available = manager.availableCommunicationDevices
        val bluetooth = HEADSET_PREFERENCE
            .take(BLUETOOTH_PREFERENCE_COUNT)
            .firstNotNullOfOrNull { type -> available.firstOrNull { it.type == type && it.isSink } }
        val target = bluetooth ?: HEADSET_PREFERENCE
            .drop(BLUETOOTH_PREFERENCE_COUNT)
            .firstNotNullOfOrNull { type -> available.firstOrNull { it.type == type && it.isSink } }
        return target ?: if (available.any { it.type in EXTERNAL_OUTPUT_TYPES && it.isSink }) {
            null
        } else {
            available.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER && it.isSink
            }
        }
    }

    /** A headset microphone currently offered by the platform, best first. */
    private fun headsetInput(manager: AudioManager): AudioDeviceInfo? {
        val inputs = manager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        val outputs = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val requestedOutput = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            manager.communicationDevice
        } else {
            outputs.firstOrNull { it.type in LEGACY_BLUETOOTH_HEADSET_OUTPUT_TYPES }
        }
        val matchingInput = requestedOutput?.let { output ->
            inputs.firstOrNull {
                it.type == output.type || (output.address.isNotBlank() && it.address == output.address)
            }
        }
        return matchingInput ?: HEADSET_PREFERENCE
            .take(BLUETOOTH_PREFERENCE_COUNT)
            .firstNotNullOfOrNull { type -> inputs.firstOrNull { it.type == type } }
            ?: HEADSET_PREFERENCE
                .drop(BLUETOOTH_PREFERENCE_COUNT)
                .firstNotNullOfOrNull { type -> inputs.firstOrNull { it.type == type } }
            ?: inputs.firstOrNull { input ->
                outputs.any { output ->
                    output.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP &&
                        input.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO &&
                        output.address == input.address
                }
            }
    }

    private companion object {
        /**
         * Headsets whose microphone should replace the phone's own, best first.
         * A2DP and BLE speakers carry no microphone and are not listed.
         */
        const val BLUETOOTH_PREFERENCE_COUNT = 2

        /** Device types that mean "a headset can carry the call audio". */
        val HEADSET_TYPES = setOf(
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_HEARING_AID,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
        )

        val HEADSET_PREFERENCE = listOf(
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_HEARING_AID,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
        )

        /** Bluetooth outputs from which API 26-30 may request a headset mic. */
        val LEGACY_BLUETOOTH_HEADSET_OUTPUT_TYPES = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
        )

        /** Outputs that mean "do not force the loudspeaker". */
        val EXTERNAL_OUTPUT_TYPES = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_HEARING_AID,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        )
    }
}
