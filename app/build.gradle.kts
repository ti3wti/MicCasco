plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "cr.wmarin.miccasco"
    compileSdk = 35

    defaultConfig {
        applicationId = "cr.wmarin.miccasco"
        minSdk = 26
        targetSdk = 35
        // GitHub Actions numera cada compilación; ese número es la versión.
        val run = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionCode = run
        versionName = "1.$run"
    }

    // Firma fija: permite instalar cada versión nueva encima de la anterior.
    signingConfigs {
        create("fija") {
            storeFile = file("miccasco.keystore")
            storePassword = "miccasco"
            keyAlias = "miccasco"
            keyPassword = "miccasco"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("fija")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fija")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}
