plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// App version, shared with the Mac app. `make apk` passes -Ppicsync.version=$(VERSION);
// builds from Android Studio use the latest git tag (v1.2 -> 1.2), so they never get
// a lower versionCode than an installed release APK. "1.0" if there is no tag.
val appVersion: String = providers.gradleProperty("picsync.version").orNull
    ?: runCatching {
        providers.exec {
            commandLine("git", "describe", "--tags", "--abbrev=0")
            isIgnoreExitValue = true
        }.standardOutput.asText.get().trim().removePrefix("v")
    }.getOrDefault("").ifEmpty { "1.0" }

// 1.2 -> 1_002_000, 1.2.3 -> 1_002_003: every newer release installs as an update.
fun versionCodeOf(version: String): Int {
    val parts = version.split(".").map { it.toIntOrNull() ?: -1 }
    require(parts.size in 1..3 && parts.all { it in 0..999 }) {
        "version must look like 1.2 or 1.2.3 (each part 0-999), got \"$version\""
    }
    val (major, minor, patch) = parts + List(3 - parts.size) { 0 }
    return major * 1_000_000 + minor * 1_000 + patch
}

android {
    namespace = "com.inakikani.picsync"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.inakikani.picsync"
        // Android 13+: READ_MEDIA_* permissions, MediaStore.createTrashRequest and
        // MANAGE_MEDIA are all available, so there are no legacy code paths.
        minSdk = 33
        targetSdk = 37
        versionCode = versionCodeOf(appVersion)
        versionName = appVersion
    }

    buildTypes {
        // For smooth scrolling, run this one (Build Variants → release). The default
        // debug build is debuggable and unoptimised: Compose runs noticeably slower and
        // janks in ways a release build doesn't. R8 optimises and shrinks the code, and
        // the libraries' baseline profiles are installed with it.
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // Signed with the local debug key so Android Studio can install it over the
            // debug build without uninstalling (same app, data kept). Not for the Play Store.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.play.services.code.scanner)
    implementation(libs.zxing.core)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}
