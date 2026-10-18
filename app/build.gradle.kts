import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.roborazzi)
}

// The release key lives outside the repo, in ~/.config/foyer/keystore.properties. Without it,
// release builds are signed with the debug key so anyone can build and install Foyer.
val keystoreProps = Properties().apply {
    val f = File(System.getProperty("user.home"), ".config/foyer/keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "dev.rohan.foyer"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.rohan.foyer"
        minSdk = 30 // Fire OS 8 = Android 11
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        ndk { abiFilters += "armeabi-v7a" } // the TV is 32-bit only
        buildConfigField("boolean", "IS_DEV", "false")
        resValue("string", "app_name", "Foyer")
        manifestPlaceholders["banner"] = "@drawable/banner"
    }

    signingConfigs {
        if (keystoreProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        // JVM tests and Roborazzi only. Never installed on a TV.
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
        // What goes on the living-room TV, and what every performance number comes from.
        create("dev") {
            initWith(getByName("release"))
            applicationIdSuffix = ".dev"
            isDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
            buildConfigField("boolean", "IS_DEV", "true")
            resValue("string", "app_name", "Foyer dev")
            manifestPlaceholders["banner"] = "@drawable/banner_dev"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true
    }
    testOptions { unitTests { isIncludeAndroidResources = true } }
    // Optional brand art for well-known apps, kept out of git (app/local/ is gitignored).
    sourceSets.getByName("main").res.srcDir("local/res")
    packaging {
        resources.excludes += setOf("META-INF/*.version", "META-INF/**/LICENSE*", "kotlin/**", "DebugProbesKt.bin")
    }
}

kotlin { jvmToolchain(21) }

dependencies {
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.profileinstaller)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(composeBom)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.test.ext.junit)
}
