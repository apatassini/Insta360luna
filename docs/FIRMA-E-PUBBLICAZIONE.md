# Firma dell'APK e pubblicazione

## Perché la firma conta

Android non guarda il nome del pacchetto per decidere se un aggiornamento è lo stesso programma:
guarda il **certificato**. Due APK con lo stesso `applicationId` ma firme diverse sono, per il
sistema, due app che si contendono lo stesso nome — e la seconda non si installa sopra la prima.
È il motivo per cui una chiave fissa serviva già quando la firma era di sviluppo.

Ma una build di **debug** porta anche `android:debuggable` nel manifest, e con quel bit acceso:

- chiunque abbia un cavo può attaccarsi al processo dell'app;
- il sistema toglie certe ottimizzazioni della macchina virtuale;
- l'installer e Play Protect trattano il pacchetto da programma in prova, con un avviso in più a
  ogni installazione.

Una **release** firmata con una chiave vera è un'app come le altre. È tutto quello che cambia, ed
è abbastanza.

---

## La chiave vera è su un token, e questo decide tutto il resto

La chiave di firma di questo progetto è il certificato **Persoft di Patassini Alessandro**
(Certum Code Signing 2021 CA, impronta SHA-1 `2A6D58A5B9223B78A439E5A8206FC087C936BD47`), e la
sua parte privata vive dentro un token hardware Certum. **Non è esportabile**: non esiste nessun
file `.jks` da dare a Gradle, e non esiste nessun segreto da mettere su GitHub.

Da qui discendono tre conseguenze che non sono opinabili:

1. **La CI non può firmare.** Il token deve essere fisicamente inserito nella macchina che firma.
2. **La versione distribuita si compila e si firma in locale**, con `tools\firma\firma-apk.ps1`.
3. **Le build della CI e quella distribuita sono due app diverse per Android**, perché hanno
   certificati diversi. Passare dall'una all'altra costa una disinstallazione.

### Come si firma

```powershell
.\tools\firma\firma-apk.ps1
```

Compila `assembleRelease`, allinea, firma col token via PKCS#11, verifica. Il risultato è
`dist\app-release-persoft.apk`.

La build esce **non firmata** perché lo script imposta `LUNA_FIRMA_ESTERNA`: senza, Gradle la
firmerebbe con la chiave di sviluppo e poi ci si firmerebbe sopra — un ottimo modo per
distribuire, un giorno, quella di debug senza accorgersene.

Il PIN non finisce mai sulla riga di comando: lo script lo passa ad apksigner in una variabile
d'ambiente. Lo cerca in `-Pin`, poi in `$env:PERSOFT_PIN`, poi in `.tokenpin` (che il
`.gitignore` tiene fuori). Dettagli e trappole del token in
[tools/firma/README.md](../tools/firma/README.md).

### Il keystore JKS che c'è ma non si usa

`app/build.gradle.kts` sa ancora firmare con un `.jks` più tre password, presi dai segreti del
progetto in CI o da un `keystore.properties` in locale. È rimasto perché è la strada da prendere
il giorno in cui servisse una firma automatica, e perché costa niente tenerlo. **Oggi non è
configurato**: i segreti non ci sono, e infatti le release che pubblica la CI escono firmate
`CN=Luna Timelapse Debug`.

`BuildConfig.SIGNED_BY_PERSOFT` dice la verità in entrambi i casi, e la si legge dal telefono in
**Impostazioni › Aggiornamenti › Firma**.

---

## Due canali di aggiornamento, e non sono intercambiabili

| | **sito Persoft** | **release GitHub** |
|---|---|---|
| indirizzo | `https://www.persoft.it/lunaultra/` | release `apk-<branch>` del repository |
| firma | certificato Persoft (token) | chiave di sviluppo del repository |
| chi pubblica | `tools\pubblica\pubblica-aggiornamento.ps1`, a mano | la CI, a ogni push |
| a cosa serve | la versione che si usa | provare un ramo prima che diventi una versione |
| come sceglie l'app | `versionCode` più alto di quello installato | commit diverso da quello della build |

Si sceglie dal telefono, in **Impostazioni › Aggiornamenti**. Di serie è il sito.

**Passare da un canale all'altro costa una disinstallazione**, perché le firme sono diverse.
Android lo dice a modo suo: «il pacchetto è in conflitto con un pacchetto esistente».

### Pubblicare una versione

Con il token inserito:

```powershell
.\tools\pubblica\pubblica-aggiornamento.ps1 -Note "Cosa è cambiato."
```

Compila, firma, e pubblica lo stesso APK in due posti: la cartella del sito (`J:\lunaultra`, la
webroot di persoft.it montata come disco) e la release GitHub `persoft`. Sono lo stesso file:
due APK diversi con lo stesso numero di versione sarebbero il modo più rapido per non capire più
cosa c'è installato sul telefono.

Il numero di build lo calcola da sé — uno più del massimo fra quello già pubblicato sul sito e
l'ultimo numero di run della CI — così il `versionCode` cresce sempre, qualunque canale abbia
pubblicato per ultimo. Si può forzare con `-BuildNumber`.

Lo script si rifiuta di pubblicare se l'APK non è firmata Persoft o se il suo `versionCode` non
è quello che si aspettava.

Sul sito **l'APK va a posto per prima e il manifest per ultimo**: finché il manifest non cambia i
telefoni vedono la versione precedente, e non esiste un istante in cui annunciano una versione il
cui APK è ancora a metà upload.

---

## L'aggiornamento che non chiede conferma

Aprire un APK con `ACTION_VIEW` — la strada ovvia — significa consegnarlo al Package Installer,
che chiede conferma **a ogni aggiornamento, per sempre**. Non dipende dalla chiave: non c'è
certificato che tolga quella schermata.

L'app usa invece una sessione del `PackageInstaller` con
`setRequireUserAction(USER_ACTION_NOT_REQUIRED)`. Da **Android 12** il sistema accetta di saltare
la conferma a quattro condizioni: che l'app aggiorni se stessa, che sia stata lei a installare la
versione precedente, che la firma sia la stessa e che non sia un ritorno indietro.

La seconda condizione è quella che si conquista sul campo: **la prima volta la conferma compare
lo stesso**, ed è proprio quell'installazione a renderci «installer of record». Da lì in avanti
gli aggiornamenti passano in silenzio.

Sotto Android 12 la conferma resta comunque, e qualche ROM (MIUI e simili) ne mette una propria
che da qui non si governa.

---

## Il passaggio costa una disinstallazione, una volta sola

Le build installate prima di questa sono firmate con la chiave di sviluppo tenuta nel
repository. Il primo APK firmato Persoft ha un certificato diverso, e Android lo rifiuterà con
«il pacchetto è in conflitto con un pacchetto esistente». Va disinstallata l'app **una volta**, e
da lì in poi gli aggiornamenti tornano a installarsi sopra come prima. Non c'è modo di evitarlo:
è esattamente la protezione per cui la firma esiste.

Si perdono le impostazioni e i lavori in coda, che vivono nella cartella privata dell'app. Le
foto no: quelle stanno in `DCIM › Luna Ultra`, che è memoria condivisa e la disinstallazione non
la tocca.
