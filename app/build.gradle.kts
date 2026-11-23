plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.justaskaspire.bandwatch"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.justaskaspire.bandwatch"
        minSdk = 26
        targetSdk = 35
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionName = "0.1.${System.getenv("GITHUB_RUN_NUMBER") ?: "0"}"
    }

    signingConfigs {
        create("bandwatchRelease") {
            storeFile = file("$rootDir/bandwatch-release.jks")
            storePassword = System.getenv("BANDWATCH_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("BANDWATCH_KEY_ALIAS")
            keyPassword = System.getenv("BANDWATCH_KEY_PASSWORD")
        }
    }

    buildTypes {
        getByName("debug") {
            // Sign every CI APK with one permanent key so updates install over prior builds.
            signingConfig = signingConfigs.getByName("bandwatchRelease")
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
    testImplementation("junit:junit:4.13.2")
}
