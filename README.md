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
| ISO, otturatore, EV, WB, Standard/i-Log/Dolby Vision, filtri Leica/cinema, risoluzioni/FPS | **Rimisurati sulla Luna Ultra reale** |
| **Comando del gimbal (pan/tilt)** | **Verificato sulla Luna Ultra**: `0x00E2`, assi ZigZag e stop esplicito |
| Lettura della posizione PTZ | Codice della notifica molto probabile (8302), contenuto non decodificato |

Il movimento è stato implementato sulla base delle catture e dell'adattatore Luna Ultra di
[Insta360Linker](https://github.com/wuhaiqi0621/Insta360Linker), riproducendo il protocollo nella
codebase Android senza copiare la sua interfaccia.

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
| 13 | `GET_FILE_LIST` | elenco dei media sulla camera, a pagine |
| 30 | `GET_MINI_THUMBNAIL` | miniatura di un file |
| 226 / `0x00E2` | `GIMBAL_CONTROL` | vettore continuo pan/tilt e stop |

### Gimbal Luna Ultra

Il corpo del comando è `08 01 12 <len> <assi>`. L'app converte la levetta UI nel sistema della
camera con `device_x = verticale` e `device_y = orizzontale`, quindi codifica i due valori
`-100..100` come protobuf ZigZag. Il rilascio invia `08 01 12 00` quattro volte a 25 ms, così
un singolo pacchetto perso non lascia il gimbal in movimento. Il pannello offre inoltre i tre
livelli hardware della camera (lento, medio, veloce) tramite il comando fotografia 9 osservato
nelle catture; l'intensità software della levetta resta un controllo separato.

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
| PureVideo | `VIDEO_SUB_MODE(41)` = `VIDEO_PURE(11)` | `START` / `STOP_CAPTURE` con `Capture_MODE_PURE_VIDEO(11)` |
| Slow-motion | `VIDEO_SUB_MODE(41)` = `VIDEO_SLOW_MOTION(9)` | `START` / `STOP_CAPTURE` con `Capture_MODE_SLOWMOTION(9)` |
| Timelapse | `VIDEO_SUB_MODE(41)` = `VIDEO_TIMELAPSE(2)` | `START` / `STOP_TIMELAPSE` |

La panoramica è **una sola sotto-modalità** anche se le proporzioni sono due: la scelta viaggia
su `PANO_ASPECT` (`PhotographyOptionType` 98) con `SET_PHOTOGRAPHY_OPTIONS`, che porta anche il
`function_mode` a cui la modifica si riferisce — `FUNCTION_MODE_NORMAL_POWER_PANO_IMAGE(14)`.
I valori sono `PANO_ASPECT_360 = 1` e `PANO_ASPECT_2_1 = 4`.

La camera lascia l'altra sotto-modalità al suo valore sentinella (`*_NONE = 100`) invece di
azzerarla: per sapere in che modalità è, il video vince quando è diverso da `VIDEO_NONE`.

### Opzioni Foto e Video

Il tasto con i cursori apre le regolazioni native della camera; le automazioni di movimento del
gimbal restano nel menu dedicato. Il pannello Video separa le tre scelte del formato
(risoluzione, rapporto e FPS) e, in modalità Pro, mostra ISO, otturatore, EV, bilanciamento del
bianco, profilo colore, filtro, intensità e nitidezza. PureVideo, Slow-motion e Timelapse
limitano automaticamente le combinazioni a quelle offerte dalla modalità reale.

I valori Luna-specifici sono stati confrontati con le misurazioni pubbliche di
[`Ripwords/insta360-luna-ultra-desktop`](https://github.com/Ripwords/insta360-luna-ultra-desktop):
fra le correzioni rispetto alle enum 2020 ci sono Standard=1, i-Log=2, Dolby Vision=5, i codici
2,7K 242–248, 48 fps e i crop 4K 2,35:1. Il protocollo di base deriva dalle estrazioni pubbliche
collegate da quel progetto e da [`RigacciOrg/insta360-wifi-api`](https://github.com/RigacciOrg/insta360-wifi-api).

### Galleria e scaricamento

I file si elencano **dalla sessione di controllo** e si scaricano **in HTTP**, e sono due cose
separate per un motivo: dal firmware 1.0.238 la camera non pubblica più l'indice HTTP delle
cartelle che le app scavalcavano prima, ma i singoli file restano scaricabili dal loro percorso
finché la sessione è aperta.

```text
GetFileList     { MediaType media_type = 1; uint32 start = 2; uint32 limit = 3 }
GetFileListResp { repeated string uri = 1; uint32 total_count = 2 }
```

Da lì escono percorsi come `/storage_internal/DCIM/Camera01/IMG_20260718_142012_00_002.jpg`, che
sono insieme l'identità del file e il suo indirizzo: `http://192.168.42.1<percorso>`.

Sull'elenco si applicano tre regole che l'occhio non vede ma che cambiano cosa compare:

- ogni video ha accanto un **proxy `.lrv`** a bassa risoluzione. Non è un file da mostrare: è
  l'anteprima, e viene accoppiato al suo video invece di finire in griglia da solo;
- le **foto in movimento** salvano un `.live.mp4` di servizio, che non è una ripresa;
- un **DNG** ha accanto il JPG dello stesso scatto, che è ciò che il telefono sa disegnare.

Le miniature costano: una foto della Luna Ultra pesa decine di megabyte e scaricarla per
disegnare un quadratino da 112 punti sarebbe assurdo.

La strada buona, quando c'è, è l'**API OSC**: `camera.listFiles` con `maxThumbSize` restituisce
insieme all'elenco una miniatura in base64 per ogni file, quaranta per richiesta. È lo standard
che Insta360 implementa sulle camere a 360° — la Luna Ultra non è nell'elenco ufficiale, quindi
l'app ci prova una volta all'apertura della galleria e, se non risponde, non ci torna più. Le
voci si legano ai file **per nome**, perché OSC riporta i percorsi come `/DCIM/...` mentre
l'elenco della sessione di controllo li dà come `/storage_internal/DCIM/...`.

Per quello che resta scoperto, in quest'ordine:

1. l'**anteprima EXIF** dentro il file, leggendone solo i primi 512 KB e chiudendo la
   connessione. Non si chiede un intervallo di byte con `Range` — c'è chi risponde 416 e chi
   ignora l'intestazione — si legge l'inizio e si smette: quello che non si legge non si scarica;
2. `GET_MINI_THUMBNAIL` sulla sessione di controllo, che **si spegne da solo** dopo tre rifiuti
   di fila. Non tutte le camere lo implementano, e il modo in cui una camera dice «non lo
   conosco» è far scadere l'attesa: pagare quel timeout su ogni casella è ciò che faceva
   sembrare le anteprime rotte;
3. per i video, il **primo fotogramma del proxy** `.lrv`, che va scaricato comunque per
   guardarli.

Se nessuna di queste porta un'immagine resta l'ultima spiaggia: **scaricare la foto intera e
ridurla**, una alla volta e per ultima. Costa quanto la foto, ma è l'unica strada che non dipende
da cosa la camera ha messo dentro il file, e il risultato finisce su disco — il prezzo si paga
una volta per file, non a ogni scorrimento.

Un file che non dà miniature nemmeno così finisce in un elenco e non viene più ritentato finché
non premi «Aggiorna».

### Guardare

A schermo intero vuol dire intero: le barre di sistema spariscono, i comandi si tolgono da soli
dopo un paio di secondi e tornano al tocco, e ruotando il telefono la foto prende tutto.

Tre modi di cambiare immagine, perché servono in momenti diversi: le **frecce** ai lati quando
si guarda con calma, il **tocco sul bordo** destro o sinistro quando si scorre veloce con il
pollice, il **trascinamento** quando si sfoglia. A foto ingrandita il trascinamento la sposta,
che è quello che serve lì.

Le **panoramiche** non si mostrano piatte: un'equirettangolare stesa deforma i poli e taglia in
due la scena dove i bordi si ricongiungono. Si aprono dentro la sfera — si trascina per girarsi
intorno, si stringe per allargare il campo visivo — e il tasto con l'icona della panoramica
torna alla vista piatta. Non c'è nessuna sfera da disegnare: due triangoli coprono lo schermo e
per ogni pixel il frammento calcola dove si sta guardando.

Con la **stella** si segnano i preferiti, che hanno un filtro tutto loro nella griglia e si
scaricano in blocco.

Mentre guardi, l'app **scarica in anticipo i due file successivi**, e tiene in cache locale
**gli ultimi dieci** aperti: avanti e indietro fra due scatti non ripaga due volte lo stesso
scaricamento. Le foto già decodificate restano in memoria fino a un sesto dell'heap, poi le più
vecchie vengono buttate.

### Restare connessi

Due cose diverse, tutte e due necessarie:

- **un servizio in primo piano.** Da Android 12 un'app che finisce in background viene congelata
  dopo pochi secondi: il keep-alive smette di battere e la camera chiude la sessione. È il
  «si disconnette quando cambio app». Il servizio tiene il processo sveglio, con un wake lock e
  un Wi-Fi lock, e mostra una notifica che dice cosa sta girando;
- **il riaggancio automatico.** Se la sessione cade lo stesso, l'app riprova da sola a distanze
  crescenti (2, 4, 8, 16 secondi…) finché non riesce o finché non si arrende dopo sei tentativi.
  Smette solo quando sei tu a premere «disconnetti».

Prima di richiedere un cambio rete, l'app controlla tutte le reti Wi-Fi attive e riutilizza
direttamente la Luna se il telefono è già collegato: in questo caso non apre il selettore di
Android. Riconosce sia l'SSID sia il collegamento/gateway verso `192.168.42.1`, compresi i
firmware che oscurano l'SSID nelle API moderne. Se la Luna non è ancora attiva, la selezione
iniziale usa l'SSID esatto trovato dalla scansione. La
Luna protegge l'access point con una password modificabile: Android non consente a un'app di
leggere quella già salvata nel telefono. Per questo basta una delle due strade:

- inserire una volta la **Password Wi-Fi Luna** nelle impostazioni dell'app;
- connettere una volta il telefono manualmente e poi premere Connetti: a sessione aperta l'app
  legge `Options.WifiInfo` dalla camera e conserva la password per gli avvii successivi.

La password non viene mai scritta nel log. Le richieste Wi-Fi sono serializzate e un vecchio
`WifiNetworkSpecifier` viene chiuso prima di aprirne un altro, per evitare il ciclo
connessione/disconnessione osservato su alcuni firmware Android.

---

## Compilare l'APK

### In CI (consigliato)

Il workflow `.github/workflows/android.yml` compila a ogni push e pubblica l'APK di debug come
artefatto della run (`luna-timelapse-debug`) e come release scaricabile. Non serve nulla
installato in locale.

**Il repository ha un ramo solo, `main`.** L'app è una: rami paralleli qui non servivano a
isolare lavoro indipendente, servivano solo a moltiplicare le release e a far cercare
l'aggiornamento nel posto sbagliato. Il lavoro si fa su `main` e la release da installare è
sempre `apk-main`.

**La firma è fissa e sta nel repository** (`app/debug.keystore`). Senza, ogni run della CI parte
da zero e genera una chiave di debug nuova: l'APK esce firmato con un certificato diverso ogni
volta e Android rifiuta l'aggiornamento con «il pacchetto è in conflitto con un pacchetto
esistente», costringendo a disinstallare — e a perdere punti e impostazioni — a ogni versione.
È una chiave di debug e non protegge niente: serve solo a far riconoscere le build come la
stessa app. Una distribuzione vera va firmata con una chiave di release tenuta fuori dal
repository.

Il `versionCode` cresce con il numero della run (`GITHUB_RUN_NUMBER`), perché Android non
installa sopra una versione con numero uguale o più alto. Una build fatta in locale vale 1 e
quindi **non** si installa sopra una build della CI: per provarla, disinstalla prima.

### Aggiornamenti automatici

All'avvio l'app interroga la release GitHub prima di qualsiasi collegamento alla camera.
Non tenta più di connettersi automaticamente alla Luna: concluso il controllo resta sul tasto
**Connetti**, che viene premuto dall'utente. Se il commit è cambiato,
scarica automaticamente `app-debug.apk`, verifica il digest
SHA-256 pubblicato da GitHub e apre l'installer di Android. La prima volta va consentito a Luna
Ultra di installare app da questa origine; Android richiede comunque la conferma finale per ogni
installazione, perché un'app normale non può aggiornarsi silenziosamente senza privilegi di
sistema. Se GitHub non è raggiungibile, il controllo non blocca la connessione alla camera.

**Quale release.** L'app ha **un ramo solo, `main`**, e la sua release è `apk-main`: si installa
da lì e si aggiorna da lì, senza scelte da fare.

Il nome non è però scritto a mano nel sorgente. Il workflow pubblica su `apk-<branch>` con le
barre sostituite da trattini, e l'app ricompone lo stesso nome partendo da
`BuildConfig.GIT_BRANCH`, cioè dal ramo che ha prodotto l'APK installato. Con un nome fisso nel
codice bastava lavorare su un ramo diverso perché l'app già sul telefono continuasse a
interrogare la release di quello vecchio e riferisse *app aggiornata* in buona fede — è successo,
ed è il motivo per cui adesso il ramo viaggia dentro l'APK.

Resta un campo **Impostazioni → Aggiornamenti**, che mostra la build installata e il ramo
interrogato e permette di puntare altrove. Vuoto significa "quello da cui vengo", ed è il caso
normale: serve solo se un giorno si vuole provare una build fuori da `main` senza reinstallare a
mano.

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

1. Aprire l'app mentre il telefono ha Internet: viene controllata prima la disponibilità di un
   aggiornamento. L'app non cambia rete da sola.
2. Collegare manualmente il telefono al Wi-Fi della Luna Ultra e premere **Connetti**.
3. L'app forza il routing dei socket sulla rete Wi-Fi della camera anche se non offre Internet.
   Batteria, spazio e stato compaiono subito nella riga in alto: se li vedi, il protocollo gira.
4. **Accendi l'anteprima** con il primo tasto della colonna dei comandi rapidi, a destra.
5. **La prima volta**: menu ⋮ → **Diagnostica** → trovare il codice del comando gimbal
   (vedi sotto). È l'unico passo di configurazione, e finché manca i comandi di movimento
   restano visibili ma inerti.
6. Scegliere la **modalità** sulla ghiera in basso: foto, video e timelapse comandano la camera
   e basta; sequenza, sequenza TL e panorama percorrono i punti memorizzati.
7. Con la **levetta** (o la croce, che muove un asse alla volta) portare il gimbal sulla prima
   inquadratura e premere il tasto con la **bandierina**. Ripetere per gli altri punti.
8. Nel pannello **Sequenza** impostare durata e, in modalità panorama, scatti per tratto e
   attesa prima dello scatto.
9. Premere il **pulsante di scatto**: fa la cosa che dice la ghiera, e nelle modalità guidate
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
| Sull'immagine, in basso | pastiglia del tempo che conta nella modalità, comandi del gimbal con **ricentra** e **mezzo giro** sempre in vista |
| Fascia in basso | galleria (o memorizza punto) · **pulsante di scatto** · impostazioni **Camera** · **Automazioni gimbal**, e sotto la ghiera delle modalità |
| Al centro | invito a connettersi, oppure il motivo per cui l'anteprima è ancora nera |

Ogni modalità ha un colore e lo porta ovunque: la voce accesa nella ghiera, il pieno del
pulsante di scatto, il distintivo in alto. Il colore si riconosce con la coda dell'occhio mentre
si guarda l'inquadratura; una scritta va letta.

Il tasto ☰ accanto alla ghiera apre l'elenco completo delle modalità con la descrizione di
ognuna e l'avviso se non è utilizzabile — una modalità guidata senza punti memorizzati non parte.

Ruotando il telefono la fascia di scatto passa sul lato destro e il pannello del gimbal si
rimpicciolisce: in orizzontale l'altezza è il bene scarso.

Quattro pannelli si aprono sopra il mirino e si chiudono con Indietro: **Galleria**,
**Automazioni gimbal**
(riepilogo a numeri grandi, punti in griglia con la bussola di dove guardano),
**Impostazioni** (camera, anteprima, movimento manuale) e **Diagnostica**.

### La galleria

Griglia dei file sulla camera, con filtro foto/video e i distintivi che dicono cos'è ogni cosa —
video, panoramica, RAW. Un tocco apre il file a schermo intero: le foto si ingrandiscono con due
dita, i video si riproducono dalla **copia locale del proxy**, non in streaming dalla camera —
il lettore di sistema apre connessioni sue, che non passano dal binding sulla rete della camera.

Tenendo premuto si entra in selezione multipla; il pulsante scarica i file scelti nella galleria
del telefono, in `Immagini/Luna Ultra` e `Filmati/Luna Ultra`, dicendo a che punto è — «3 di 6»,
non sei volte «scaricamento in corso». Lo scaricamento va diritto dentro
MediaStore invece di passare da una copia in cache: un video lungo occuperebbe due volte lo
spazio, e su un telefono pieno è la differenza fra riuscire e no. Continua anche se chiudi la
galleria.

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

Le impostazioni native e le funzioni aggiuntive non sono mescolate: il tasto con i cursori apre
il menu **Camera** (per il video: risoluzione, rapporto e FPS; per la foto: timer, formato e
regolazioni Auto/Pro), mentre il tasto con il percorso apre **Automazioni gimbal**. Il firmware
codifica risoluzione e FPS in un solo `VideoResolution`: il menu propone quindi solo
combinazioni atomiche realmente descrivibili dal protocollo, da 8K a 1080p.

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

### Le azioni del gimbal: 1, 2, 3

Il campo 1 di `GIMBAL_CONTROL` porta un'azione. **Nessuna estrazione pubblica dei `.proto`
contiene il gimbal** — la più completa (`RigacciOrg/insta360-wifi-api`) viene da una ONE RS, e
quella camera il gimbal non ce l'ha: 78 `PHONE_COMMAND_*` e nemmeno uno che lo nomini. Questi
tre numeri vengono quindi dalla camera vera, provati uno a uno:

| Azione | Payload | Cosa fa |
|---|---|---|
| 1 | `08 01 12 …` | vettore continuo pan/tilt e stop |
| 2 | `08 02` | ricentra **il lato in cui la camera si trova** |
| 3 | `08 03` | commuta fronte ↔ selfie: **è un interruttore** |

Le due trappole, misurate:

**La 2 non è uno zero assoluto, e il lato dipende da dove sei arrivato.** La camera si accende
sul fronte, a 0°. Girando il pan oltre il mezzo giro dalla partenza si passa nel lato selfie, e
da lì «centro» vuol dire 180°: il ricentraggio riporta a *quel* centro, non a zero. È l'intera
spiegazione di un comportamento che sembrava un baco del comando — dopo una corsa completa del
pan, ricentrare inquadrava tutt'altro. Non era il comando sbagliato: era l'altro lato.

Ne segue il modo di tornare allo zero vero, quello dell'accensione: **prima si commuta il lato
con la 3, poi si ricentra con la 2**. Il mirino ha il pulsante apposta, nel pannello del gimbal.
Il lato che l'app conosce resta però una convinzione — la camera non lo pubblica — quindi se il
gimbal viene girato dal suo schermo l'app non se ne accorge.

E ne segue anche una conseguenza sulla calibrazione: **la validazione non usa più il ricentraggio
come riferimento assoluto**. I quattro collaudi partono da casa, la cui posizione è misurata come
frazione della corsa e non cambia significato a seconda di dove si è passati. La camera non visita
più lo zero hardware durante la calibrazione.

**La 3 è un interruttore, non una rotazione.** Premuta una volta gira, premuta di nuovo torna. E
la camera **non dice da che lato si trova**: nessuna notifica decodificata finora lo riporta, per
cui il lato mostrato dal pulsante è una convinzione dell'app. Chi gira il gimbal dallo schermo
della camera la smentisce senza che l'app se ne accorga.

Da qui una conseguenza pratica nel codice: prima di commutare, l'app **ricentra**. Il
ricentraggio è relativo al lato, quindi partire dal centro è l'unico modo di sapere dove si
finisce — al centro dell'altro lato, cioè a 0°/0° del nuovo riferimento. Senza quel passo la
posizione stimata dopo un mezzo giro sarebbe un numero inventato.

Il mirino ha i due comandi **sempre in vista** sopra la levetta: **Ricentra** e **Selfie**, il
secondo acceso quando l'app crede di essere girata.

Se un firmware sposta il numero, si cambia da Diagnostica senza ricompilare; a zero il mezzo giro
torna a essere una rotazione di 180° calcolata sul profilo di calibrazione, che sceglie il verso
rimasto dentro i fine corsa — a +235°/-57° i due versi non sono intercambiabili.

### Trovare le azioni che mancano

Diagnostica → **Azioni del gimbal** scansiona un intervallo di numeri con l'anteprima accesa:
ogni azione lascia nel log la miniatura prima e dopo, ed è **il ricentraggio fra un tentativo e
l'altro** a rendere il log leggibile — senza, dopo la prima azione che muove non si capisce più
chi ha fatto cosa. È così che sono stati trovati la 2 e la 3.

**La via definitiva, però, non è provare: è leggere.** Il servizio che ascolta su TCP/6666 è il
binario `ins_link` sulla camera, e i descrittori protobuf compilati dentro un binario **portano i
nomi in chiaro**: un `strings` su `ins_link` tira fuori i nomi delle azioni, e `pbtk` ne
ricostruisce i `.proto` con i numeri. Serve una shell di root sulla camera, e quella è
documentata: [derrickyau9/insta360-luna-ultra-reverse-engineering](https://github.com/derrickyau9/insta360-luna-ultra-reverse-engineering)
descrive il bootstrap via porta 23456 (protocollo INSGUI), Dropbear come SSH di root a chiave e
`adbd` sulla 5555. Quel progetto non documenta il gimbal — ma dà l'accesso da cui il gimbal si
legge invece di indovinarlo.

### Calibrazione e ripetibilità dello zero

La calibrazione misura due cose diverse, e conviene tenerle separate perché falliscono per
motivi diversi:

1. **la curva di risposta** — quanti gradi al secondo produce ogni intensità dall'1% al 100%.
   Si misura sugli *spostamenti* fra un fotogramma e il successivo, a pochi secondi di
   distanza l'uno dall'altro;
2. **la ripetibilità dello zero** — se dopo un ricentraggio l'inquadratura torna dov'era.
   Si misura confrontando due immagini prese a **minuti** di distanza.

La seconda dipende dal mondo, non solo dal gimbal: in otto minuti le persone si spostano, la
luce gira, il vento muove le foglie. Per questo il riferimento visivo dello zero viene ripreso
all'inizio della validazione invece di essere ereditato dal primo fotogramma, lo scarto viene
creduto solo con un consenso RANSAC forte (altrimenti il log dice *scena cambiata*), e **un
esito negativo non butta via la curva**: viene registrato come avvertimento e il profilo si
salva lo stesso. Una curva valida e uno zero che si sposta sono un risultato utile — dicono di
rifare i waypoint, non di rifare gli otto minuti di misure.

Se la ripetibilità serve davvero, la calibrazione va fatta inquadrando qualcosa di **fermo**:
niente persone, niente traffico, niente rami al vento.

**La calibrazione non ricentra più all'avvio, e torna sempre a casa.** «Casa» è l'inquadratura in
cui la camera si trova quando si preme *Calibra*: la scegli tu, la scegli ferma. Prima la
procedura ricentrava subito sullo zero hardware e ci tornava dopo ogni fase — e siccome quello
zero guarda dove guarda il corpo camera, cioè quasi sempre verso chi sta usando la camera, gli
otto minuti di misure finivano puntati sulle persone in movimento. Che è il posto peggiore: il
confronto fra fotogrammi è lo strumento con cui la calibrazione misura, e su una scena che si
muove quello strumento non funziona.

Adesso la casa è il riferimento: dopo i fine corsa del pan, dopo quelli del tilt, fra
un'intensità e l'altra e alla fine, la camera ci torna.

**Il ritorno non usa le coordinate: rigioca i comandi.** Per disfare un movimento non serve
sapere quanti gradi al secondo fa il gimbal — basta rimandare lo stesso comando, per lo stesso
tempo, nel verso opposto: il tempo è simmetrico anche quando la velocità è ignota. E durante la
ricerca dei fine corsa la velocità *è* ignota, perché è esattamente ciò che si sta per misurare.
Tornare a casa con le coordinate significava fidarsi di un modello non ancora esistente, il cui
ripiego è 30°/s per il pan e 20°/s per il tilt: con quelli la casa usciva calcolata a 75° di tilt
quando era a un grado dall'orizzonte, e la curva veniva misurata puntando il soffitto.

Per lo stesso motivo **la posizione della casa in gradi è misurata, non integrata**: due tempi
comandati alla stessa intensità — da casa al primo fine corsa, e da un fine corsa all'altro — e
il loro rapporto dà la posizione della casa come frazione della corsa. La velocità si semplifica.

Sull'arrivo c'è la rifinitura visiva: il confronto con la miniatura di casa chiude gli ultimi
pixel, e se non è affidabile ci si ferma e il log lo dice. Se la casa è troppo vicina a un fine
corsa per contenere gli archi di misura da 45°, viene spostata del minimo indispensabile e il log
lo segnala.

Restano un paio di minuti sullo zero hardware: sono i quattro collaudi finali, che verificano le
coordinate contro i fine corsa e per definizione partono da lì. Finiti quelli, la camera torna a
casa.

### La scala in gradi viene dai fine corsa, non dal cronometro

Le immagini danno la **forma** della curva — quanto è più veloce il 100% del 10% — ma non la sua
scala in gradi. Quella nasceva dal tempo impiegato a percorrere la corsa, misurato contando gli
impulsi «in cui si è visto un movimento». Ed è lì che si rompeva: al 40% con impulsi da 650 ms
due fotogrammi consecutivi non si sovrappongono abbastanza perché il confronto funzioni, così un
impulso che ha mosso eccome finiva contato come fermo. **Un solo impulso perso su quindici sposta
la scala del 7%**, e su una corsa da 235° sono 16° — abbastanza da mancare il fine corsa e far
fallire la validazione dopo nove minuti di misure buone.

Due correzioni:

1. il tempo di corsa si misura **contando i comandi inviati**, che sono un fatto, invece degli
   impulsi visivamente confermati, che a quella velocità sono una deduzione sbagliata;
2. la validazione non boccia più: **misura**. Il fine corsa è verità assoluta — la camera lo
   annuncia — quindi si spinge il modello fino a toccarlo, si contano i gradi che *credeva* di
   aver percorso, e il rapporto con i gradi veri diventa la correzione di scala del profilo
   (`panAngularScale`, `tiltAngularScale`). Due misure per asse, una per verso, mediate: così un
   eventuale scostamento dello zero hardware si annulla invece di sommarsi.

Si fallisce solo se il fine corsa non arriva neanche cercandolo molto oltre la corsa prevista, o
se la correzione esce da un intervallo ragionevole: quello non è più taratura, è un sintomo —
gimbal ostacolato, segnale di limite sbagliato, corsa diversa da quella dichiarata.

I profili con `schemaVersion` 3 sono sistematicamente troppo veloci e vengono rifiutati: vanno
rifatti una volta.

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
* **[derrickyau9/insta360-luna-ultra-reverse-engineering](https://github.com/derrickyau9/insta360-luna-ultra-reverse-engineering)**
  — l'accesso di root alla camera (bootstrap sulla porta 23456, Dropbear, `adbd`) e la mappa dei
  servizi: è da lì che si sa che a rispondere sulla 6666 è il binario `ins_link`, cioè dove
  andare a leggere i numeri del gimbal invece di provarli.
* **[Cedric-Hsu/insta360-go3s-mac-import](https://github.com/Cedric-Hsu/insta360-go3s-mac-import)**
  e **[NiklasVoigt/Insta360-Livestream](https://github.com/NiklasVoigt/Insta360-Livestream)**
  — conferme indipendenti del framing su altri modelli.

Il codice qui è scritto da zero in Kotlin; da quei progetti vengono i **fatti sul protocollo**
(numeri, offset, formato), non righe di codice.

---

## Fuori scope

Editing dei media, AI tracking, Deep Track, live streaming, cloud e account Insta360,
controllo multi-camera.

## Nota legale

Progetto non ufficiale, non affiliato a Insta360, basato su reverse engineering del protocollo
di controllo per interoperabilità con la propria camera.
