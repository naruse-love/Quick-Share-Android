# QuickShare Data Models
-keep class com.quickshare.android.model.** { *; }
-keepclassmembers class com.quickshare.android.model.** { *; }

# Protocol constants and streams
-keep class com.quickshare.android.protocol.** { *; }
-keepclassmembers class com.quickshare.android.protocol.** { *; }

# Kotlinx Serialization & Gson
-keepattributes Signature
-keepattributes *Annotation*
-keepclassmembers enum * { *; }
