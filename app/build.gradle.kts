plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.avip.solarcustomer"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.avip.solarcustomer"
        minSdk = 26
        targetSdk = 36  // Google Play: new apps and updates must target Android 16 (API 36)
        // GitHub Actions sets VERSION_CODE for every build, so each release is newer
        // than the last and the app can update itself.
        val buildNumber = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
    }

    signingConfigs {
        // Website (GitHub) build: same key on every build, so new versions install over old ones.
        create("avip") {
            storeFile = file("release.keystore")
            storePassword = "avipsc2026"
            keyAlias = "avipcustomer"
            keyPassword = "avipsc2026"
        }
        // Play Store build: the upload key. It is NOT in the repo; GitHub Actions writes it
        // from the repository secrets (UPLOAD_KEYSTORE_B64 and the passwords).
        create("upload") {
            storeFile = file(System.getenv("UPLOAD_KEYSTORE") ?: "upload.keystore")
            storePassword = System.getenv("UPLOAD_STORE_PASSWORD") ?: ""
            keyAlias = System.getenv("UPLOAD_KEY_ALIAS") ?: "upload"
            keyPassword = System.getenv("UPLOAD_KEY_PASSWORD") ?: System.getenv("UPLOAD_STORE_PASSWORD") ?: ""
        }
    }

    // direct = APK from GitHub that updates itself (current customers).
    // play   = App Bundle for Google Play; Play delivers the updates.
    flavorDimensions += "store"
    productFlavors {
        create("direct") {
            dimension = "store"
            signingConfig = signingConfigs.getByName("avip")
        }
        create("play") {
            dimension = "store"
            signingConfig = signingConfigs.getByName("upload")
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("avip")
        }
        getByName("release") {
            isMinifyEnabled = false
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    // checks for progress updates in the background and shows a notification
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    // Play build only: compulsory updates through Google Play (in-app update)
    "playImplementation"("com.google.android.play:app-update:2.1.0")
}
