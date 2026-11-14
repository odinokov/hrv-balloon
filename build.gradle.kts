plugins {
    id("com.android.application") version "9.5.1" apply false
    // Same Kotlin version AGP 9.5.1 bundles for its built-in Kotlin, so both modules compile with one compiler.
    id("org.jetbrains.kotlin.jvm") version "2.2.10" apply false
}
