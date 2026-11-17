-keepattributes *Annotation*
-keep class com.eachother.app.core.network.** { *; }
-keepclassmembers class com.eachother.app.core.network.** { *; }

# Strip debug/verbose logs in release builds so tokens or coordinates never leak
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
