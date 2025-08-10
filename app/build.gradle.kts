plugins {
    id("com.android.application") version "8.5.2"
    id("org.jetbrains.kotlin.android") version "1.9.24"
}

android {
    namespace = "com.recovereasy.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.recovereasy.app"
        minSdk = 26
        targetSdk = 34

        // ใช้เลขรอบและ SHA จาก GitHub Actions (ถ้าไม่มี ให้ fallback)
        val runNum = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        val sha = (System.getenv("GITHUB_SHA") ?: "local").take(7)

        versionCode = runNum
        versionName = "1.0.$runNum"

        // ส่งค่าเข้า resource string เพื่อให้ MainActivity เรียกผ่าน R.string.*
        resValue("string", "git_sha", "\"$sha\"")
        resValue("string", "build_run", "\"$runNum\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
