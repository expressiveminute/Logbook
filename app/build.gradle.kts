import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// Keystore-Konfiguration laden (keystore.properties ist in .gitignore!)
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(keystorePropertiesFile.inputStream())
}

android {
    namespace = "com.highfly.logbook"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.highfly.logbook"
        minSdk = 29
        targetSdk = 37
        // Muss zum GitHub-Release passen. versionCode MUSS bei jedem Release
        // steigen, sonst erkennt Obtainium kein Update (oder Android blockt
        // es als Downgrade).
        versionCode = 35
        versionName = "0.0.35"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            // Release-Keystore aus keystore.properties laden
            storeFile = file(keystoreProperties["storeFile"] as String)
            storePassword = keystoreProperties["storePassword"] as String
            keyAlias = keystoreProperties["keyAlias"] as String
            keyPassword = keystoreProperties["keyPassword"] as String
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
    bundle {
        language {
            // Die App schaltet die Sprache zur Laufzeit selbst um. Ein App
            // Bundle darf die Sprachressourcen deshalb nicht nach Sprache
            // aufteilen, sonst fehlt die gerade gewählte Sprache.
            enableSplit = false
        }
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)
    implementation(libs.material)
    implementation(libs.osmdroid)
    implementation(libs.androidx.viewpager2)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}