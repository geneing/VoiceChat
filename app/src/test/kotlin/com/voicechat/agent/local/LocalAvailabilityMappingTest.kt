package com.voicechat.agent.local

import com.voicechat.agent.contracts.ModelAvailability
import com.voicechat.agent.domain.ErrorCode
import com.voicechat.agent.domain.UnavailableReason
import com.voicechat.agent.domain.VoiceAgentError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M20 acceptance for local availability mapping: only a real `READY` state
 * becomes ready, and every other path stays an explicit typed unavailable state.
 */
class LocalAvailabilityMappingTest {
    @Test
    fun aReadyProbeBecomesReady() {
        val availability =
            LocalAvailabilityMapper.fromProbe(LocalModels.geminiNano, LocalProbeResult.Ready, systemManaged = true)

        assertTrue(availability is LocalModelAvailability.Ready)
        assertTrue(availability.asModelAvailability() is ModelAvailability.Ready)
    }

    @Test
    fun aSystemManagedDownloadableModelIsNotReadyAndNotAppDownloadable() {
        val availability =
            LocalAvailabilityMapper.fromProbe(
                LocalModels.geminiNano,
                LocalProbeResult.DownloadRequired,
                systemManaged = true,
            )

        assertTrue(availability is LocalModelAvailability.Unavailable)
        val unavailable = availability as LocalModelAvailability.Unavailable
        assertEquals(LocalModelState.UNPROVISIONED, unavailable.state)
        assertEquals(UnavailableReason.MODEL_NOT_PROVISIONED, unavailable.reason)
        // DownloadRequired is app-managed only; AICore is never downloaded here.
        assertFalse(availability is LocalModelAvailability.DownloadRequired)
        assertFalse(availability.asModelAvailability() is ModelAvailability.Ready)
    }

    @Test
    fun anAppManagedDownloadableModelIsDownloadRequired() {
        val availability =
            LocalAvailabilityMapper.fromProbe(LocalModels.geminiNano, LocalProbeResult.DownloadRequired, systemManaged = false)

        assertTrue(availability is LocalModelAvailability.DownloadRequired)
        assertTrue(availability.asModelAvailability() is ModelAvailability.DownloadRequired)
    }

    @Test
    fun provisioningIsExplicitlyUnavailable() {
        val availability =
            LocalAvailabilityMapper.fromProbe(LocalModels.geminiNano, LocalProbeResult.Provisioning, systemManaged = true)

        assertEquals(LocalModelState.PROVISIONING, (availability as LocalModelAvailability.Unavailable).state)
    }

    @Test
    fun aDeviceUnsupportedProbeKeepsTheTypedReason() {
        val availability =
            LocalAvailabilityMapper.fromProbe(
                LocalModels.geminiNano,
                LocalProbeResult.Unavailable(UnavailableReason.DEVICE_UNSUPPORTED, "no Gemini Nano"),
                systemManaged = true,
            )

        val unavailable = availability as LocalModelAvailability.Unavailable
        assertEquals(LocalModelState.DEVICE_UNSUPPORTED, unavailable.state)
        assertEquals(UnavailableReason.DEVICE_UNSUPPORTED, unavailable.reason)
    }

    @Test
    fun aFailedProbeNeverBecomesReady() {
        val availability =
            LocalAvailabilityMapper.fromProbe(
                LocalModels.geminiNano,
                LocalProbeResult.Failed(VoiceAgentError(ErrorCode.MODEL_UNAVAILABLE, "status check failed")),
                systemManaged = true,
            )

        assertFalse(availability.asModelAvailability() is ModelAvailability.Ready)
        assertEquals(ErrorCode.MODEL_UNAVAILABLE, (availability.asModelAvailability() as ModelAvailability.Unavailable).error.code)
    }

    @Test
    fun installStateMapsToDownloadRequiredReadyAndCorrupt() {
        val descriptor = LocalModels.geminiNano

        assertTrue(
            LocalAvailabilityMapper.fromInstallState(descriptor, LocalInstallState.NotInstalled) is
                LocalModelAvailability.DownloadRequired,
        )
        assertTrue(
            LocalAvailabilityMapper.fromInstallState(descriptor, LocalInstallState.Installed("m.litertlm", 10)) is
                LocalModelAvailability.Ready,
        )

        val corrupt =
            LocalAvailabilityMapper.fromInstallState(
                descriptor,
                LocalInstallState.IntegrityFailed(expectedSha256 = "a".repeat(64), actualSha256 = "b".repeat(64)),
            ) as LocalModelAvailability.Unavailable
        assertEquals(LocalModelState.CORRUPT, corrupt.state)
        assertEquals(ErrorCode.MODEL_CORRUPT, corrupt.error.code)
    }

    @Test
    fun theExplicitStatesMapToTypedUnavailableReasons() {
        val descriptor = LocalModels.geminiNano

        val missing = LocalAvailabilityMapper.fromState(descriptor, LocalModelState.MISSING) as LocalModelAvailability.Unavailable
        assertEquals(LocalModelState.MISSING, missing.state)
        assertEquals(ErrorCode.MODEL_UNAVAILABLE, missing.error.code)

        val insufficient =
            LocalAvailabilityMapper.fromState(descriptor, LocalModelState.INSUFFICIENT_RESOURCES) as
                LocalModelAvailability.Unavailable
        assertEquals(LocalModelState.INSUFFICIENT_RESOURCES, insufficient.state)

        val notAllowListed =
            LocalAvailabilityMapper.fromState(descriptor, LocalModelState.NOT_ALLOW_LISTED) as LocalModelAvailability.Unavailable
        assertEquals(LocalModelState.NOT_ALLOW_LISTED, notAllowListed.state)
    }

    @Test
    fun onlyTheReadyStateMapsToReady() {
        val nonReady =
            listOf(
                LocalModelState.DOWNLOAD_REQUIRED,
                LocalModelState.PROVISIONING,
                LocalModelState.UNPROVISIONED,
                LocalModelState.MISSING,
                LocalModelState.CORRUPT,
                LocalModelState.INSUFFICIENT_RESOURCES,
                LocalModelState.DEVICE_UNSUPPORTED,
                LocalModelState.NOT_ALLOW_LISTED,
                LocalModelState.UNKNOWN,
            )

        nonReady.forEach { state ->
            val availability = LocalAvailabilityMapper.fromState(LocalModels.geminiNano, state)
            assertFalse("$state must not be ready", availability.asModelAvailability() is ModelAvailability.Ready)
        }
    }
}
