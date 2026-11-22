package com.voicechat.assistant.realtime.tools

internal fun isPhoneConfirmation(input: String, whatsapp: Boolean): Boolean {
    val phrase = if (whatsapp) "open whatsapp draft" else "confirm call"
    val reply = input.trim().lowercase().trimEnd('.', '!', '?')
        .removePrefix("yes, ").removePrefix("yes ")
    return reply == phrase
}
