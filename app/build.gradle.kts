plugins {
    id("com.android.application")
}

android {
    namespace = "com.zetteldraw.penpoc"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.zetteldraw.penpoc"
        minSdk = 26
        targetSdk = 33
        versionCode = 5
        versionName = "1.4"
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        // Committed so every machine signs debug builds identically and installs over each other.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
        }
        getByName("release") {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            pickFirsts += "lib/*/libc++_shared.so"
            excludes += "lib/*/libc++.so"
        }
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
            )
        }
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    implementation("com.onyx.android.sdk:onyxsdk-pen:1.5.5")
    implementation("com.onyx.android.sdk:onyxsdk-device:1.3.6")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
}
