plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * Numero della build in CI. Serve a far crescere il `versionCode` a ogni pubblicazione:
 * Android rifiuta di installare sopra una versione uguale o più recente, e con un numero fisso
 * ogni aggiornamento sarebbe un reinstall a mano.
 */
val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "0").toIntOrNull() ?: 0
val gitSha = System.getenv("GITHUB_SHA") ?: "local"

/**
 * Branch da cui esce questa build.
 *
 * L'aggiornamento automatico legge la release del branch, e il nome della release lo decide il
 * workflow a partire da qui. Compilarlo dentro l'APK e' cio' che permette a una build di
 * cercare gli aggiornamenti del proprio branch invece che di uno fisso scritto nel codice: con
 * un branch fisso, ogni ramo nuovo esce da sotto i piedi all'app gia' installata.
 */
val gitBranch = System.getenv("GITHUB_REF_NAME") ?: "local"

android {
    namespace = "it.persoft.lunaultra"
    compileSdk = 35

    defaultConfig {
        applicationId = "it.persoft.lunaultra"
        minSdk = 26
        targetSdk = 35
        versionCode = 1 + buildNumber
        versionName = "0.2.$buildNumber"
        buildConfigField("String", "GIT_SHA", "\"$gitSha\"")
        buildConfigField("String", "GIT_BRANCH", "\"$gitBranch\"")
    }

    /**
     * Chiave di firma fissa, tenuta nel repository.
     *
     * Senza, ogni macchina che compila — e ogni run della CI, che parte da zero — genera una
     * `debug.keystore` nuova: l'APK esce firmato con un certificato diverso ogni volta e Android
     * rifiuta l'aggiornamento con «il pacchetto è in conflitto con un pacchetto esistente»,
     * costringendo a disinstallare e perdere punti e impostazioni a ogni versione.
     *
     * È una chiave di debug e va trattata come tale: non protegge niente, serve solo a far
     * riconoscere le build come la stessa app. Se un giorno l'app venisse distribuita davvero,
     * la firma di release va fatta con una chiave privata tenuta fuori dal repository.
     */
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "luna"
            keyPassword = "android"
        }
    }

    buildTypes {
        // La build di debug è quella che viene distribuita, firmata con la chiave fissa qui
        // sopra. Senza R8 pesa 10 MB di dex non ottimizzato, che su una connessione incerta è
        // un download che non arriva in fondo.
        debug {
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
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
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.exifinterface)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    debugImplementation(libs.androidx.ui.tooling)
    testImplementation(libs.junit)
}
