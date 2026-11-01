plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.user.aplaunch"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.user.aplaunch"
        minSdk = 21
        targetSdk = 34
        versionCode = 5
        versionName = "5.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}
