import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// CI writes keystore.properties from repo secrets; without it the release APK is debug-signed.
val keyProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val runNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
// gradle.properties: soqzilla.serverSetting=false for the launch build (missing also means false)
val serverSetting = (findProperty("soqzilla.serverSetting") as String?)?.toBoolean() ?: false

android {
    namespace = "com.soqzilla.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.soqzilla.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1000 + runNumber   // above every 1.0/1.1 build, so updates install over them
        versionName = "1.2.$runNumber"
        // soqzilla.com goes through Cloudflare (post-quantum TLS); the Railway address is the fallback
        buildConfigField("String", "API_URL", "\"https://soqzilla.com\"")
        buildConfigField("String", "API_FALLBACK_URL", "\"https://soqzilla.up.railway.app\"")
        buildConfigField("boolean", "SERVER_SETTING", "$serverSetting")
    }

    signingConfigs {
        create("release") {
            if (keyProps.isNotEmpty()) {
                storeFile = rootProject.file(keyProps.getProperty("storeFile"))
                storePassword = keyProps.getProperty("storePassword")
                keyAlias = keyProps.getProperty("keyAlias")
                keyPassword = keyProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (keyProps.isNotEmpty()) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    packaging {
        resources {
            excludes += setOf("META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // fingerprint unlock (BiometricPrompt needs a FragmentActivity)
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    // ML-DSA-44 signatures and Argon2id, checked byte for byte against the Node reference in the unit tests
    implementation("org.bouncycastle:bcprov-jdk18on:1.81")

    testImplementation("junit:junit:4.13.2")
}
