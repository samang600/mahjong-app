plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.mahjongapp"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.mahjongapp"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "0.3"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // Signed with the debug key so the APK installs directly; use your own key for a store.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { test ->
            System.getenv("ROBOLECTRIC_REPO_URL")?.let { test.systemProperty("robolectric.dependency.repo.url", it) }
            test.systemProperty("screenshotDir", layout.buildDirectory.dir("screenshots").get().asFile.path)
            test.testLogging { showStandardStreams = true }
            System.getenv("RECOGNITION_FAILURE_DIR")?.let { test.environment("RECOGNITION_FAILURE_DIR", it) }
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
