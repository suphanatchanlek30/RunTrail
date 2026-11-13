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
        // Default backend: the emulator reaches the host machine at 10.0.2.2 (changeable in the app).
        buildConfigField("String", "DEFAULT_API_URL", "\"http://10.0.2.2:8080\"")
        // End-to-end tests against a real backend: ./gradlew connectedDebugAndroidTest -Pe2eApiUrl=http://10.0.2.2:8080
        testInstrumentationRunnerArguments["e2eApiUrl"] = (project.findProperty("e2eApiUrl") as String?) ?: ""
    }

    buildTypes {
        release {
            // Production server; override with -PreleaseApiUrl=https://... (users can also change it in the app).
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
    implementation(libs.okhttp)          // background sync (blocking calls)
    implementation(libs.volley)          // requests from screens
    implementation(libs.androidx.work.runtime)
    testImplementation(libs.junit)
    testImplementation(libs.org.json)          // real org.json; android.jar only has stubs
    testImplementation(libs.mockwebserver)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
}
