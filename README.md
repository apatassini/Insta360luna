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

Due cose misurate sul campo (firmware **v1.0.288**) che correggono l'estrazione pubblica:

- **la risposta non rimanda indietro il codice del comando: porta sempre `200`.** A correlare
  richiesta e risposta è solo il `requestId`;
- **la camera non manda messaggi `Error`.** A un codice inesistente, a un payload spazzatura e a
  un comando reale senza argomenti risponde allo stesso modo: `200` con corpo vuoto.

E soprattutto: **la camera accetta una sola connessione di controllo alla volta.** Se l'app
Insta360 ufficiale è attiva, prende lei la sessione e questa app viene chiusa fuori — non è un
bug, è il comportamento della camera. Non si possono usare le due app insieme, e non si possono
"intercettare" i comandi dell'app ufficiale collegandosi in parallelo: servirebbe una cattura
del traffico a livello di rete.

Comandi usati dall'app, tutti con numero noto:

| Codice | Comando | Uso |
|---|---|---|
| 4 / 5 | `START_CAPTURE` / `STOP_CAPTURE` | registrazione video |
| 7 / 8 | `SET_OPTIONS` / `GET_OPTIONS` | batteria (opzione 11), storage (20), modello (48), seriale (15), firmware (30) |
| 15 | `GET_CURRENT_CAPTURE_STATUS` | sta registrando? da quanto? |
| 17 / 18 | `GET` / `SET_TIMELAPSE_OPTIONS` | durata e intervallo |
| 22 / 23 | `START` / `STOP_TIMELAPSE` | timelapse interno della camera |
| 3 | `TAKE_PICTURE` | scatto singolo |
| 9 | `SET_PHOTOGRAPHY_OPTIONS` | proporzione della panoramica (sferica / 2:1) |

### Le modalità della camera: due trappole misurate

**Il comando di scatto non dice cosa scattare.** A decidere è la sotto-modalità in cui si trova
la camera: con `photo_sub_mode = 8` (`PHOTO_INSTA_PANO`) un `TAKE_PICTURE` produce una
panoramica, con `photo_sub_mode = 0` (`PHOTO_SINGLE`) uno scatto normale — comando identico. Per
questo l'app imposta la sotto-modalità con `SET_OPTIONS` prima di ogni scatto invece di darla
per buona: la camera può essere stata cambiata dal suo schermo mentre l'app era aperta.

**`TakePicture.Mode` non è `CaptureMode`.** Due enum con nomi simili e valori diversi: in
`CaptureMode` il normale è 1, in `TakePicture.Mode` l'1 è l'**AEB**, il bracketing di
esposizione, e il normale è 0. Passare la costante sbagliata non dà errore — la camera esegue un
bracketing e basta.

| Modalità | Come la si seleziona | Come si scatta |
|---|---|---|
| Foto | `SET_OPTIONS` `PHOTO_SUB_MODE(40)` = `PHOTO_SINGLE(0)` | `TAKE_PICTURE` con `Mode.NORMAL(0)` |
| Panorama | `PHOTO_SUB_MODE(40)` = `PHOTO_INSTA_PANO(8)` | `TAKE_PICTURE`; sferica o 2:1 da `PANO_ASPECT` |
| Video | `VIDEO_SUB_MODE(41)` = `VIDEO_NORMAL(0)` | `START` / `STOP_CAPTURE` con `Capture_MODE_NORMAL(1)` |
| Timelapse | `VIDEO_SUB_MODE(41)` = `VIDEO_TIMELAPSE(2)` | `START` / `STOP_TIMELAPSE` |

La panoramica è **una sola sotto-modalità** anche se le proporzioni sono due: la scelta viaggia
su `PANO_ASPECT` (`PhotographyOptionType` 98) con `SET_PHOTOGRAPHY_OPTIONS`, che porta anche il
`function_mode` a cui la modifica si riferisce — `FUNCTION_MODE_NORMAL_POWER_PANO_IMAGE(14)`.
I valori sono `PANO_ASPECT_360 = 1` e `PANO_ASPECT_2_1 = 4`.

La camera lascia l'altra sotto-modalità al suo valore sentinella (`*_NONE = 100`) invece di
azzerarla: per sapere in che modalità è, il video vince quando è diverso da `VIDEO_NONE`.

### Restare connessi

Due cose diverse, tutte e due necessarie:

- **un servizio in primo piano.** Da Android 12 un'app che finisce in background viene congelata
  dopo pochi secondi: il keep-alive smette di battere e la camera chiude la sessione. È il
  «si disconnette quando cambio app». Il servizio tiene il processo sveglio, con un wake lock e
  un Wi-Fi lock, e mostra una notifica che dice cosa sta girando;
- **il riaggancio automatico.** Se la sessione cade lo stesso, l'app riprova da sola a distanze
  crescenti (2, 4, 8, 16 secondi…) finché non riesce o finché non si arrende dopo sei tentativi.
  Smette solo quando sei tu a premere «disconnetti».

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

