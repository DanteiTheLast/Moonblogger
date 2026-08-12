// MoonBlogger — módulo raíz de Android.
//
// AGP 9 trae "built-in Kotlin": el plugin `org.jetbrains.kotlin.android` NO se
// aplica (AGP lo rechaza). Para usar una versión de KGP superior a la mínima
// que embebe AGP 9.3 (2.2.10), se declara la classpath de KGP en `buildscript`
// (mecanismo documentado en las release notes de AGP 9.0).
buildscript {
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.10")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
