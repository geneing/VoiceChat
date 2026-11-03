package com.voicechat.assistant

import android.content.Context
import com.voicechat.assistant.data.attachments.AttachmentManager
import com.voicechat.assistant.data.db.AppDatabase
import com.voicechat.assistant.data.prefs.SettingsRepository
import com.voicechat.assistant.data.repo.ChatRepository
import com.voicechat.assistant.realtime.RealtimeTransport
import com.voicechat.assistant.realtime.aoq.AoqTransport
import com.voicechat.assistant.realtime.tools.ToolRegistry
import com.voicechat.assistant.realtime.webrtc.WebRtcTransport
import com.voicechat.assistant.realtime.ws.WebSocketTransport
import com.voicechat.assistant.data.model.TransportKind
import kotlinx.coroutines.CoroutineScope
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Manual dependency container. One instance per process. */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val database: AppDatabase by lazy { AppDatabase.build(appContext) }

    val chats: ChatRepository by lazy { ChatRepository(database) }

    val settings: SettingsRepository by lazy { SettingsRepository(appContext) }

    val attachments: AttachmentManager by lazy { AttachmentManager(appContext) }

    private val apiClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(40, TimeUnit.SECONDS)
            .writeTimeout(40, TimeUnit.SECONDS)
            .build()
    }

    private val realtimeClient: OkHttpClient by lazy {
        apiClient.newBuilder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    val tools: ToolRegistry by lazy { ToolRegistry(appContext, apiClient) }

    fun createTransport(kind: TransportKind, scope: CoroutineScope): RealtimeTransport = when (kind) {
        TransportKind.WEBSOCKET -> WebSocketTransport(realtimeClient, appContext)
        TransportKind.WEBRTC -> WebRtcTransport(appContext, apiClient)
        TransportKind.AOQ -> AoqTransport(appContext, apiClient)
    }
}
