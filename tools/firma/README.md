# Firma dell'APK col certificato Persoft

L'APK di release viene firmata con il certificato **Persoft di Patassini Alessandro**,
la cui chiave privata sta su un token Certum (`cryptoCertum 3.7`) e non è esportabile.
Ogni firma richiede quindi il token inserito: **non è automatizzabile in CI**.

```powershell
.\tools\firma\firma-apk.ps1
```

Compila la release, allinea, firma, verifica. Il risultato finisce in
`dist\app-release-persoft.apk`.

Per firmare un'APK già compilata (per esempio scaricata dagli artefatti della CI):

```powershell
.\tools\firma\firma-apk.ps1 -NoBuild -Apk .\app-release-unsigned.apk
```

## Il PIN

Non va mai scritto in un file versionato. Lo script lo cerca, in ordine, in:

1. il parametro `-Pin`
2. `$env:PERSOFT_PIN` (variabile utente, già impostata su questa macchina di build)
3. `$env:FASTDESK_TOKEN_PIN`
4. il file `.tokenpin` nella radice del repo (in `.gitignore`)

Ad apksigner il PIN arriva come `--ks-pass env:PERSOFT_PIN`, non sulla riga di
comando: altrimenti sarebbe leggibile da chiunque guardi la lista dei processi.

## Perché serve `ApkSignerPkcs11.java`

`apksigner` sa parlare con un keystore PKCS#11, ma lo istanzia col vecchio
costruttore `new SunPKCS11(configFile)`. Su JDK 9+ quel costruttore non esiste più
e il modulo `jdk.crypto.cryptoki` non esporta nemmeno il package, quindi il
tentativo diretto muore così:

```
java.lang.IllegalAccessException: class com.android.apksigner.SignerParams cannot
access class sun.security.pkcs11.SunPKCS11 ... does not export sun.security.pkcs11
```

Il wrapper configura il provider con l'API supportata (`Provider.configure`), lo
registra, e poi passa la palla ad apksigner indicandogli il provider per nome
(`--ks-provider-name SunPKCS11-Certum`). È l'unico modo pulito su JDK moderni.

## Il token ha due profili, e uno solo funziona

| Profilo | Libreria PKCS#11 | Stato |
|---|---|---|
| `common profile` | `crypto3PKCS.dll` | **PIN valido — qui sta la chiave Persoft** |
| `profil bezpieczny` (secure profile) | `cryptoCertum3PKCS64.dll` | **PIN bloccato**, inutilizzabile |

Puntare la libreria sbagliata dà `CKR_PIN_LOCKED` e non c'è PIN che tenga:
il contatore del profilo sicuro è esaurito. `certum-common.cfg` punta già a
`crypto3PKCS.dll`, che espone solo il common profile.

## Conseguenze della scelta, da tenere a mente

- **La firma diventa l'identità permanente dell'app.** Android accetta un
  aggiornamento solo se firmato con la stessa chiave. Il certificato scade il
  **15/04/2027**: al rinnovo Certum genera una chiave nuova, e da quel momento gli
  aggiornamenti richiederanno una disinstallazione con perdita dei dati locali.
  Se il token si perde o si blocca, stessa storia.
- **Non aggiunge fiducia.** Android ignora la catena CA: l'avviso "origine
  sconosciuta" resta identico. Il certificato qui serve come identità, non come
  garanzia.
- **La versione firmata Persoft e quelle della CI non convivono.** Le release
  `apk-<branch>` che pubblica la CI sono firmate con la chiave di sviluppo del
  repository: non si installano sopra quella Persoft, né viceversa. Passare da un
  canale all'altro costa una disinstallazione — vedi
  [docs/FIRMA-E-PUBBLICAZIONE.md](../../docs/FIRMA-E-PUBBLICAZIONE.md).

## Se qualcosa non va

- `Unable to establish loopback connection` durante la build: i socket AF_UNIX
  creati sotto `AppData\Local\Temp` falliscono su questa macchina, e Gradle non
  riesce a parlare col proprio daemon. Lo script sposta già `TEMP` in
  `_temp\tmp`; fuori dallo script serve fare lo stesso.
- `CKR_PIN_LOCKED`: si sta puntando il secure profile — vedi tabella sopra.
- Il token non risponde: verificare che proCertum CardManager veda la carta.
