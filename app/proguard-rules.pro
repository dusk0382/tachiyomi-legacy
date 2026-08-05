# Base ProGuard rules
-keepattributes *Annotation*
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
-dontwarn org.jetbrains.annotations.**
-dontwarn kotlin.**

# apksig: re-signing library (used for extension APKs)
-keep class com.android.apksig.** { *; }
-dontwarn com.android.apksig.**
