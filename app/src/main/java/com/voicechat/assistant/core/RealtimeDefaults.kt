package com.voicechat.assistant.core

import com.voicechat.assistant.BuildConfig
import com.voicechat.assistant.data.model.TransportKind
import com.voicechat.assistant.tutor.ConversationMode

/**
 * A voice enrolled with `qwen-voice-enrollment`, plus the name it shows in
 * Settings. [mode] keeps the two conversations apart: the assistant speaks with
 * its own voices, the Spanish tutor with the Spanish ones, so neither mode
 * offers a voice recorded for the other.
 */
data class ClonedVoice(
    val id: String,
    val label: String,
    val mode: ConversationMode = ConversationMode.ASSISTANT,
)

data class ModelInfo(
    val id: String,
    val label: String,
    val description: String,
    val voices: List<String>,
    val defaultVoice: String,
    val turnDetectionOptions: List<String>,
    val defaultTurnDetection: String,
    val supportsTools: Boolean,
    val transcriptionModel: String,
    val supportedTransports: Set<TransportKind>,
    /**
     * `enable_search` runs the model in "agent mode", which rejects any `tools`
     * array ("Agent mode does not support tools"). Only models that accept both
     * can search server-side while client tools are attached.
     */
    val supportsToolsWithSearch: Boolean = true,
    /**
     * Voices enrolled with `qwen-voice-enrollment` for this account. The vendor
     * names them `qwen-omni-vc-<name>-voice-…`, with no model id in front, so
     * they cannot be recognised by [supportsVoice]'s prefix rule and are listed
     * explicitly. A clone belongs to the model passed as `target_model`.
     */
    val clonedVoices: List<ClonedVoice> = emptyList(),
) {
    /** The clones enrolled for [mode], in the order Settings lists them. */
    fun clonesFor(mode: ConversationMode = ConversationMode.ASSISTANT): List<ClonedVoice> =
        clonedVoices.filter { it.mode == mode }

    /**
     * The voices [mode] may use. A mode with clones of its own uses only those —
     * the vendor's system voices are not offered at all, and a voice recorded for
     * the other conversation never reaches this one. The system list is the
     * fallback for an account that has no clone yet.
     */
    fun allowedVoices(mode: ConversationMode = ConversationMode.ASSISTANT): List<String> {
        val clones = clonesFor(mode)
        return if (clones.isNotEmpty()) clones.map { it.id } else voices
    }

    /** Picker entries for [mode]: a clone shows its label, a system voice its own name. */
    fun voiceOptions(
        mode: ConversationMode = ConversationMode.ASSISTANT,
    ): List<Pair<String, String>> {
        val clones = clonesFor(mode)
        return if (clones.isNotEmpty()) {
            clones.map { it.label to it.id }
        } else {
            voices.map { it to it }
        }
    }

    /** The mode's first clone, or the model default when that mode has none. */
    fun defaultVoiceFor(mode: ConversationMode = ConversationMode.ASSISTANT): String =
        clonesFor(mode).firstOrNull()?.id ?: defaultVoice

    /**
     * Anything outside the mode's [allowedVoices] must not reach `session.update`
     * or the server answers "voice not supported". The vendor also names clones
     * `<target-model>-myvoice-…`, so that form is accepted by a mode that has no
     * clone listed.
     */
    fun supportsVoice(voice: String, mode: ConversationMode = ConversationMode.ASSISTANT): Boolean {
        if (voice.isBlank()) return false
        if (voice in allowedVoices(mode)) return true
        return clonesFor(mode).isEmpty() && voice.startsWith("$id-")
    }

    /** The name a voice shows in Settings: the clone's label, or the voice id. */
    fun labelFor(voice: String): String =
        clonedVoices.firstOrNull { it.id == voice }?.label ?: voice

    fun resolveVoice(
        voice: String,
        mode: ConversationMode = ConversationMode.ASSISTANT,
    ): String = if (supportsVoice(voice, mode)) voice else defaultVoiceFor(mode)

    /**
     * Client tools win when the model cannot combine them with agent mode: the
     * client `web_search` stays available instead of failing the session.
     */
    fun resolveNativeSearch(requested: Boolean, clientToolsEnabled: Boolean): Boolean =
        requested && supportsTools && (supportsToolsWithSearch || !clientToolsEnabled)
}

/** Static model catalogue and defaults shared by settings and the session layer. */
object RealtimeDefaults {

    const val OMNI_MODEL = "qwen3.8-omni-flash-realtime"
    const val AUDIO_MODEL = "qwen-audio-3.1-realtime-flash"