L'app è un mirino: l'anteprima occupa tutto lo schermo e i comandi ci stanno sopra, come in
un'app di ripresa. Un tocco sull'immagine nasconde i comandi e lascia solo l'inquadratura.

1. Collegare il telefono alla rete Wi-Fi della Luna Ultra.
2. Aprire l'app e premere **Connetti**, al centro dell'anteprima
   (l'app forza il routing dei socket sulla rete Wi-Fi anche se non offre Internet).
   Batteria, spazio e stato compaiono subito nella riga in alto: se li vedi, il protocollo gira.
3. **Accendi l'anteprima** con il primo tasto della colonna dei comandi rapidi, a destra.
4. **La prima volta**: menu ⋮ → **Diagnostica** → trovare il codice del comando gimbal
   (vedi sotto). È l'unico passo di configurazione, e finché manca i comandi di movimento
   restano visibili ma inerti.
5. Scegliere la **modalità** sulla ghiera in basso: foto, video e timelapse comandano la camera
   e basta; sequenza, sequenza TL e panorama percorrono i punti memorizzati.
6. Con la **levetta** (o la croce, che muove un asse alla volta) portare il gimbal sulla prima
   inquadratura e premere il tasto con la **bandierina**. Ripetere per gli altri punti.
7. Nel pannello **Sequenza** impostare durata e, in modalità panorama, scatti per tratto e
   attesa prima dello scatto.
8. Premere il **pulsante di scatto**: fa la cosa che dice la ghiera, e nelle modalità guidate
   l'anello attorno mostra l'avanzamento.
9. **STOP** — sul pulsante stesso o nella scheda dell'avanzamento — interrompe tutto
   immediatamente: ferma il gimbal e la registrazione.

I punti e le impostazioni sono salvati in JSON nella memoria privata dell'app e ricaricati
all'avvio.

---

## L'interfaccia

L'impaginazione è quella di una camera: una fascia piena in alto, l'immagine al centro, una
fascia in basso con lo scatto e la ghiera. Le fasce sono opache e l'anteprima ci sta dentro
invece che sotto — un tocco sull'immagine le toglie e l'anteprima si allarga a tutto schermo.

| Dove | Cosa |
|---|---|
| Fascia in alto | connessione (si tocca per connettere), distintivo della modalità, anteprima on/off, griglia, impostazioni, menu ⋮ |
| Sull'immagine, in alto a sinistra | spazio libero, batteria, gimbal pronto o no, cronometro di registrazione |
| Sull'immagine, in basso | azzera posizione, pastiglia del tempo che conta nella modalità, comandi del gimbal |
| Fascia in basso | memorizza punto · **pulsante di scatto** · sequenza e tempi · interpolazione, e sotto la ghiera delle modalità |
| Al centro | invito a connettersi, oppure il motivo per cui l'anteprima è ancora nera |

Ogni modalità ha un colore e lo porta ovunque: la voce accesa nella ghiera, il pieno del
pulsante di scatto, il distintivo in alto. Il colore si riconosce con la coda dell'occhio mentre
si guarda l'inquadratura; una scritta va letta.

Il tasto ☰ accanto alla ghiera apre l'elenco completo delle modalità con la descrizione di
ognuna e l'avviso se non è utilizzabile — una modalità guidata senza punti memorizzati non parte.

Ruotando il telefono la fascia di scatto passa sul lato destro e il pannello del gimbal si
rimpicciolisce: in orizzontale l'altezza è il bene scarso.

Tre pannelli si aprono sopra il mirino e si chiudono con Indietro: **Sequenza e punti**
(riepilogo a numeri grandi, punti in griglia con la bussola di dove guardano),
**Impostazioni** (camera, anteprima, movimento manuale) e **Diagnostica**.

### Le modalità della ghiera

| Modalità | Cosa fa il pulsante di scatto |
|---|---|
| **Panorama** | la panoramica della camera: sferica 360° o 2:1, si sceglie con la pastiglia sopra lo scatto |
| **Foto** | uno scatto singolo normale |
| **Video** | avvia e ferma la registrazione |
| **Timelapse** | avvia e ferma il timelapse interno della camera, a gimbal fermo |
| **Sequenza** | percorre i punti memorizzati registrando video |
| **Sequenza TL** | percorre i punti con il timelapse interno della camera |
| **Sequenza foto** | si ferma a ogni scatto lungo il percorso, per le foto da unire in post |

Scegliere una modalità **mette davvero la camera in quella modalità**: la ghiera non è un
promemoria di cosa farà l'app, è un comando che parte. All'aggancio succede il contrario — la
ghiera adotta la modalità in cui la camera si trova già.

Le tre modalità guidate hanno bisogno di almeno due punti: senza, il pulsante di scatto resta
spento. Sceglierne una dalla ghiera equivale a sceglierla nel pannello della sequenza, e
viceversa: è la stessa impostazione detta in due posti.

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

Sulla Luna Ultra 1.0.288 il MJPEG OSC non c'è, e lo stream di controllo arriva in **H.265**
(1280×720 a 29 fps, dichiarati dalla notifica 8234), non in H.264: il codec viene riconosciuto
dai set di parametri nel flusso, non dato per scontato.

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

La modalità **panorama** è quella per le foto da unire in post produzione. Due dettagli che
cambiano il risultato:

- **l'attesa prima dello scatto** esiste perché il gimbal ha inerzia. Fotografare subito dopo un
  movimento dà scatti mossi, e in una panoramica il difetto si vede proprio sulle giunzioni;
- **il punto in comune fra due tratti si scatta una volta sola**. Due foto identiche nello stesso
  punto complicano l'unione invece di aiutarla.

---

## Trovare il codice del comando gimbal

Tutto nella scheda **Diagnostica**, con la camera connessa e ferma.

### Il metodo

L'idea di partenza era usare i codici di errore come oracolo: `UNKNOWN_MSG_CODE` (comando
inesistente) contro `UNKNOWN_MSG_PAYLOAD` (comando esistente, argomenti sbagliati). **Misurato
sulla camera, non funziona**: la Luna Ultra non manda messaggi `Error`, risponde `200` con corpo
vuoto in tutti e tre i casi.

