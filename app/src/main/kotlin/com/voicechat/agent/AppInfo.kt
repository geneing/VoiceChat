package com.voicechat.agent

/**
 * Platform-free facts about the app that both UI and unit tests can rely on.
 *
 * The intended voice path uses on-device speech, which is only broadly available
 * at API 31+ (see `docs/decisions.md` §1). [MIN_SUPPORTED_API] mirrors `minSdk` in
 * `app/build.gradle.kts`; keep the two in sync when the pinned decision changes.
 */
object AppInfo {
    /** Lowest Android API level the app is built for. */
    const val MIN_SUPPORTED_API: Int = 31

    /** True when [apiLevel] can host the on-device speech path the app targets. */
    fun supportsOnDeviceSpeech(apiLevel: Int): Boolean = apiLevel >= MIN_SUPPORTED_API
}
