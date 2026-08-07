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

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    // Gradle 9 exige el launcher de JUnit Platform en el runtime de tests.
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")
    // Los parsers usan org.json; en tests JVM no existe el de android.jar.
    testImplementation("org.json:json:20240303")
}

android {
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

tasks.withType<Test>().configureEach {
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
    // Secuencial: somos amables con los sitios.
    maxParallelForks = 1
    // Reenviar las propiedades -Dhealth.* al JVM del test (SourceHealthCheck).
    systemProperty("health.nsfw", System.getProperty("health.nsfw") ?: "false")
    System.getProperty("health.locale")?.let { systemProperty("health.locale", it) }
    System.getProperty("health.timeout")?.let { systemProperty("health.timeout", it) }
    System.getProperty("health.sources")?.let { systemProperty("health.sources", it) }
}
