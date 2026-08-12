// MoonBlogger — módulo de la aplicación Android.
//
// AGP 9.3: Kotlin "built-in" (no se aplica org.jetbrains.kotlin.android).

import java.io.FileInputStream
import java.util.Properties

// ---------------------------------------------------------------------------
// URL base de la API.
//
// - debug:  http://10.0.2.2:8000/  (emulador → host). Para un dispositivo
//           físico, añade en `android/local.properties` (NO versionado):
//              moonblogger.apiBaseUrlDebug=http://<IP-del-equipo>:8000/
// - release: placeholder https://api.moonblogger.example/. La URL real será un
//           subdominio de Koyeb que aún no existe; se pondrá en el despliegue
//           en `android/local.properties` (o `gradle.properties`) con la clave
//              moonblogger.apiBaseUrlRelease=https://<subdominio>.koyeb.app/
//
// También se puede sobreescribir desde gradle.properties con las mismas claves.
// ---------------------------------------------------------------------------
fun localProperty(key: String): String? {
    val file = rootProject.file("local.properties")
    if (!file.exists()) return null
    return Properties().apply { file.inputStream().use { load(it) } }.getProperty(key)
}

val apiBaseUrlDebug: String =
    localProperty("moonblogger.apiBaseUrlDebug")
        ?: (findProperty("moonblogger.apiBaseUrlDebug") as String?)
        ?: "http://10.0.2.2:8000/"

val apiBaseUrlRelease: String =
    localProperty("moonblogger.apiBaseUrlRelease")
        ?: (findProperty("moonblogger.apiBaseUrlRelease") as String?)
        ?: "https://api.moonblogger.example/"

// ---------------------------------------------------------------------------
// Firma del build release (ver también scripts/create-keystore.sh).
//
// Se lee `android/keystore.properties` (NO versionado, generado por
// scripts/create-keystore.sh). Contiene: storeFile, storePassword, keyAlias,
// keyPassword. storeFile es una ruta RELATIVA a android/ (p. ej.
// moonblogger-release.jks) para que funcione igual en local y en CI.
//
// Manejo de ausencia (intencionado): si keystore.properties no existe o está
// incompleto, NO se asigna signingConfig al buildType release. Así
// assembleDebug, lint y los tests siguen funcionando sin keystore; en cambio
// assembleRelease produce un APK sin firmar (app-release-unsigned.apk) con un
// warning en el log. Android rechazará instalar un APK sin firmar, que es el
// fallo esperado y claro si no se ha generado el keystore antes.
// ---------------------------------------------------------------------------
fun keystoreProperties(): Properties? {
    val file = rootProject.file("keystore.properties")
    if (!file.exists()) return null
    return Properties().apply { file.inputStream().use { load(it) } }
}

val keystoreProps = keystoreProperties()
val releaseSigningKeys = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
val hasReleaseSigning =
    keystoreProps != null && releaseSigningKeys.all { key ->
        !keystoreProps.getProperty(key).isNullOrBlank()
    }

if (keystoreProps == null) {
    logger.warn(
        "MoonBlogger: no existe android/keystore.properties; el release NO quedará " +
            "firmado. Ejecuta ./scripts/create-keystore.sh para generarlo.",
    )
} else if (!hasReleaseSigning) {
    logger.warn(
        "MoonBlogger: android/keystore.properties incompleto (faltan " +
            releaseSigningKeys.filter { key -> keystoreProps.getProperty(key).isNullOrBlank() } +
            "); el release NO quedará firmado.",
    )
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.moonblogger.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.moonblogger.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Con built-in Kotlin, jvmTarget por defecto sigue a targetCompatibility.
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    // Configuración de firma: solo se activa si keystore.properties existe y
    // está completo (ver sección superior). storeFile se interpreta relativo
    // a android/ gracias a rootProject.file().
    if (hasReleaseSigning) {
        val props = keystoreProps!!
        val keystoreFile = rootProject.file(props.getProperty("storeFile"))
        if (!keystoreFile.exists()) {
            logger.warn(
                "MoonBlogger: el keystore '${props.getProperty("storeFile")}' indicado en " +
                    "android/keystore.properties no existe; assembleRelease fallará en la firma.",
            )
        }
        signingConfigs {
            create("release") {
                storeFile = keystoreFile
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrlDebug\"")
        }
        release {
            isMinifyEnabled = false
            // Sin keystore.properties no se asigna signingConfig: assembleRelease
            // produce un APK sin firmar y no rompe assembleDebug/lint (ver arriba).
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrlRelease\"")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
}

dependencies {
    // AndroidX / Compose
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.navigation.compose)

    // Almacenamiento cifrado de tokens (EncryptedSharedPreferences, 1.1.0).
    // NOTA: la librería está deprecada upstream (ver android/README.md, D7).
    implementation(libs.androidx.security.crypto)

    // Red / serialización
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp.logging.interceptor)

    // Tests JVM (unitarios)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockwebserver3)

    // Tests instrumentados (Compose)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
