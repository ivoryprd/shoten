import java.util.Properties

plugins {
    id("com.android.application")
}

// Optional release signing: create keystore.properties (see README) to sign release builds with
// your own key. Without it, release builds are signed with the debug key so they stay installable.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "io.github.ivoryprd.shoten"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.ivoryprd.shoten"
        minSdk = 26
        targetSdk = 36
        versionCode = 12
        versionName = "0.1.11"

        // Optional: build for specific ABIs only, e.g. -Pshoten.abiFilters=arm64-v8a. This mostly
        // matters for the offline flavor, whose bundled OCR library is ~11 MB per ABI.
        providers.gradleProperty("shoten.abiFilters").orNull?.let { filters ->
            ndk { abiFilters += filters.split(',').map { it.trim() }.filter { it.isNotEmpty() } }
        }
    }

    signingConfigs {
        if (keystoreProperties.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (keystoreProperties.isNotEmpty()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    // How the ML Kit OCR models are delivered.
    //  standard: models are provided by Google Play services (small APK, needs Play services).
    //  offline:  models are bundled in the APK (works without Play services, larger APK).
    flavorDimensions += "ocr"
    productFlavors {
        create("standard") {
            dimension = "ocr"
            isDefault = true
        }
        create("offline") {
            dimension = "ocr"
            versionNameSuffix = "-offline"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { test ->
            test.maxHeapSize = "4g"
            // Robolectric's Android 16 runtime needs access to JDK internals.
            test.jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED", "--add-opens=java.base/java.io=ALL-UNNAMED")
        }
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/*.version",
            "META-INF/*.kotlin_module",
            "kotlin/**",
            "DebugProbesKt.bin",
        )
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        // The app deliberately doesn't use AndroidX to stay small, so AndroidX suggestions don't apply.
        disable += setOf("UseKtx", "UseSwitchCompatOrMaterialCode", "UseRequiresApi", "AppCompatCustomView")
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    "standardImplementation"("com.google.android.gms:play-services-mlkit-text-recognition-japanese:16.0.1")
    "standardImplementation"("com.google.android.gms:play-services-mlkit-text-recognition-chinese:16.0.1")
    "standardImplementation"("com.google.android.gms:play-services-base:18.11.1")

    "offlineImplementation"("com.google.mlkit:text-recognition-japanese:16.0.1")
    "offlineImplementation"("com.google.mlkit:text-recognition-chinese:16.0.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
}
