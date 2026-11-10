# libwebrtc calls into Java via JNI by name.
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**
