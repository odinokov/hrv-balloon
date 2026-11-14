import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The signal path, search and session logic: plain Kotlin on the JVM, so it cannot reach Android APIs and its tests
// run without an emulator or android.jar.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
