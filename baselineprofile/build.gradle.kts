plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.androidx.baselineprofile)
}

android {
    namespace = "com.phantom.scroll.baselineprofile"
    compileSdk = 35

    defaultConfig {
        minSdk = 28          // baseline-profile 生成要求 API 28+
        targetSdk = 35

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(libs.junit)
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation("androidx.test.ext:junit:1.2.1")
}