Resta un segnale, ed è quello che l'app usa: **un codice che risponde con dati a un corpo vuoto
esiste ed è un getter** — restituisce qualcosa senza bisogno di argomenti.
`PHONE_COMMAND_GET_PTZ_OPTION` è esattamente uno di questi, e trovarlo dà anche il vicinato in
cui cercare gli altri comandi PTZ.

Un corpo vuoto resta la sonda più prudente possibile, e i comandi distruttivi (cancellazione
file, riavvio, ripristino di fabbrica, Wi-Fi) e l'intero blocco di fabbrica `12288+` sono
esclusi a monte: lì un corpo vuoto non protegge, perché un comando senza argomenti si limita a
eseguire.

### I passi

1. **Misura le risposte note.** L'app confronta un codice sicuramente inesistente con casi reali
   e ti dice, in chiaro, quale segnale è disponibile su questa camera. Se non ce ne fosse
   nessuno, si rifiuta di scansionare invece di produrre migliaia di righe senza significato.
2. **Scansiona una gamma.** La più promettente è **Blocco richieste (4096–8191)**: è dichiarato
   `PHONE_REQUEST_*` e l'estrazione pubblica non ci mette dentro niente — un pan/tilt interattivo
   è esattamente ciò per cui esiste un blocco «richieste». In alternativa i buchi dentro
   **Comandi telefono (0–152)**, dove atterrano le aggiunte successive al 2020.
3. **Prova i candidati.** In cima alla lista stanno quelli che rispondono con dati: esistono di
   sicuro. Il pulsante *Usa per il gimbal* imposta il codice; poi la croce direzionale,
   guardando la camera.

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

Le velocità massime in °/s si tarano in Impostazioni → Movimento manuale (o in Diagnostica,
insieme al resto della taratura fine): cronometra una rotazione completa e correggi. Da quelle dipende la corrispondenza fra la durata impostata e il movimento reale.

L'interpolazione fra due waypoint segue le formule della specifica:

```text
lineare:  position(t) = start + (end - start) * t
smooth:   smooth(t)   = t² * (3 - 2t)
          position(t) = start + (end - start) * smooth(t)
```

### Timelapse interno o video normale?

La ghiera sceglie fra `START_TIMELAPSE` e `START_CAPTURE`: le modalità *timelapse* usano il
primo, *video* e *sequenza* il secondo. Con il gimbal pilotato dall'app la registrazione video normale è di solito la
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
└─ ui/           MainActivity, MainViewModel, LunaApp (mirino + pannelli)
   ├─ viewfinder/  ViewfinderScreen, ghiera delle modalità, pulsante di scatto, HUD,
   │               pannello del gimbal, griglia, avanzamento della sequenza
   ├─ components/  vetro dei comandi sovrapposti, levetta, croce, anteprima, campi
   ├─ screens/     Sequenza, Impostazioni, Diagnostica
   └─ theme/       palette scura, tipografia, icone
```

Il core (tutto tranne `ui/` e `WifiNetworkBinder`) non dipende dall'SDK Android ed è testato
come codice JVM puro: 54 test coprono framing UCD2 e checksum, riaggancio al magic dopo byte
spuri, frame media, riconoscimento degli errori, corpi dei comandi confrontati con quelli
osservati, riconoscimento dei NAL Annex-B e dei keyframe, conteggio degli scatti di una
panoramica, roundtrip protobuf, interpolazione, calcolo delle durate, un giro completo su socket
in loopback e le **risposte reali catturate dalla camera** — batteria, spazio disco a 64 bit,
modello, seriale e firmware, byte per byte come sono arrivati.

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
  Da lì viene anche lo **schema del protocollo della Luna Ultra** con i numeri delle
  sotto-modalità, di `PanoAspect` e dei `FunctionMode`: misurati su questa camera, non dedotti.
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
