package com.voicechat.assistant.core

import com.voicechat.assistant.BuildConfig
import com.voicechat.assistant.data.model.TransportKind

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
    val supportsSpeechRate: Boolean = false,
    val defaultSpeechRate: Double = 0.0,
) {
    /**
     * System voices come from the vendor's per-model voice list. Cloned voices
     * are returned as `<target-model>-myvoice-…`, so allow that prefix too.
     * Anything else must not reach `session.update` or the server answers
     * "voice not supported".
     */
    fun supportsVoice(voice: String): Boolean {
        if (voice.isBlank()) return false
        return voice in voices || voice.startsWith("$id-")
    }

    fun resolveVoice(voice: String): String = if (supportsVoice(voice)) voice else defaultVoice
}

/** Static model catalogue and defaults shared by settings and the session layer. */
object RealtimeDefaults {

    const val OMNI_MODEL = "qwen3.8-omni-flash-realtime"
    const val AUDIO_MODEL = "qwen-audio-3.1-realtime-flash"

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
            defaultVoice = "Tina",
            turnDetectionOptions = listOf("semantic_vad", "semantic_vad_ec", "server_vad"),
            defaultTurnDetection = "semantic_vad",
            supportsTools = true,
            transcriptionModel = "qwen3-asr-flash-realtime",
            supportedTransports = TransportKind.entries.toSet(),
            supportsSpeechRate = true,
            defaultSpeechRate = 1.25,
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
            supportsSpeechRate = true,
            defaultSpeechRate = 1.25,
        ),
    )

    val defaultHost: String = BuildConfig.QWEN_API_HOST.ifBlank {
        "dashscope.aliyuncs.com"
    }
    val defaultWorkspaceId: String = BuildConfig.QWEN_WORKSPACE_ID
    val defaultApiKey: String = BuildConfig.QWEN_API_KEY

    const val ASR_MODEL = "qwen3-asr-flash-realtime"

    const val DEFAULT_INSTRUCTIONS = """You are VoiceChat, a concise, friendly personal assistant.
Answer in the language the user speaks. Prefer concise, spoken-friendly answers
 unless the user asks for detail. When you use a tool, say what
you are doing in one short sentence. Never invent tool results or current
information; if a tool fails, say so plainly."""
    val TURN_DETECTION_OPTIONS = listOf("semantic_vad", "semantic_vad_ec", "server_vad")
}
