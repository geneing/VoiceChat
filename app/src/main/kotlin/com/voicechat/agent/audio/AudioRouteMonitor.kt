package com.voicechat.agent.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Reports the current input route and changes to it.
 *
 * Capture itself does not need to restart when the route changes: `AudioRecord`
 * follows the platform routing. The route is surfaced so diagnostics can record
 * which path produced the audio, and so an eventual UI can show it.
 */
interface AudioRouteMonitor {
    /** Best estimate of the active input route right now. */
    fun current(): AudioRoute

    /** Emits the current route and then each change, until collection is cancelled. */
    fun routes(): Flow<AudioRoute>
}

/**
 * [AudioRouteMonitor] backed by `AudioManager` device callbacks.
 *
 * On a platform that reports no input device (including Robolectric) both
 * [current] and [routes] degrade to [AudioRoute.UNKNOWN] rather than failing.
 */
class AndroidAudioRouteMonitor(
    context: Context,
    private val inputRoutes: () -> List<AudioRoute> = {
        context.applicationContext
            .getSystemService(AudioManager::class.java)
            ?.getDevices(AudioManager.GET_DEVICES_INPUTS)
            ?.map { classifyInputDevice(it.type) }
            .orEmpty()
    },
) : AudioRouteMonitor {
    private val audioManager: AudioManager? = context.applicationContext.getSystemService(AudioManager::class.java)

    override fun current(): AudioRoute = preferredRoute(inputRoutes())

    override fun routes(): Flow<AudioRoute> =
        callbackFlow {
            trySend(current())
            val manager = audioManager
            val callback = routeCallback { trySend(it) }
            val registered = manager != null && runCatching { manager.registerAudioDeviceCallback(callback, null) }.isSuccess
            if (!registered) {
                close()
                return@callbackFlow
            }
            awaitClose { runCatching { manager.unregisterAudioDeviceCallback(callback) } }
        }.distinctUntilChanged()

    private fun routeCallback(onChange: (AudioRoute) -> Unit): AudioDeviceCallback =
        object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = onChange(current())

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = onChange(current())
        }
}

/**
 * Chooses the route to report: an external input (headset, USB, Bluetooth) wins
 * over the built-in microphone, because when one is connected capture follows
 * it. Falls back to the built-in mic, then [AudioRoute.UNKNOWN].
 */
internal fun preferredRoute(routes: List<AudioRoute>): AudioRoute {
    if (routes.isEmpty()) return AudioRoute.UNKNOWN
    routes.firstOrNull { it.type != AudioRouteType.BUILTIN_MIC && it.type != AudioRouteType.UNKNOWN }?.let { return it }
    return routes.firstOrNull { it.type == AudioRouteType.BUILTIN_MIC } ?: AudioRoute.UNKNOWN
}

/** Maps an `AudioDeviceInfo` product type to the app's privacy-safe route kind. */
internal fun classifyInputDevice(type: Int): AudioRoute = AudioRoute(routeTypeForProductType(type))

/**
 * Pure product-type mapping. The `AudioDeviceInfo` constants are compile-time
 * ints, so this stays testable on the JVM without an Android device.
 */
internal fun routeTypeForProductType(productType: Int): AudioRouteType =
    when (productType) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> AudioRouteType.BUILTIN_MIC
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> AudioRouteType.WIRED_HEADSET
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> AudioRouteType.WIRED_HEADPHONES
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> AudioRouteType.BLUETOOTH_SCO
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> AudioRouteType.BLUETOOTH_A2DP
        AudioDeviceInfo.TYPE_BLE_HEADSET -> AudioRouteType.BLE_HEADSET
        AudioDeviceInfo.TYPE_USB_DEVICE -> AudioRouteType.USB_DEVICE
        AudioDeviceInfo.TYPE_USB_HEADSET -> AudioRouteType.USB_HEADSET
        AudioDeviceInfo.TYPE_TELEPHONY -> AudioRouteType.TELEPHONY
        AudioDeviceInfo.TYPE_HDMI -> AudioRouteType.HDMI
        else -> AudioRouteType.UNKNOWN
    }
