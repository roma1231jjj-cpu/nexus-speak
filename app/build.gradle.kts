plugins {
    id("com.android.application")
}

android {
    namespace = "com.local.aivoice"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.local.aivoice"
        // -Plowapi — только для сборки под старый эмулятор (скриншоты), релиз всегда 33
        minSdk = if (project.hasProperty("lowapi")) 28 else 33
        targetSdk = 34
        versionCode = 58
        versionName = "1.10.5"
        buildFeatures { buildConfig = true }
        buildConfigField("boolean", "SAFE_MODE",
                if (project.hasProperty("safe")) "true" else "false")
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.core:core:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

}
