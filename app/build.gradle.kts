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
        versionCode = 28
        versionName = "3.7"
        ndk {
            // -Pzetteldraw.emulator: an x86_64 build without the arm64-only Onyx libraries, for launch and data tests.
            abiFilters += if (project.hasProperty("zetteldraw.emulator")) "x86_64" else "arm64-v8a"
        }
        // Sync stays off until a server URL is set (here via -Pzetteldraw.syncUrl, or at runtime in SyncConfig).
        buildConfigField("String", "SYNC_URL", "\"${project.findProperty("zetteldraw.syncUrl") ?: ""}\"")
        buildConfigField("String", "SYNC_TOKEN", "\"${project.findProperty("zetteldraw.syncToken") ?: ""}\"")
        javaCompileOptions {
            annotationProcessorOptions {
                arguments["room.schemaLocation"] = "$projectDir/schemas"
            }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.systemProperty("zd.repoRoot", rootProject.projectDir.absolutePath)
            }
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
    implementation("androidx.room:room-runtime:2.6.1")
    annotationProcessor("androidx.room:room-compiler:2.6.1")
    implementation("androidx.work:work-runtime:2.9.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")
}
