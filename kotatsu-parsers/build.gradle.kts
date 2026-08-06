plugins {
    alias(mihonx.plugins.android.library)
}

android {
    namespace = "org.koitharu.kotatsu.parsers"
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll(
            listOf(
                "-opt-in=kotlin.RequiresOptIn",
                "-opt-in=kotlin.contracts.ExperimentalContracts",
                "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
                "-opt-in=org.koitharu.kotatsu.parsers.InternalParsersApi",
            ),
        )
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.okhttp.core)
    implementation(libs.okio)
    implementation(libs.androidx.collection)
    implementation(libs.jspecify)
    compileOnly(libs.androidx.annotation)
    api(libs.jsoup)
}
