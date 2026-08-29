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

## Come è messa su

Quattro pezzi, nessuno dei quali sta nel repository:

| pezzo | segreto GitHub | `keystore.properties` |
|---|---|---|
| il file `.jks`, in base64 | `SIGNING_KEYSTORE_BASE64` | `storeFile` (percorso vero) |
| password del portachiavi | `SIGNING_KEYSTORE_PASSWORD` | `storePassword` |
| nome della chiave | `SIGNING_KEY_ALIAS` | `keyAlias` |
| password della chiave | `SIGNING_KEY_PASSWORD` | `keyPassword` |

In CI il workflow scrive il `.jks` in `app/persoft-release.jks` e passa le tre password come
variabili d'ambiente; il runner è usa-e-getta e finito il run sparisce tutto con lui. Su una
macchina di lavoro basta un `keystore.properties` accanto al progetto — il `.gitignore` tiene
fuori sia quello sia qualunque `.jks`.

**Se manca anche uno solo dei quattro**, la build di release esce firmata con la chiave di
sviluppo invece di fallire: chi clona il progetto deve poterlo compilare. In quel caso l'APK lo
dice — `BuildConfig.SIGNED_BY_PERSOFT` è falso e il log scrive `firma di sviluppo` invece di
`firma Persoft` nella prima riga, che è l'unico posto da cui, dal telefono, la cosa si vede.

## Mettere il portachiavi nel segreto

Da PowerShell, sulla macchina che ha il `.jks`:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("C:\percorso\persoft-release.jks")) | Set-Clipboard
```

Poi su GitHub: **Settings › Secrets and variables › Actions › New repository secret**, uno per
riga della tabella qui sopra.

## Il passaggio costa una disinstallazione, una volta sola

Le build già installate sono firmate con la chiave di sviluppo tenuta nel repository. Il primo
APK firmato Persoft ha un certificato diverso, e Android lo rifiuterà con «il pacchetto è in
conflitto con un pacchetto esistente». Va disinstallata l'app **una volta**, e da lì in poi gli
aggiornamenti tornano a installarsi sopra come prima. Non c'è modo di evitarlo: è esattamente la
protezione per cui la firma esiste.

Si perdono le impostazioni e i lavori in coda, che vivono nella cartella privata dell'app. Le
foto no: quelle stanno in `DCIM › Luna Ultra`, che è memoria condivisa e la disinstallazione non
la tocca.
