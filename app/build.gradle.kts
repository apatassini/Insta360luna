// Esplicito, e non `java.util.Properties` scritto per intero più sotto: dentro uno script Gradle
// `java` è anche il nome dell'estensione del plugin Java, e a seconda di come vengono generati
// gli accessori quel nome vince sul package — con un «Unresolved reference: util» che non dice
// niente. In CI passava, su questa macchina no.
import java.util.Properties

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
val buildNumber = (System.getenv("LUNA_BUILD_NUMBER") ?: System.getenv("GITHUB_RUN_NUMBER") ?: "0")
    .toIntOrNull() ?: 0
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

/**
 * La chiave di firma Persoft, quando c'è.
 *
 * Si prende da due posti, e nessuno dei due sta nel repository. In CI dai segreti del progetto,
 * che il workflow trasforma in un file e in tre variabili d'ambiente; su una macchina di lavoro
 * da un `keystore.properties` accanto al progetto, che il `.gitignore` tiene fuori. Se non c'è
 * nessuno dei due la build di release esce firmata con la chiave di sviluppo qui sotto: meglio
 * un APK che si installa e lo dice, che una compilazione che fallisce su chi clona il progetto
 * e non ha nessuna chiave.
 *
 * Le password non stanno **mai** in un file versionato, e nemmeno il `.jks`: chi ha quei quattro
 * pezzi può firmare aggiornamenti che il telefono installa sopra l'app vera senza chiedere
 * niente a nessuno, ed è esattamente la ragione per cui Android si fida della firma.
 */
val keystoreProperties = Properties().apply {
    val local = rootProject.file("keystore.properties")
    if (local.isFile) local.inputStream().use { load(it) }
}

fun signingSetting(property: String, variable: String): String? =
    (keystoreProperties.getProperty(property) ?: System.getenv(variable))?.takeIf { it.isNotBlank() }

val persoftStore = file(signingSetting("storeFile", "SIGNING_KEYSTORE_FILE") ?: "persoft-release.jks")
    .takeIf { it.isFile }
val persoftStorePassword = signingSetting("storePassword", "SIGNING_KEYSTORE_PASSWORD")
val persoftKeyAlias = signingSetting("keyAlias", "SIGNING_KEY_ALIAS")
val persoftKeyPassword = signingSetting("keyPassword", "SIGNING_KEY_PASSWORD")
val signedByPersoft = persoftStore != null && persoftStorePassword != null &&
    persoftKeyAlias != null && persoftKeyPassword != null

/**
 * La firma la mette qualcun altro, dopo.
 *
 * La chiave Persoft di questo progetto vive su un token hardware Certum e non è esportabile:
 * Gradle non può usarla, perché non esiste nessun file da dargli. Con questa variabile la build
 * di release esce **non firmata** e ci pensa `tools\firma\firma-apk.ps1`, che parla col token via
 * PKCS#11. Farla uscire firmata di debug e poi rifirmarla sopra sarebbe un modo per dimenticarsi,
 * un giorno, di aver distribuito quella di debug.
 */
val firmaEsterna = !System.getenv("LUNA_FIRMA_ESTERNA").isNullOrBlank()

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
        // L'ora della compilazione: nelle schermate si legge questa, non il commit.
        buildConfigField("long", "BUILT_AT_MS", "${System.currentTimeMillis()}L")
        // Con che chiave è uscito questo APK. Dal telefono non si vede in nessun altro modo, e
        // quando un aggiornamento viene rifiutato la prima domanda è proprio questa.
        buildConfigField("boolean", "SIGNED_BY_PERSOFT", "${signedByPersoft || firmaEsterna}")
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
        // La chiave vera, creata solo se i quattro pezzi ci sono davvero.
        if (signedByPersoft) {
            create("persoft") {
                storeFile = persoftStore
                storePassword = persoftStorePassword
                keyAlias = persoftKeyAlias
                keyPassword = persoftKeyPassword
                // Firma v1 più v2 più v3: la v1 serve ad Android 6 e precedenti, che qui non
                // ci sono (minSdk 26), ma costa niente e toglie di mezzo gli installer che la
                // cercano lo stesso. La v3 è quella che permetterà, un giorno, di cambiare
                // chiave senza disinstallare.
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
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
        // Questa è la build che si distribuisce da quando c'è la chiave Persoft.
        //
        // La differenza con la debug non è il nome: una build di debug porta
        // `android:debuggable`, e con quel bit acceso il telefono la tratta da programma in
        // prova — chiunque abbia il cavo può attaccarsi al processo, il sistema toglie certe
        // ottimizzazioni, e Play Protect ci mette il naso a ogni installazione. Una release
        // firmata con una chiave vera è un'app come le altre.
        release {
            signingConfig = when {
                // Con LUNA_FIRMA_ESTERNA l'APK esce nudo: la firma la mette il token Certum
                // subito dopo, e l'output si chiama app-release-unsigned.apk.
                firmaEsterna -> null
                signedByPersoft -> signingConfigs.getByName("persoft")
                // Senza chiave si firma con quella di sviluppo. L'APK si installa lo stesso, e
                // `SIGNED_BY_PERSOFT` dice la verità a chi lo guarda dal telefono.
                else -> signingConfigs.getByName("debug")
            }
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
