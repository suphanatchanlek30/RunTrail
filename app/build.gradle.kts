plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.runtrail"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.runtrail"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // ที่อยู่ backend เริ่มต้น: emulator มองเห็นเครื่อง Mac เป็น 10.0.2.2 (เปลี่ยนได้ในหน้าตั้งค่า)
        buildConfigField("String", "DEFAULT_API_URL", "\"http://10.0.2.2:8080\"")
        // ใช้กับ instrumented test ที่ยิงไป backend จริง: ./gradlew connectedDebugAndroidTest -Pe2eApiUrl=http://10.0.2.2:8080
        testInstrumentationRunnerArguments["e2eApiUrl"] = (project.findProperty("e2eApiUrl") as String?) ?: ""
    }

    buildTypes {
        release {
            // ที่อยู่เซิร์ฟเวอร์จริง (HTTPS) — เปลี่ยนได้ตอน build: ./gradlew assembleRelease -PreleaseApiUrl=https://...
            // ผู้ใช้ยังเปลี่ยนเองได้ในหน้าเข้าสู่ระบบ → "เซิร์ฟเวอร์" (ดู docs/DEPLOY_RENDER.md)
            val releaseApiUrl = (project.findProperty("releaseApiUrl") as String?) ?: "https://runtrail-api.onrender.com"
            buildConfigField("String", "DEFAULT_API_URL", "\"$releaseApiUrl\"")
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.exifinterface)
    implementation(libs.zxing.core)
    implementation(libs.osmdroid)
    implementation(libs.material)
    implementation(libs.okhttp)          // ซิงก์เบื้องหลัง (WorkManager ต้องเรียกแบบรอผล)
    implementation(libs.volley)          // MA11: เรียก API จากหน้าจอ (JsonObjectRequest + RequestQueue)
    implementation(libs.androidx.work.runtime)
    testImplementation(libs.junit)
    testImplementation(libs.org.json)          // org.json จริง (ใน android.jar เป็นแค่ stub)
    testImplementation(libs.mockwebserver)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
}
