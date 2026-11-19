plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.originpods.app"
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }

    defaultConfig {
        applicationId = "com.originpods.app"
        minSdk = 33
        targetSdk = 36
        versionCode = 101
        versionName = "0.1.1"
    }

    androidResources {
        localeFilters += listOf("zh", "zh-rCN", "en")
    }

    signingConfigs {
        create("release") {
            storeFile = file("../originpods-release.jks")
            storePassword = System.getenv("ORIGINPODS_STORE_PASS") ?: "OriginPods2026!"
            keyAlias = "originpods"
            keyPassword = System.getenv("ORIGINPODS_KEY_PASS") ?: "OriginPods2026!"
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
            isMinifyEnabled = false
        }
        release {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging.resources.excludes += listOf(
        "/META-INF/{AL2.0,LGPL2.1}",
        "/META-INF/*.version",
        "/META-INF/versions/**",
        "/DebugProbesKt.bin",
        "/kotlin-tooling-metadata.json",
    )
}

dependencies {
    implementation(project(":integration"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.miuix.ui)
    implementation(libs.miuix.preference)
    implementation(libs.miuix.icons)
    implementation(libs.miuix.blur)
    implementation(libs.kotlinx.coroutines.android)
    compileOnly(libs.libxposed.api)
    implementation(libs.libxposed.service)
    testImplementation(libs.junit)
}
