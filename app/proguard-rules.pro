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

# kotatsu parsers (vendored): toda la libreria debe sobrevivir a R8
-keep class org.koitharu.kotatsu.parsers.** { *; }
-dontwarn org.koitharu.kotatsu.parsers.**
-keep class app.cash.quickjs.** { *; }
