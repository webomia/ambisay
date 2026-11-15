import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
// Los valores vacíos (como en local.properties.example) cuentan como no definidos.
fun prop(name: String, default: String = "") =
    localProps.getProperty(name)?.trim()?.takeIf { it.isNotEmpty() }
        ?: System.getenv(name)?.trim()?.takeIf { it.isNotEmpty() }
        ?: default

android {
    namespace = "com.javier.raybangrok"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.javier.raybangrok"
        // El Wearables Device Access Toolkit requiere Android 10+ (API 29).
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        // Clave de xAI leída de local.properties o variable de entorno XAI_API_KEY. Nunca hardcodeada.
        buildConfigField("String", "XAI_API_KEY", "\"${prop("XAI_API_KEY")}\"")
        buildConfigField("String", "XAI_MODEL", "\"${prop("XAI_MODEL", "grok-4.7")}\"")

        // Credenciales del Wearables Developer Center. "0" sirve en Developer Mode.
        manifestPlaceholders["mwdat_application_id"] = prop("mwdat_application_id", "0")
        manifestPlaceholders["mwdat_client_token"] = prop("mwdat_client_token", "0")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }

dependencies {
    val bom = platform("androidx.compose:compose-bom:2025.10.01")
    implementation(bom)
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-service:2.9.4")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Meta Wearables Device Access Toolkit (developer preview) – Maven Central
    val mwdat = "1.0.1"
    implementation("com.meta.wearable:mwdat-core:$mwdat")
    implementation("com.meta.wearable:mwdat-camera:$mwdat")
    implementation("com.meta.wearable:mwdat-display:$mwdat")   // solo útil en Meta Ray-Ban Display
    debugImplementation("com.meta.wearable:mwdat-mockdevice:$mwdat") // pruebas sin gafas (MockDeviceKit)

    // Cámara del móvil como fuente de prueba
    val camerax = "1.5.1"
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")

    // HTTP para la API de xAI
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    testImplementation("junit:junit:4.13.2")
}
