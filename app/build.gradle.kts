plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

android {
    namespace = "studio.forest.keyboard"
    compileSdk = 35

    defaultConfig {
        applicationId = "studio.forest.keyboard"
        minSdk = 26
        targetSdk = 35
        versionCode = 12
        versionName = "0.9.3"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions { unitTests.isIncludeAndroidResources = true }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