    /**
     * Voice cloned from `Herm.wav` with `qwen-voice-enrollment`, enrolled for
     * [OMNI_MODEL] and verified against the live realtime API. Cloned voices
     * are account-scoped: any other workspace must create its own voice and
     * replace this entry (see the voice-cloning notes in AGENTS.md).
     */
    const val CLONED_OMNI_VOICE = "qwen-omni-vc-herm-voice-20261122161820603-725d"
    const val CLONED_OMNI_VOICE_LABEL = "Herm"
    /** Voice cloned from `Zach.wav`, same account and model, second to Herm. */
    const val CLONED_OMNI_VOICE_ZACH = "qwen-omni-vc-zach-voice-20261122162925632-ba24"
    const val CLONED_OMNI_VOICE_ZACH_LABEL = "Zach"
    /** Voice cloned from `Janice.wav`, same account and model. */
    const val CLONED_OMNI_VOICE_JANICE = "qwen-omni-vc-janice-voice-20261122163256610-f953"
    const val CLONED_OMNI_VOICE_JANICE_LABEL = "Janice"
    /** Voices cloned from `Hope.mp3`, `Kristen.mp3` and `Hank.mp3`, assistant mode. */
    const val CLONED_OMNI_VOICE_HOPE = "qwen-omni-vc-hope-voice-20261123034309565-28ce"
    const val CLONED_OMNI_VOICE_HOPE_LABEL = "Hope"
    const val CLONED_OMNI_VOICE_KRISTEN = "qwen-omni-vc-kristen-voice-20261123034314048-1b84"
    const val CLONED_OMNI_VOICE_KRISTEN_LABEL = "Kristen"
    const val CLONED_OMNI_VOICE_HANK = "qwen-omni-vc-hank-voice-20261123034318121-541a"
    const val CLONED_OMNI_VOICE_HANK_LABEL = "Hank"

    /**
     * Spanish-tutor voices, cloned from `Nacho.wav` and `Alejandro.wav` for the
     * same model. They belong to tutor mode only, and Nacho is its default.
     */
    const val CLONED_TUTOR_VOICE = "qwen-omni-vc-nacho-voice-20261123033113976-f906"
    const val CLONED_TUTOR_VOICE_LABEL = "Nacho"
    const val CLONED_TUTOR_VOICE_ALT = "qwen-omni-vc-alejandro-voice-20261123033137181-2dfd"
    const val CLONED_TUTOR_VOICE_ALT_LABEL = "Alejandro"

    /** Qwen3.8-Omni-Flash-Realtime system voices (Model Studio voice list). */
    private val OMNI_VOICES = listOf(
        "Tina",
        "Cindy",
        "Liora Mira",
        "Raymond",
        "Zane",
        "Katerina",
        "Ryan",
        "Mia",
        "Cici",
        "Theo Calm",
        "Serena",
        "Maia",
        "Evan",
        "Qiao",
        "Momo",
        "Wil",
        "Angel",
        "Li Cassian",
        "Joyner",
        "Gold",
        "Jennifer",
        "Aiden",
        "Mione",
        "Sunny",
        "Dylan",
        "Eric",
        "Peter",
        "Joseph Chen",
        "Marcus",
        "Li",
        "Rocky",
        "Kiki",
        "Sohee",
        "Eliska",
        "Alek",
        "Arda",
        "Dolce",
        "Lenn",
        "Ono Anna",
        "Sonrisa",
        "Bodega",
        "Andre",
        "Radio Gol",
        "Rizky",
        "Roya",
        "Hana",
        "Jakub",
        "Griet",
        "Marina",
        "Siiri",
        "Ingrid",
        "Sigga",
        "Bea",
        "Chloe",
        "Emilien",
        "longanlingxin",
    )

    /**
     * Qwen-Audio-3.1-Realtime supports `longanqian_v3.1` plus the union of the
     * qwen-audio-3.1-tts-plus and qwen-audio-3.1-tts-flash voice lists. The 3.0
     * IDs (longanqian, longanlingxin, …) are NOT valid here.
     */
    private val AUDIO_31_VOICES = listOf(
        "longanqian_v3.1",
        "longanlingxin_v3.1",
        "xunanchuan_v3.1",
        "longanhuan_v3.1",
        "longanfengyue_v3.1",
        "yuxiaoyun_v3.1",
        "qiaoxiaojiao_v3.1",
        "xiaxiaochen_v3.1",
        "anmingyuan_v3.1",
        "wenhuaiqing_v3.1",
        "anxiaolan_v3.1",
        "xieshurou_v3.1",
        "baiqinglan_v3.1",
        "xuyuyuan_v3.1",
        "anruorou_v3.1",
        "wenhuaizhi_v3.1",
        "xiaoxingzhi_v3.1",
        "guyunshu_v3.1",
        "huozhuoshi_v3.1",
        "yeqinghe_v3.1",
        "yunhuanhuan_v3.1",
        "xuxiaoqiao_v3.1",
        "baianran_v3.1",
        "xuyanchu_v3.1",
        "yezhiqing_v3.1",
        "andi_v3.1",
        "anyuqing_v3.1",
        "linxiaoling_v3.1",
        "longfeifei_v3.1",
        "longjing_v3.1",
        "longqiang_v3.1",
        "longyan_v3.1",
        "longxiaobai_v3.1",
        "longyue_v3.1",
        "longcheng_v3.1",
        "longlaotie_v3.1",
        "longshao_v3.1",
        "longshu_v3.1",
        "longze_v3.1",
        "longhao_v3.1",
        "longjin_v3.1",
        "longnan_v3.1",
        "longshuo_v3.1",
        "longtian_v3.1",
        "longxiaocheng_v3.1",
        "longke_v3.1",
        "loongbella_v3.1",
        "Emily_v3.1",
        "Luna_v3.1",
        "Eric_v3.1",
        "Luca_v3.1",
        "Abby_v3.1",
        "Annie_v3.1",
        "Ava_v3.1",
        "Beth_v3.1",
        "Betty_v3.1",
        "Cally_v3.1",
        "Cindy_v3.1",
        "Donna_v3.1",
        "Andy_v3.1",
        "Brian_v3.1",
        "David_v3.1",
        "longanyuanfei_v3.1",
        "longjielidou_v3.1",
        "longanlingxi_v3.1",
        "longhuohuo_v3.1",
        "longyingtao_v3.1",
        "longanya_v3.1",
        "longwan_v3.1",
        "longxing_v3.1",
        "longhua_v3.1",
        "longhan_v3.1",
        "longanzhi_v3.1",
        "longzhe_v3.1",
        "longanyang_v3.1",
        "libai_v3.1",
        "longling_v3.1",
        "longniuniu_v3.1",
        "longshanshan_v3.1",
        "longpaopao_v3.1",
        "loongstella_v3.1",
        "longyuan_v3.1",
        "longmiao_v3.1",
        "longsanshu_v3.1",
        "longanli_v3.1",
        "longanwen_v3.1",
        "longanlang_v3.1",
        "longxiaoxia_v3.1",
        "longanchong_v3.1",
    )

