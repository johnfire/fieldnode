plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "de.christopherrehm.fieldnode"
    compileSdk = 35

    defaultConfig {
        applicationId = "de.christopherrehm.fieldnode"
        minSdk = 30        // Android 11 — MANAGE_EXTERNAL_STORAGE APIs are API 30+ (the device's level)
        targetSdk = 30     // Android 11 — DELIBERATE (2026-07-20): stays at 30 because the sole
                            // target device (Redmi Note 8 Pro) runs API 30 and the
                            // MANAGE_EXTERNAL_STORAGE grant flow is simplest here. Raising
                            // targetSdk would add scoped-storage complexity with zero benefit.
        versionCode = 1
        versionName = "0.0.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // DELIBERATE (2026-07-20): minification is disabled because this is a
            // sideloaded personal app on a single device. R8 would shrink the APK but
            // adds keep-rule maintenance and makes stack traces unreadable — costs
            // that don't justify the benefit for a non-distributed app.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    lint {
        // This app is sideloaded on purpose and never ships on Google Play, and targetSdk 30 is the
        // deliberate floor that keeps the MANAGE_EXTERNAL_STORAGE grant flow simple. The Play-store
        // "target a recent API level" gate therefore doesn't apply — every other lint check stays on.
        disable += "ExpiredTargetSdkVersion"
        abortOnError = true
        warningsAsErrors = false
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
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.drawerlayout:drawerlayout:1.2.0")   // session drawer in AgentActivity
    implementation("androidx.recyclerview:recyclerview:1.3.2")   // session list
    implementation("org.osmdroid:osmdroid-android:6.1.20")       // interactive leads map (OSM, no key)

    testImplementation("junit:junit:4.13.2")
    // Real org.json on the JVM test classpath: SessionStore stores the agent's protocol JSONArray, and
    // the android.jar org.json is stubbed (throws "not mocked") under unit tests. Test-scoped only —
    // the app gets org.json from Android at runtime, so this adds nothing to the shipped APK.
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.ext:junit-ktx:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.test.espresso:espresso-contrib:3.6.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
