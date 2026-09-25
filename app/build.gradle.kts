plugins {
    alias(libs.plugins.android.application)
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
        versionCode = 9
        versionName = "0.0.9"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
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
        viewBinding = true
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
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}