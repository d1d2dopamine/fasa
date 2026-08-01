plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "dev.fasa"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.fasa"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
        resourceConfigurations += listOf("en", "ru")
    }

    buildFeatures {
        compose = true
    }

    signingConfigs {
        // A fixed key, committed on purpose.
        //
        // Without it every CI run generates a fresh random debug key, Android
        // treats each build as a different app, and the only way to install a
        // new version is to uninstall the old one. That wipes the database,
        // the trained model and every battery exemption granted by hand.
        //
        // The key has no security value: this app is sideloaded, never
        // published, and holds no secrets of its own.
        getByName("debug") {
            storeFile = file("fasa-debug.jks")
            storePassword = "fasapass"
            keyAlias = "fasa"
            keyPassword = "fasapass"
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    // Compose. The BOM pins every compose artifact to one compatible set,
    // so the individual artifacts below carry no version on purpose.
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    implementation("androidx.health.connect:connect-client:1.1.0-rc02")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Encrypted storage for the Telegram token. Alpha is the only channel this
    // library has shipped in for years; the API used here is stable.
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
