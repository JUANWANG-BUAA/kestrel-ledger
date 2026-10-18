plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Version and signing come from the environment so CI can publish signed builds; local builds keep
// working with the debug key when no keystore is configured.
val releaseKeystore = System.getenv("MARKET_KEYSTORE")?.takeIf { it.isNotBlank() }

android {
    namespace = "cn.market.assistant"
    compileSdk = 35
    defaultConfig {
        applicationId = "cn.market.assistant"
        minSdk = 26
        targetSdk = 35
        versionCode = (System.getenv("VERSION_CODE") ?: "204").toInt()
        versionName = System.getenv("VERSION_NAME") ?: "0.2.4"
    }
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("MARKET_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("MARKET_KEY_ALIAS")
                keyPassword = System.getenv("MARKET_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            // Never publish with a throwaway key: self-update compares signatures and will refuse a
            // different certificate. CI supplies the long-lived keystore through secrets.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":data"))
    implementation(platform("androidx.compose:compose-bom:2025.03.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.work:work-runtime-ktx:2.11.2")
    testImplementation("junit:junit:4.13.2")
}
