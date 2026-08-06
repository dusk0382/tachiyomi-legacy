plugins {
    alias(mihonx.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "net.spin.tachiyomi.legacy"

    defaultConfig {
        applicationId = "net.spin.tachiyomi.legacy"
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "UPSTREAM_VERSION", """"0.20.1"""")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            setProguardFiles(
                listOf(
                    getDefaultProguardFile("proguard-android-optimize.txt"),
                    "proguard-rules.pro",
                ),
            )
        }
    }

    splits {
        abi {
            isEnable = true
            isUniversalApk = false
            reset()
            // Snapdragon 210 (MSM8909) es un SoC ARMv7 de 32 bits
            include("armeabi-v7a")
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
    }

    packaging {
        resources {
            excludes += setOf(
                "kotlin-tooling-metadata.json",
                "LICENSE.txt",
                "META-INF/**/*.properties",
                "META-INF/**/LICENSE.txt",
                "META-INF/*.properties",
                "META-INF/*.version",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/NOTICE",
                "META-INF/README.md",
            )
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    implementation(projects.sourceApi)
    implementation(projects.core.common)
    implementation(projects.kotatsuParsers)

    implementation(libs.androidx.activity)
    implementation(libs.androidx.appCompat)
    implementation(libs.androidx.constraintLayout)
    implementation(libs.androidx.core)
    implementation(libs.androidx.documentFile)
    implementation(libs.androidx.lifecycle.livedata)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.recyclerView)
    implementation(libs.androidx.viewPager2)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.material)
    implementation(libs.quickJs)

    coreLibraryDesugaring(libs.android.desugar)
}
