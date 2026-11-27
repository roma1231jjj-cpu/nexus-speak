import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
}

// ------------------------------------------------------------------ релизная подпись
// Ключ берётся из keystore.properties в корне проекта (файл в .gitignore) или из
// переменных окружения. Секретов в репозитории нет. Без ключа release-сборка падает:
// подписывать релиз debug-ключом запрещено.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.isFile) keystorePropsFile.inputStream().use { load(it) }
}

fun signingValue(prop: String, env: String): String? {
    val v = keystoreProps.getProperty(prop) ?: System.getenv(env)
    return v?.trim()?.takeIf { it.isNotEmpty() }
}

val releaseStorePath = signingValue("storeFile", "NEXUS_KEYSTORE")
val releaseStorePassword = signingValue("storePassword", "NEXUS_KEYSTORE_PASSWORD")
val releaseKeyAlias = signingValue("keyAlias", "NEXUS_KEY_ALIAS")
val releaseKeyPassword = signingValue("keyPassword", "NEXUS_KEY_PASSWORD")
val releaseKeyFile: File? = releaseStorePath?.let { p ->
    val f = File(p)
    if (f.isAbsolute) f else rootProject.file(p)
}
val hasReleaseKey = releaseKeyFile != null && releaseKeyFile.isFile
        && releaseStorePassword != null && releaseKeyAlias != null && releaseKeyPassword != null

android {
    namespace = "com.local.aivoice"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.local.aivoice"
        // -Plowapi — только для сборки под старый эмулятор (скриншоты), релиз всегда 33
        minSdk = if (project.hasProperty("lowapi")) 28 else 33
        targetSdk = 34
        versionCode = 61
        versionName = "1.10.8"
        buildFeatures { buildConfig = true }
        buildConfigField("boolean", "SAFE_MODE",
                if (project.hasProperty("safe")) "true" else "false")
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = releaseKeyFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (hasReleaseKey) signingConfigs.getByName("release") else null
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

if (!hasReleaseKey) {
    gradle.taskGraph.whenReady {
        val wantsRelease = allTasks.any { t ->
            val n = t.name
            n.contains("Release") && (n.startsWith("assemble") || n.startsWith("bundle")
                    || n.startsWith("package"))
        }
        if (wantsRelease) {
            throw GradleException(
                    "Нет ключа подписи: release-сборка отменена, debug-ключ для релиза запрещён.\n" +
                            "Создай keystore.properties в корне проекта:\n" +
                            "  storeFile=/абсолютный/путь/nexus-release.jks\n" +
                            "  storePassword=...\n" +
                            "  keyAlias=nexus\n" +
                            "  keyPassword=...\n" +
                            "или задай NEXUS_KEYSTORE, NEXUS_KEYSTORE_PASSWORD, NEXUS_KEY_ALIAS, " +
                            "NEXUS_KEY_PASSWORD."
            )
        }
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
