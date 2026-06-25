# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# Keep audio-related classes
-keep class android.media.** { *; }
-keep class android.media.projection.** { *; }
-keep class android.media.AudioRecord { *; }
-keep class android.media.AudioTrack { *; }
-keep class android.media.AudioPlaybackCaptureConfiguration { *; }

# Keep networking classes
-keep class java.net.** { *; }
-keep class java.io.** { *; }

# Keep WebRTC classes if using WebRTC
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# Keep coroutines
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# Keep Compose classes
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# Keep service classes
-keep class com.audioshare.app.AudioShareService { *; }
-keep class com.audioshare.app.AudioShareForegroundService { *; }
-keep class com.audioshare.app.NetworkChangeReceiver { *; }

# Keep MainActivity
-keep class com.audioshare.app.MainActivity { *; }

# Standard Android rules
-keep class * extends android.app.Activity
-keep class * extends android.app.Service
-keep class * extends android.content.BroadcastReceiver
-keep class * extends android.content.ContentProvider

# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep custom attributes
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes SourceFile,LineNumberTable

# Remove logging in release builds
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int i(...);
    public static int w(...);
    public static int d(...);
    public static int e(...);
}

# Keep serialization classes
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}