# Base ProGuard rules
-keepattributes *Annotation*
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
-dontwarn org.jetbrains.annotations.**
-dontwarn kotlin.**
