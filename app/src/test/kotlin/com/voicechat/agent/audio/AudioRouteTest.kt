package com.voicechat.agent.audio

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for route classification. The `AudioDeviceInfo` type
 * constants are compile-time ints, so no Android runtime is needed.
 */
class AudioRouteTest {
    @Test
    fun mapsPlatformProductTypesToRouteKinds() {
        assertEquals(AudioRouteType.BUILTIN_MIC, routeTypeForProductType(AudioDeviceInfo.TYPE_BUILTIN_MIC))
        assertEquals(AudioRouteType.BLUETOOTH_SCO, routeTypeForProductType(AudioDeviceInfo.TYPE_BLUETOOTH_SCO))
        assertEquals(AudioRouteType.BLUETOOTH_A2DP, routeTypeForProductType(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
        assertEquals(AudioRouteType.USB_HEADSET, routeTypeForProductType(AudioDeviceInfo.TYPE_USB_HEADSET))
        assertEquals(AudioRouteType.WIRED_HEADSET, routeTypeForProductType(AudioDeviceInfo.TYPE_WIRED_HEADSET))
        assertEquals(AudioRouteType.UNKNOWN, routeTypeForProductType(Int.MIN_VALUE))
    }

    @Test
    fun anExternalInputWinsOverTheBuiltinMicrophone() {
        val route =
            preferredRoute(
                listOf(
                    AudioRoute(AudioRouteType.BUILTIN_MIC),
                    AudioRoute(AudioRouteType.USB_HEADSET),
                ),
            )

        assertEquals(AudioRouteType.USB_HEADSET, route.type)
    }

    @Test
    fun noReportedRouteIsUnknown() {
        assertEquals(AudioRoute.UNKNOWN, preferredRoute(emptyList()))
    }

    @Test
    fun theDiagnosticLabelNeverContainsADeviceName() {
        assertEquals("BLUETOOTH_SCO", AudioRoute(AudioRouteType.BLUETOOTH_SCO).label)
    }

    @Test
    fun bluetoothKindsAreFlagged() {
        assertTrue(AudioRouteType.BLUETOOTH_SCO.isBluetooth)
        assertTrue(AudioRouteType.BLE_HEADSET.isBluetooth)
        assertFalse(AudioRouteType.BUILTIN_MIC.isBluetooth)
    }
}
