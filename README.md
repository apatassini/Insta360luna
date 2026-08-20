# Luna Ultra Timelapse Controller

App Android (Kotlin + Jetpack Compose) per pilotare una **Insta360 Luna Ultra** via Wi-Fi e
realizzare timelapse con movimento automatico del gimbal fra punti memorizzati.

Implementa le specifiche MVP: connessione TCP alla camera, sessione con handshake e keep-alive,
lettura stato, controllo manuale pan/tilt, memorizzazione waypoint, interpolazione lineare o
`SmoothStep`, esecuzione della sequenza con start/stop registrazione e pulsante di STOP.

---

## ⚠️ Stato del progetto: leggere prima di usarla

Insta360 non pubblica il protocollo di controllo, ma non è terra incognita: framing, comandi e
messaggi sono stati ricostruiti da più progetti di reverse engineering indipendenti, alcuni
verificati proprio sulla Luna Ultra. Questa app usa **quei numeri**, non ipotesi.

Resta però un buco, ed è esattamente quello che serve qui:

| Parte | Stato |
|---|---|
| Framing UCD2, checksum, handshake, keep-alive, correlazione richiesta/risposta | **Noto e verificato sulla Luna Ultra** |
| Stato camera, batteria, storage, avvio/stop registrazione, opzioni timelapse | **Numeri di comando e di campo noti** |
| **Comando del gimbal (pan/tilt)** | **Nome noto, numero ignoto** — nessuna fonte pubblica lo riporta |
| Lettura della posizione PTZ | Codice della notifica molto probabile (8302), contenuto non decodificato |

`PHONE_COMMAND_GIMBAL_CONTROL` esiste con questo nome nell'app Insta360 2.30.0, insieme ad altri
13 comandi PTZ. I loro **numeri** no: l'app Android è protetta da AppShield (bytecode cifrato a
runtime), il firmware della camera è cifrato per intero e l'IPA iOS è protetta da FairPlay.
Tutte e tre le strade statiche sono chiuse, ognuna per un motivo diverso.

Per questo l'app **non inventa** il numero mancante. Finché non è noto rifiuta di muovere il
gimbal, e la scheda **Diagnostica** contiene lo scanner che lo trova interrogando la camera.

---

## Il protocollo, in breve

Controllo su `192.168.42.1:6666`, framing **UCD2**, payload **protobuf**
(namespace `insta360.messages`).

```text
 0   4   'U' 'C' 'D' '2'      magic
 4   1   0x01                 versione
 5   1   0x0c                 flag
 6   1   tipo                 0x01 media, 0x04 comando/risposta, 0x05 stream
 7   1   seq                  contatore a 8 bit
 8   4   len (LE)             lunghezza del corpo
12   len corpo
     4   checksum (LE)        CRC-32 variante Insta360, su tutto ciò che precede
```

Nei frame di comando il corpo si apre con 9 byte:

```text
 0   2   code (LE)            codice messaggio; la risposta lo rimanda indietro
 2   1   direzione            0x02 richiesta, 0x03 risposta
 3   2   requestId (LE)       correla richiesta e risposta (non il seq!)
 5   4   0x00008000 (LE)      costante
 9   ..  messaggio protobuf
```

La sessione **non** si apre con un comando: si autorizza inviando un frame di tipo `0x05` a
lunghezza zero con il token costante `f6 cc 4f 09`, ripetuto ogni 3 secondi come keep-alive.

Comandi usati dall'app, tutti con numero noto:

| Codice | Comando | Uso |
|---|---|---|
| 4 / 5 | `START_CAPTURE` / `STOP_CAPTURE` | registrazione video |
| 7 / 8 | `SET_OPTIONS` / `GET_OPTIONS` | batteria (opzione 11), storage (20), modello (48), seriale (15), firmware (30) |
| 15 | `GET_CURRENT_CAPTURE_STATUS` | sta registrando? da quanto? |
| 17 / 18 | `GET` / `SET_TIMELAPSE_OPTIONS` | durata e intervallo |
| 22 / 23 | `START` / `STOP_TIMELAPSE` | timelapse interno della camera |

