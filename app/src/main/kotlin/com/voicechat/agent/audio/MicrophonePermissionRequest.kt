package com.voicechat.agent.audio

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** Point-of-use microphone permission state. */
enum class MicrophonePermissionStatus {
    /** Not granted yet and the user has not refused this request. */
    NOT_REQUESTED,

    /** Granted right now. */
    GRANTED,

    /** Refused with a rationale still to show, or refused this session. */
    DENIED,
}

/**
 * Point-of-use microphone permission controller.
 *
 * [request] launches the system request; [status] reflects the current grant.
 * The status is refreshed on `ON_RESUME` so a change made in system settings is
 * observed when the user returns. The permission is requested only when the
 * caller invokes [request] at the moment capture is wanted — never at app start
 * (`docs/privacy-and-security.md`).
 */
@Stable
class MicrophonePermissionController internal constructor(
    val status: MicrophonePermissionStatus,
    val request: () -> Unit,
)

/**
 * Remembers a [MicrophonePermissionController] for the current composition.
 *
 * Call it from the voice control's composable and invoke
 * [MicrophonePermissionController.request] from the microphone button; do not
 * request the permission from `MainActivity.onCreate` or the app entry point.
 */
@Composable
fun rememberMicrophonePermissionController(): MicrophonePermissionController {
    val context = LocalContext.current
    var status by remember { mutableStateOf(currentMicrophonePermissionStatus(context)) }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            status = if (granted) MicrophonePermissionStatus.GRANTED else MicrophonePermissionStatus.DENIED
        }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    status = currentMicrophonePermissionStatus(context)
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    return remember(status, launcher) {
        MicrophonePermissionController(status) { launcher.launch(AndroidMicrophonePermission.PERMISSION) }
    }
}

/** Reads the current permission state, distinguishing "not asked" from "denied". */
internal fun currentMicrophonePermissionStatus(context: Context): MicrophonePermissionStatus {
    if (AndroidMicrophonePermission(context).isGranted()) return MicrophonePermissionStatus.GRANTED
    val activity = context.findActivity() ?: return MicrophonePermissionStatus.NOT_REQUESTED
    return if (activity.shouldShowRequestPermissionRationale(AndroidMicrophonePermission.PERMISSION)) {
        MicrophonePermissionStatus.DENIED
    } else {
        MicrophonePermissionStatus.NOT_REQUESTED
    }
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
