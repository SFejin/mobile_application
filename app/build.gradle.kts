plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose) // Kotlin 2.0+에서 Compose 컴파일러 플러그인 분리 필수
}

android {
    namespace   = "com.example.safetymonitor"
    compileSdk  = 35

    defaultConfig {
        applicationId   = "com.example.safetymonitor"
        minSdk          = 26   // Android 8.0 이상 — Bluetooth LE API, SensorManager 안정
        targetSdk       = 35
        versionCode     = 1
        versionName     = "0.1.0-mvp"
        testInstrumentationRunner = "com.example.safetymonitor.BackgroundMonitoringTestRunner"
    }

    buildTypes {
        debug {
            // 팀 개발 중 빠른 빌드를 위해 최적화 생략
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false // MVP 단계에서는 난독화 없이 유지
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    // --- Compose (BOM으로 버전 일괄 관리) ---
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    // --- Activity / Lifecycle ---
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.ktx)

    // --- Coroutines ---
    // Flow 기반 비동기 감지 결과 전달에 사용
    implementation(libs.coroutines.android)
}
