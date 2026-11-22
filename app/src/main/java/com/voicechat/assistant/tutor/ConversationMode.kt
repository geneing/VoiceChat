package com.voicechat.assistant.tutor

enum class ConversationMode(val label: String) {
    ASSISTANT("Assistant"),
    SPANISH_TUTOR("Spanish tutor");

    companion object {
        /** Only explicit app commands: quoted lesson/translation text must remain an exercise. */
        fun command(text: String): ConversationMode? {
            val clean = text.lowercase(java.util.Locale.ROOT).trim()
                .trimEnd('.', '!', '?').replace(Regex("\\s+"), " ")
            return when (clean) {
                "switch to spanish tutor", "switch to spanish tutor mode", "start spanish tutor",
                "open spanish tutor", "spanish tutor mode", "cambia a tutor de español" -> SPANISH_TUTOR
                "switch to voice chat", "switch to assistant", "switch to assistant mode",
                "back to voice chat", "exit spanish tutor", "salir del tutor", "volver al asistente" -> ASSISTANT
                else -> null
            }
        }
    }
}
