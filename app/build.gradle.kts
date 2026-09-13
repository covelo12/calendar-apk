plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.covelo.calendar"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.covelo.calendar"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // Overridable at build time with -PapiBaseUrl=... ; falls back to the deployed
        // Render URL confirmed working during this handoff.
        val apiBaseUrl = (project.findProperty("apiBaseUrl") as String?) ?: "https://calendar-g2yh.onrender.com"
        val pwaUrl = (project.findProperty("pwaUrl") as String?) ?: "https://calendar-27c.pages.dev/"
        buildConfigField("String", "DEFAULT_API_BASE_URL", "\"$apiBaseUrl\"")
        buildConfigField("String", "PWA_URL", "\"$pwaUrl\"")
        buildConfigField("String", "SERVER_TIME_ZONE", "\"Europe/Lisbon\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
