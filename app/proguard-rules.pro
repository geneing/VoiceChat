# Keep the WebRTC native bindings.
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# Room generated code.
-keep class androidx.room.** { *; }

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *; }
-keep,includedescriptorclasses class com.voicechat.assistant.**$$serializer { *; }
-keepclassmembers class com.voicechat.assistant.** {
    *** Companion;
}
-keepclasseswithmembers class com.voicechat.assistant.** {
    kotlinx.serialization.KSerializer serializer(...);
}
