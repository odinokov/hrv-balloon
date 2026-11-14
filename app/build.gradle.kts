import java.util.Properties

plugins {
    id("com.android.application")
}

// Upload-key settings for Play builds, kept out of git: storeFile (relative to app/), storePassword, keyAlias, keyPassword.
val keystoreProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "com.hrvballoon"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.hrvballoon"
        minSdk = 31
        targetSdk = 36
        versionCode = 7
        versionName = "2.0"
    }

    signingConfigs {
        if (!keystoreProps.isEmpty) create("upload") {
            storeFile = file(keystoreProps.getProperty("storeFile"))
            storePassword = keystoreProps.getProperty("storePassword")
            keyAlias = keystoreProps.getProperty("keyAlias")
            keyPassword = keystoreProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        // Play bundle: signed with the upload key when keystore.properties exists, unsigned otherwise. No R8: no libraries to shrink.
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("upload")
        }
        // GitHub APK: the release build signed with the debug key every earlier GitHub APK used, so installs update in place.
        create("sideload") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core"))
    testImplementation("junit:junit:4.13.2")
}
