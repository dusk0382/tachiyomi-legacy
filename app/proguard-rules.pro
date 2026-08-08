# ============================================================
# Reglas R8 a medida para Tachiyomi Legacy
# ============================================================

# --- Atributos y enums (necesario para serialization, reflection y R8) ---
-keepattributes *Annotation*, InnerClasses, Signature, Exception, EnclosingMethod
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
-dontwarn org.jetbrains.annotations.**
-dontwarn kotlin.**

# --- kotlinx.serialization (NetworkModels de extensiones, Page, metadatos) ---
-keep,includedescriptorclasses class **$$serializer { *; }
-keepclassmembers class ** {
    *** Companion;
}
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class mihon.data.extension.** { *; }
-keep,includedescriptorclasses class eu.kanade.tachiyomi.source.model.Page { *; }
-dontwarn kotlinx.serialization.**

# --- ViewBinding / DataBinding ---
-keep class net.spin.tachiyomi.legacy.databinding.** { *; }

# --- Logs: eliminar v/d/i en release (el lector loguea por pagina; R8 no
#     los quita por defecto y cada llamada concatena strings + escribe logcat).
#     Se conservan w/e (errores reales). ---
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# --- coroutines ---
-dontwarn kotlinx.coroutines.**
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory { *; }
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler { *; }

# --- Material / AppCompat ---
-dontwarn com.google.android.material.**
-keep class com.google.android.material.** { *; }

# --- apksig: re-signing library (used for extension APKs) ---
-keep class com.android.apksig.** { *; }
-dontwarn com.android.apksig.**

# --- kotatsu parsers (vendored): toda la libreria debe sobrevivir a R8.
#     El MangaParserFactory instancia clases directamente, y los parsers
#     usan reflection interna; sin este keep R8 romperia todas las fuentes. ---
-keep class org.koitharu.kotatsu.parsers.** { *; }
-dontwarn org.koitharu.kotatsu.parsers.**
-keep class app.cash.quickjs.** { *; }

# --- okhttp / jsoup ---
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.jsoup.**

# --- QuickJS nativo ---
-keepclasseswithmembernames class * {
    native <methods>;
}
