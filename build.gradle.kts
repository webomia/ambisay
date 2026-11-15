plugins {
    id("com.android.application") version "8.13.0" apply false
    // El SDK de Meta (mwdat 1.0.x) está compilado con Kotlin 2.2, por eso usamos 2.2.21 (no 2.0.21).
    id("org.jetbrains.kotlin.android") version "2.2.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21" apply false
}
