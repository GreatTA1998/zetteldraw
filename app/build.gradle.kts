import java.util.Properties

plugins {
    id("com.android.application")
}

// Upload key for Play. Never commit the keystore or these secrets.
// Resolution order for each value: -P property, repo-root keystore.properties, environment variable.
// Paths are relative to the repo root unless absolute. Leave all four unset to build an unsigned AAB.
val keystoreProperties = Properties()
val keystorePropertiesFile = rootProject.file("keystore.properties")
if (keystorePropertiesFile.isFile) {
    keystorePropertiesFile.inputStream().use { keystoreProperties.load(it) }
}

fun signingValue(propertyName: String, envName: String): String? {
    val fromGradle = project.findProperty(propertyName) as String?
    val fromFile = keystoreProperties.getProperty(propertyName)
    val fromEnv = System.getenv(envName)
    return listOf(fromGradle, fromFile, fromEnv)
        .firstNotNullOfOrNull { raw -> raw?.trim()?.takeIf { it.isNotEmpty() } }
}

val uploadStoreFile = signingValue("zetteldraw.storeFile", "ZETTELDRAW_STORE_FILE")
val uploadStorePassword = signingValue("zetteldraw.storePassword", "ZETTELDRAW_STORE_PASSWORD")
val uploadKeyAlias = signingValue("zetteldraw.keyAlias", "ZETTELDRAW_KEY_ALIAS")
val uploadKeyPassword = signingValue("zetteldraw.keyPassword", "ZETTELDRAW_KEY_PASSWORD")
val uploadSigningValues = listOf(uploadStoreFile, uploadStorePassword, uploadKeyAlias, uploadKeyPassword)
val uploadSigningReady = uploadSigningValues.all { it != null }
if (uploadSigningValues.count { it != null } in 1..3) {
    throw GradleException(
        "Release signing is incomplete. Set all of zetteldraw.storeFile, zetteldraw.storePassword, " +
            "zetteldraw.keyAlias, and zetteldraw.keyPassword (keystore.properties, -P, or " +
            "ZETTELDRAW_STORE_FILE / ZETTELDRAW_STORE_PASSWORD / ZETTELDRAW_KEY_ALIAS / " +
            "ZETTELDRAW_KEY_PASSWORD), or set none of them to produce an unsigned release bundle.",
    )
}

android {
    namespace = "com.zetteldraw.penpoc"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.zetteldraw.penpoc"
        minSdk = 26
        targetSdk = 36
        // Tip v120: Ready-only pages() — delete BoardEntity fallthrough + warmCache.
        versionCode = 121
        versionName = "4.00"
        ndk {
            // -Pzetteldraw.emulator: an x86_64 build without the arm64-only Onyx libraries, for launch and data tests.
            // Play bundles must stay arm64-v8a. Do not pass zetteldraw.emulator for a store build.
            abiFilters += if (project.hasProperty("zetteldraw.emulator")) "x86_64" else "arm64-v8a"
        }
        // Sync URL is baked for production builds; Google Sign-In supplies the bearer at runtime.
        // Do not bake SYNC_TOKEN into Play/sideload releases once Google auth is live.
        // The release build type overrides SYNC_TOKEN to empty even if -Pzetteldraw.syncToken is set.
        buildConfigField("String", "SYNC_URL", "\"${project.findProperty("zetteldraw.syncUrl") ?: ""}\"")
        buildConfigField("String", "SYNC_TOKEN", "\"${project.findProperty("zetteldraw.syncToken") ?: ""}\"")
        // Web OAuth client ID (serverClientId) for Google ID tokens. The existing Android
        // OAuth client for com.zetteldraw.penpoc needs the upload-key SHA-1 and the Play
        // App Signing SHA-1. The ID token audience is this web client.
        buildConfigField(
            "String",
            "GOOGLE_WEB_CLIENT_ID",
            "\"${project.findProperty("zetteldraw.googleWebClientId") ?: ""}\"",
        )
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
        if (uploadSigningReady) {
            create("release") {
                storeFile = rootProject.file(uploadStoreFile!!)
                storePassword = uploadStorePassword
                keyAlias = uploadKeyAlias
                keyPassword = uploadKeyPassword
            }
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
        }
        getByName("release") {
            if (uploadSigningReady) {
                signingConfig = signingConfigs.getByName("release")
            }
            // Play and sideload releases authenticate with Google, not a baked device token.
            buildConfigField("String", "SYNC_TOKEN", "\"\"")
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

if (uploadSigningReady && !rootProject.file(uploadStoreFile!!).isFile) {
    throw GradleException(
        "Upload keystore not found: ${rootProject.file(uploadStoreFile).absolutePath}. " +
            "zetteldraw.storeFile is relative to the repo root unless it is absolute.",
    )
}

gradle.taskGraph.whenReady {
    val buildingRelease = allTasks.any { it.project == project && it.name.endsWith("Release") }
    val token = (project.findProperty("zetteldraw.syncToken") as String?)?.trim().orEmpty()
    if (buildingRelease && token.isNotEmpty()) {
        logger.warn(
            "Release builds ignore -Pzetteldraw.syncToken. SYNC_TOKEN is empty in the release BuildConfig.",
        )
    }
}

dependencies {
    implementation("com.onyx.android.sdk:onyxsdk-pen:1.5.5")
    implementation("com.onyx.android.sdk:onyxsdk-device:1.3.6")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")
    implementation("androidx.room:room-runtime:2.6.1")
    annotationProcessor("androidx.room:room-compiler:2.6.1")
    implementation("androidx.work:work-runtime:2.9.1")
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")
}
