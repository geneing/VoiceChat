Goal: 

1. Android AI assistant app with both text and voice interface similar to Gemini Live. 
2. Voice assistant shall support conversation mode, barge-in, interruption, natural real-time seamless voice conversation mode, etc. It should have the same features as Gemini Live or ChatGPT Live.
3. Shall support seamless switching between voice input and text input. If user uses voice, output should be audio. If user entered text, output should be text. Context of both modes shall be used as long as it's the same conversation. 
4. App shall use third-party API access. Initial implementation will use Qwen APIs: qwen3.8-omni-flash-realtime, qwen-audio-3.1-realtime-flash. API key is provided in secrets/qwen-apiKey-1341022.csv
5. This app will be kept private, so it's Ok to build in the key. Don't upload the key to git. Don't release the apk. 
6. The models have tool calling capability. Provide tools that enable functionality like Gemini Live (e.g. web_search, location, date). 
7. The app shall enable WebRTC and AOQ connection for API. Configurable in the settings. See docs below.
8. The app shall have interface similar to Gemini and ChatGPT. Side burger menu for older chats. Gear for settings. Long press on older chat for a local menu, including deletion. Main area with edit box on the bottom for text entry, "sound wave" button for live chat. Show input text and output in the main window. Show voice input / output in the main window too. "+" button in the text entry box for adding images, camera shots, documents, etc. 
9. Additional information: connection latentcy, TTFT, number of tokens used (per the conversation), total cost for Qwen API calls since the beginning of the billing (if available).
10. Provide extensive logging in debug mode (modes configurable) to enable easy debugging and tracing.  
11. Provide configuration for propmpts for LLM. 
12. Tech stack: Kotlin, Jetpack Compose, Alibaba SDK (see below). 


Documentation: 
https://docs.modelstudio.console.alibabacloud.com/en/model-studio/qwen3-8-omni-flash-realtime
https://docs.modelstudio.console.alibabacloud.com/en/model-studio/qwen-audio-3-1-realtime-flash
https://docs.modelstudio.console.alibabacloud.com/en/model-studio/qwen-audio-realtime-model-access
https://docs.modelstudio.console.alibabacloud.com/en/model-studio/android-sdk-for-qwen-audio-realtime-service