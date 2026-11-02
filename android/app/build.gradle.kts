plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
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
        versionCode = 1
        versionName = "1.0"
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
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}
