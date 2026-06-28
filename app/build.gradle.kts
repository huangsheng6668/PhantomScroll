import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.androidx.baselineprofile)
}

android {
    namespace = "com.phantom.scroll"
    compileSdk = 35

    // Signing credentials are read ONLY from a local (git-ignored) keystore.properties.
    // Never hardcode secrets here — they would ship in version control. When the file
    // is absent (e.g. CI, fresh clone) the release build is left unsigned and must be
    // signed by an external step; debug falls back to AGP's default debug signing.
    val keystorePropertiesFile = rootProject.file("keystore.properties")
    val keystoreProperties = Properties()
    if (keystorePropertiesFile.exists()) {
        keystoreProperties.load(FileInputStream(keystorePropertiesFile))
    }
    val hasKeystore = keystorePropertiesFile.exists() &&
        keystoreProperties.getProperty("key.store.password") != null

    signingConfigs {
        create("release") {
            if (hasKeystore) {
                storeFile = file(keystoreProperties.getProperty("key.store.file"))
                storePassword = keystoreProperties.getProperty("key.store.password")
                keyAlias = keystoreProperties.getProperty("key.alias")
                keyPassword = keystoreProperties.getProperty("key.password")
            }
        }
    }

    defaultConfig {
        applicationId = "com.phantom.scroll"
        minSdk = 26
        targetSdk = 35
        versionCode = 8
        versionName = "1.7"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Release is signed only when a local keystore.properties is present.
            if (hasKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        // debug uses AGP's default debug signing — no release secrets in debug builds.
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
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    // Material Components (native overlay Slider)
    implementation(libs.google.material)
    implementation(libs.androidx.savedstate)
    // Link to the baselineprofile generator module (Phase 4)
    baselineProfile(project(":baselineprofile"))
    // Runtime distribution of Baseline Profiles (Phase 4)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.kotlinx.coroutines.android)
    // DataStore (async persistence, replaces SharedPreferences)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.core)

    // Compose BOM
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.bundles.compose)

    debugImplementation(libs.androidx.compose.ui.tooling)

    // Local JVM Unit Tests
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.kotlin)
}

composeCompiler {
    // Phase 4: emit Compose stability reports to build/reports to find unstable params.
    // Reports are build artifacts (not committed); inspect to guide MainScreen refactors.
    reportsDestination.set(layout.buildDirectory.dir("compose_compiler/reports"))
    // (Optional) stability config file can force-mark packages stable; not needed here.
}

