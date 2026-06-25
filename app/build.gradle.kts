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
        targetSdk = 30     // Android 11 — min target that can request MANAGE_EXTERNAL_STORAGE
        versionCode = 1
        versionName = "0.0.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
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
    androidTestImplementation("androidx.test:runner:1.6.2")
}
