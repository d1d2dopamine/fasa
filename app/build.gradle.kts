// Inside a Kotlin DSL build script the name `java` resolves to the Gradle Java
// extension, not to the JDK package, so java.time.* must be imported.
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// Build identity.
//
// On GitHub Actions the run number becomes the build number, so every artifact
// is distinguishable and every new build installs over the previous one. A
// local build gets zero and the sha "local".
val vespianRun: Int = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0
val vespianSha: String = (System.getenv("GITHUB_SHA") ?: "local").take(7)
val vespianBuiltAt: String = DateTimeFormatter
    .ofPattern("yyyy-MM-dd HH:mm 'UTC'")
    .withZone(ZoneOffset.UTC)
    .format(Instant.now())

android {
    namespace = "dev.vespian"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.vespian"
        minSdk = 28
        targetSdk = 34
        versionCode = 2 + vespianRun
        versionName = "0.2." + vespianRun
        resourceConfigurations += listOf("en", "ru")

        buildConfigField("String", "GIT_SHA", "\"" + vespianSha + "\"")
        buildConfigField("String", "BUILD_AT", "\"" + vespianBuiltAt + "\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
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
            storeFile = file("vespian-debug.jks")
            storePassword = "vespianpass"
            keyAlias = "vespian"
            keyPassword = "vespianpass"
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
