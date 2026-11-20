plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.example.companion"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.example.companion"
        minSdk = 26
        targetSdk = 34
        versionCode = 38
        versionName = "0.38"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
// No runtime dependencies on purpose: platform APIs only. JUnit and org.json are test-only (JVM tests of Stage 7 and Stage 8).
dependencies {
    testImplementation("junit:junit:4.13.2")
    // Test-only: the android.jar org.json is a stub on a plain JVM (SmsMessagesTest encodes/decodes JSON).
    testImplementation("org.json:json:20231013")
}