    val models: List<ModelInfo> = listOf(
        ModelInfo(
            id = OMNI_MODEL,
            label = "Qwen3.8 Omni Flash Realtime",
            description = "Full-duplex audio + vision, tool calling, semantic turn detection.",
            voices = OMNI_VOICES,
            defaultVoice = CLONED_OMNI_VOICE,
            clonedVoices = listOf(
                ClonedVoice(CLONED_OMNI_VOICE, CLONED_OMNI_VOICE_LABEL),
                ClonedVoice(CLONED_OMNI_VOICE_ZACH, CLONED_OMNI_VOICE_ZACH_LABEL),
                ClonedVoice(CLONED_OMNI_VOICE_JANICE, CLONED_OMNI_VOICE_JANICE_LABEL),
                ClonedVoice(CLONED_OMNI_VOICE_HOPE, CLONED_OMNI_VOICE_HOPE_LABEL),
                ClonedVoice(CLONED_OMNI_VOICE_KRISTEN, CLONED_OMNI_VOICE_KRISTEN_LABEL),
                ClonedVoice(CLONED_OMNI_VOICE_HANK, CLONED_OMNI_VOICE_HANK_LABEL),
                ClonedVoice(
                    CLONED_TUTOR_VOICE,
                    CLONED_TUTOR_VOICE_LABEL,
                    ConversationMode.SPANISH_TUTOR,
                ),
                ClonedVoice(
                    CLONED_TUTOR_VOICE_ALT,
                    CLONED_TUTOR_VOICE_ALT_LABEL,
                    ConversationMode.SPANISH_TUTOR,
                ),
            ),
            turnDetectionOptions = listOf("semantic_vad", "semantic_vad_ec", "server_vad"),
            defaultTurnDetection = "semantic_vad",
            supportsTools = true,
            transcriptionModel = "qwen3-asr-flash-realtime",
            supportedTransports = TransportKind.entries.toSet(),
            supportsToolsWithSearch = false,
        ),
        ModelInfo(
            id = AUDIO_MODEL,
            label = "Qwen Audio 3.1 Realtime Flash",
            description = "Low-latency voice conversation. WebSocket only; tools + web search.",
            voices = AUDIO_31_VOICES,
            defaultVoice = "longanqian_v3.1",
            // smart_turn_v2 fuses acoustics with semantics and is much less
            // likely to mistake the assistant's own playback for a barge-in.
            turnDetectionOptions = listOf("smart_turn_v2", "smart_turn"),
            defaultTurnDetection = "smart_turn_v2",
            supportsTools = true,
            transcriptionModel = "fun-asr",
            // The AOQ gateway returns no credentials for this model and the
            // WebRTC signalling endpoint answers HTTP 500; WebSocket works.
            supportedTransports = setOf(TransportKind.WEBSOCKET),
        ),
    )

    val defaultHost: String = BuildConfig.QWEN_API_HOST.ifBlank {
        "dashscope.aliyuncs.com"
    }
    val defaultWorkspaceId: String = BuildConfig.QWEN_WORKSPACE_ID
    val defaultApiKey: String = BuildConfig.QWEN_API_KEY

    const val ASR_MODEL = "qwen3-asr-flash-realtime"

    // The default system prompt and the preset picker copy are data, not code:
    // they live in `assets/prompts.json` and are read through `PromptCatalog`.

    val TURN_DETECTION_OPTIONS = listOf("semantic_vad", "semantic_vad_ec", "server_vad")
}