---

## Compilare l'APK

### In CI (consigliato)

Il workflow `.github/workflows/android.yml` compila a ogni push e pubblica l'APK di debug come
artefatto della run (`luna-timelapse-debug`). Non serve nulla installato in locale.

### In locale

Requisiti: JDK 17, Android SDK (compileSdk 35).

```bash
./gradlew assembleDebug        # APK in app/build/outputs/apk/debug/
./gradlew testDebugUnitTest    # test del core (protocollo, framing, interpolazione, sequenza)
```

In alternativa, aprire la cartella con Android Studio (Ladybug o successivo).

---

## Come si usa

1. Collegare il telefono alla rete Wi-Fi della Luna Ultra.
2. Aprire l'app, scheda **Controllo**, premere **Connetti**
   (l'app forza il routing dei socket sulla rete Wi-Fi anche se non offre Internet).
   Batteria, modello e stato di registrazione compaiono subito: se li vedi, il protocollo gira.
3. **Accendi** l'anteprima: da lì in poi vedi dove punti mentre memorizzi i punti.
4. **La prima volta**: scheda **Diagnostica** → trovare il codice del comando gimbal
   (vedi sotto). È l'unico passo di configurazione.
5. Scheda **Sequenza**: scegliere **cosa vuoi fare** — video, timelapse della camera, o foto a
   scatti per una panoramica.
6. In **Controllo**, con la croce direzionale portare il gimbal sulla prima inquadratura e
   premere **Memorizza punto**. Ripetere per gli altri (A, B, C…).
7. In **Sequenza** impostare durata (totale o per tratto) e, in modalità foto, scatti per tratto
   e attesa prima dello scatto.
8. Tornare in **Controllo** e premere **AVVIA**.
9. **STOP** interrompe tutto immediatamente: ferma il gimbal e la registrazione.

I punti e le impostazioni sono salvati in JSON nella memoria privata dell'app e ricaricati
all'avvio.

---

## Anteprima dal vivo

Due trasporti, provati in quest'ordine:

1. **MJPEG via OSC** — `POST http://192.168.42.1/osc/commands/execute` con
   `{"name":"camera.getLivePreview"}`. Quando la camera lo offre è la strada migliore: un JPEG
   per fotogramma, nessun decoder, nessun keyframe da attendere.
2. **Stream della sessione di controllo** — `START_LIVE_STREAM` (codice 1) con i campi
   `2 enableVideo, 6 videoBitrate, 7 resolution, 8 enableGyro, 9 videoBitrate1, 10 resolution1`.
   Il video non torna come risposta: arriva sui frame media della stessa connessione TCP
   (tipo `0x01`, sottoflusso `0x20`) come stream elementare Annex-B, che l'app decodifica con
   MediaCodec su una `SurfaceView`.

La sorgente in uso è scritta sotto l'anteprima. Se resta nera, il messaggio sopra dice a che
punto si è fermata — rifiuto della camera, attesa del primo keyframe, flusso chiuso.

---

## Le tre modalità

La scelta non cambia solo quale comando parte: cambia il significato della durata che imposti.

| Modalità | Cosa fa | La durata è… |
|---|---|---|
| **Video** | Registra video normale lungo tutto il percorso | tempo reale di ripresa: l'accelerazione la fai in montaggio |
| **Timelapse camera** | Usa il timelapse interno (`START_TIMELAPSE`) | il tempo che la camera comprimerà da sé |
| **Foto a scatti** | Si ferma a ogni punto, aspetta, scatta (`TAKE_PICTURE`) | il tempo di movimento **fra** uno scatto e il successivo |

La modalità **foto** è quella per le panoramiche da unire in post produzione. Due dettagli che
cambiano il risultato:

- **l'attesa prima dello scatto** esiste perché il gimbal ha inerzia. Fotografare subito dopo un
  movimento dà scatti mossi, e in una panoramica il difetto si vede proprio sulle giunzioni;
- **il punto in comune fra due tratti si scatta una volta sola**. Due foto identiche nello stesso
  punto complicano l'unione invece di aiutarla.

---

## Trovare il codice del comando gimbal

Tutto nella scheda **Diagnostica**, con la camera connessa e ferma.

### Il metodo

Il messaggio `Error` della camera distingue `UNKNOWN_MSG_CODE` (comando inesistente) da
`UNKNOWN_MSG_PAYLOAD` (comando esistente, argomenti sbagliati). Questa differenza è un oracolo:
inviando un corpo **vuoto** a un codice sconosciuto, una risposta «argomenti sbagliati» dice che
il comando c'è **e non ha eseguito nulla**.

È ciò che rende la scansione difendibile, dove sparare payload inventati non lo sarebbe: un
comando che rifiuta i suoi argomenti non è mai partito.

### I passi

1. **Calibra l'oracolo.** Confronta come risponde un codice sicuramente inesistente con come
   risponde uno reale. Se le due risposte fossero identiche la scansione non distinguerebbe
   nulla, e l'app si rifiuta di procedere invece di produrre migliaia di righe senza significato.
2. **Scansiona una gamma.** La più promettente è **Blocco richieste (4096–8191)**: è dichiarato
   `PHONE_REQUEST_*` e l'estrazione pubblica non ci mette dentro niente — un pan/tilt interattivo
   è esattamente ciò per cui esiste un blocco «richieste». In alternativa i buchi dentro
   **Comandi telefono (0–152)**, dove atterrano le aggiunte successive al 2020.
3. **Prova i candidati.** Chi rifiuta il corpo vuoto esiste e vuole argomenti: dal risultato,
   il pulsante *Usa per il gimbal* lo imposta. Poi la croce direzionale, guardando la camera.

Comandi distruttivi (cancellazione file, riavvio, ripristino di fabbrica, Wi-Fi) e l'intero
blocco di fabbrica `12288+` sono esclusi a monte dallo scanner: lì un corpo vuoto non protegge,
perché un comando senza argomenti si limita a eseguire.

### Il log

La scheda Diagnostica registra **ogni comando inviato e ogni risposta ricevuta**, con i byte
grezzi e i campi protobuf decodificati. Il pulsante **Condividi** lo salva su file e lo allega:
è il formato giusto per farlo analizzare, e l'intestazione porta host, stato, codice gimbal in
uso, modello e firmware.

### In parallelo: le notifiche

La sezione **Notifiche osservate** conta i frame che la camera manda di sua iniziativa. Muovendo
il gimbal dallo schermo della camera si vede quale codice si sveglia: uno con molti payload
distinti porta numeri che cambiano, uno che ripete gli stessi byte è un battito. Il predefinito
`8302` viene da traffico osservato durante il movimento del gimbal, compatibile con
`CAMERA_NOTIFICATION_PTZ_STATE` — indizio forte, non certezza.

### La via più rapida, se hai gli strumenti

Una cattura Wireshark del traffico dell'app ufficiale resta il modo più diretto: il codice del
gimbal si legge nei byte 12–13 di ogni frame `UCD2` inviato mentre muovi il joystick.

---

## Movimento del gimbal

Il movimento è **a velocità**: l'app invia comandi ripetuti a ~10 Hz e integra la posizione
stimata (dead reckoning). Non usa la posizione assoluta perché `PHONE_COMMAND_SET_PTZ_OPTION`
condivide lo stesso problema del comando di controllo — nome noto, numero no.

Le velocità massime in °/s si tarano in Diagnostica: cronometra una rotazione completa e
correggi. Da quelle dipende la corrispondenza fra la durata impostata e il movimento reale.

L'interpolazione fra due waypoint segue le formule della specifica:

```text
lineare:  position(t) = start + (end - start) * t
smooth:   smooth(t)   = t² * (3 - 2t)
          position(t) = start + (end - start) * smooth(t)
```

### Timelapse interno o video normale?

L'interruttore *Usa il timelapse interno* in Diagnostica sceglie fra `START_TIMELAPSE` e
`START_CAPTURE`. Con il gimbal pilotato dall'app la registrazione video normale è di solito la
scelta giusta: durata reale e durata della sequenza coincidono, e l'accelerazione la fai in
montaggio. Il timelapse interno comprime i tempi e rende difficile far quadrare le due cose.

---

## Architettura

```text
it.persoft.lunaultra
├─ protocol/     Ucd2 (framing + checksum), FrameAssembler, LunaProtocolCodes,
│                LunaMessages, ProtoWriter, ProtoReader, Hex
├─ net/          EventLog, SocketBinder, TcpClient, WifiNetworkBinder
├─ camera/       CameraSession (handshake, keep-alive, requestId), LunaCommands,
│                LunaError, CodeProbe (scanner), modelli
├─ preview/      AnnexB, VideoDecoder (MediaCodec), MjpegStream, PreviewController
├─ gimbal/       GimbalController (jog manuale, dead reckoning, drive verso target)
├─ timelapse/    Waypoint, TimelapseSequence, Interpolation, TimelapseEngine
├─ data/         AppSettings, JsonFileStore (persistenza JSON)
└─ ui/           MainActivity, MainViewModel, schermate Controllo / Sequenza / Diagnostica
```

Il core (tutto tranne `ui/` e `WifiNetworkBinder`) non dipende dall'SDK Android ed è testato
come codice JVM puro: 49 test coprono framing UCD2 e checksum, riaggancio al magic dopo byte
spuri, frame media, riconoscimento degli errori, corpi dei comandi confrontati con quelli
osservati, riconoscimento dei NAL Annex-B e dei keyframe, conteggio degli scatti di una
panoramica, roundtrip protobuf, interpolazione, calcolo delle durate e un giro completo su
socket in loopback.

---

## Crediti

Il protocollo non l'ho ricostruito io. Questa app sta sulle spalle di:

* **[Ripwords/insta360-luna-ultra-desktop](https://github.com/Ripwords/insta360-luna-ultra-desktop)**
  — framing UCD2, checksum, handshake e keep-alive, verificati sulla Luna Ultra; la
  [documentazione del buco nel protocollo](https://github.com/Ripwords/insta360-luna-ultra-desktop/blob/main/docs/PROTOCOL-GAP.md)
  è ciò che stabilisce che i numeri del gimbal non sono pubblici, e da lì viene sia il codice
  8302 osservato sia il metodo dell'oracolo sugli errori.
* **[RigacciOrg/insta360-wifi-api](https://github.com/RigacciOrg/insta360-wifi-api)** (GPLv3)
  — l'estrazione dei `.proto`, l'enum `MessageCode` con i 164 codici noti e i numeri di campo di
  `Options`, `BatteryStatus`, `CameraCaptureStatus`, `TimelapseOptions`.
* **[diamondfsd/luna-ai-cut](https://github.com/diamondfsd/luna-ai-cut)** — l'estrazione da cui
  parte il lavoro sulla Luna Ultra.
* **[Cedric-Hsu/insta360-go3s-mac-import](https://github.com/Cedric-Hsu/insta360-go3s-mac-import)**
  e **[NiklasVoigt/Insta360-Livestream](https://github.com/NiklasVoigt/Insta360-Livestream)**
  — conferme indipendenti del framing su altri modelli.

Il codice qui è scritto da zero in Kotlin; da quei progetti vengono i **fatti sul protocollo**
(numeri, offset, formato), non righe di codice.

---

## Fuori scope

Editing e download dei media, AI tracking, Deep Track, live streaming, cloud e account Insta360,
controllo multi-camera.

## Nota legale

Progetto non ufficiale, non affiliato a Insta360, basato su reverse engineering del protocollo
di controllo per interoperabilità con la propria camera.
