package com.voicechat.assistant

import android.content.Context
import com.voicechat.assistant.core.IssueReportStore
import com.voicechat.assistant.data.attachments.AttachmentManager
import com.voicechat.assistant.data.db.AppDatabase
import com.voicechat.assistant.data.prefs.SettingsRepository
import com.voicechat.assistant.data.repo.ChatRepository
import com.voicechat.assistant.memory.MemoryStore
import com.voicechat.assistant.tutor.TutorStore
import com.voicechat.assistant.realtime.RealtimeTransport
import com.voicechat.assistant.realtime.aoq.AoqTransport
import com.voicechat.assistant.realtime.handoff.ReasoningClient
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

    internal val issueReports: IssueReportStore by lazy { IssueReportStore(appContext) }

    /** Facts the assistant remembers between chats, stored in the same database. */
    val memory: MemoryStore by lazy { MemoryStore(database.memoryDao()) }

    // Separate database instances also isolate every DAO query, deletion and memory tool.
    val tutorDatabase: AppDatabase by lazy { AppDatabase.build(appContext, "spanish-tutor.db") }
    val tutorProgress: TutorStore by lazy { TutorStore(appContext) }
    val tutorMemory: MemoryStore by lazy { MemoryStore(tutorDatabase.memoryDao()) }
    val tutorChats: ChatRepository by lazy {
        ChatRepository(tutorDatabase) { message ->
            try {
                tutorProgress.record(message)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                com.voicechat.assistant.core.AppLog.warn(
                    com.voicechat.assistant.core.LogCategory.MEMORY, "Could not retain tutor dialog", error.message,
                )
            }
        }
    }

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
            // Keep-alive heartbeat: a WebSocket ping every 10 s stops NAT/proxy
            // idle reaping and makes OkHttp fail a dead link within ~20 s, which
            // is what triggers RealtimeSession's automatic recovery.
            .pingInterval(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    val tools: ToolRegistry by lazy {
        ToolRegistry(appContext, apiClient, memory = memory, tutorMemory = tutorMemory, tutorProgress = tutorProgress)
    }

    /** Higher-reasoning text model consulted for hard turns. */
    val reasoner: ReasoningClient by lazy { ReasoningClient(apiClient) }

    val tutorLearning by lazy { com.voicechat.assistant.tutor.TutorLearning(reasoner, tutorProgress) }

    fun createTransport(kind: TransportKind, scope: CoroutineScope): RealtimeTransport = when (kind) {
        TransportKind.WEBSOCKET -> WebSocketTransport(realtimeClient, appContext)
        TransportKind.WEBRTC -> WebRtcTransport(appContext, apiClient)
        TransportKind.AOQ -> AoqTransport(appContext, apiClient)
    }
}
