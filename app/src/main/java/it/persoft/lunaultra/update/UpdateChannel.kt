package it.persoft.lunaultra.update

import kotlinx.serialization.Serializable

/**
 * Da dove arrivano gli aggiornamenti.
 *
 * I due canali servono a due cose diverse e convivono per questo. [PERSOFT] è la distribuzione:
 * una cartella sul sito con l'APK firmata col certificato Persoft e un manifest che ne dichiara
 * versione e impronta — la stessa forma usata da ViewerImage per Android. [GITHUB] è lo sviluppo:
 * la release che la CI pubblica per ogni ramo, utile per provare un branch prima che diventi una
 * versione.
 *
 * Non sono intercambiabili sullo stesso telefono: le build del sito sono firmate col token
 * Certum, quelle della CI con la chiave di sviluppo del repository, e Android non installa l'una
 * sopra l'altra. Passare da un canale all'altro costa una disinstallazione.
 */
@Serializable
enum class UpdateChannel {
    /** Il sito: `https://www.persoft.it/lunaultra/`. */
    PERSOFT,

    /** La release del branch su GitHub. */
    GITHUB;

    val etichetta: String get() = when (this) {
        PERSOFT -> "sito Persoft"
        GITHUB -> "release GitHub"
    }
}
