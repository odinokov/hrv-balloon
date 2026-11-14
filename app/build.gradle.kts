plugins {
    id("com.android.application")
}

android {
    namespace = "com.hrvballoon"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.hrvballoon"
        minSdk = 31
        targetSdk = 36
        versionCode = 6
        versionName = "2.0-alpha"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
