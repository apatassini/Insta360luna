# Luna Ultra Timelapse Controller

App Android (Kotlin + Jetpack Compose) per pilotare una **Insta360 Luna Ultra** via Wi-Fi e
realizzare timelapse con movimento automatico del gimbal fra punti memorizzati.

Implementa le specifiche MVP: connessione TCP alla camera, sessione con handshake e keep-alive,
lettura stato, controllo manuale pan/tilt, memorizzazione waypoint, interpolazione lineare o
`SmoothStep`, esecuzione della sequenza con start/stop registrazione e pulsante di STOP.

---

## ⚠️ Stato del progetto: leggere prima di usarla

Il protocollo di controllo Insta360 (**UCD2 + protobuf** su `192.168.42.1:6666`) **non è
documentato pubblicamente**. Di esso sono noti host, porta e i nomi simbolici dei comandi
(`PHONE_COMMAND_GIMBAL_CONTROL`, `PHONE_COMMAND_GET_PTZ_OPTION`, …), **non** i loro id numerici
né gli schemi `.proto` dei payload.

Di conseguenza l'app è divisa in due parti con maturità diversa:

| Parte | Stato |
|---|---|
| Trasporto TCP, framing, encoder/decoder protobuf, sessione, motore timelapse, interpolazione, UI, persistenza | **Completi e coperti da test** |
| Id numerici dei comandi, numeri di campo dei payload, layout esatto dell'header | **Da ricavare sulla propria camera** (l'app fornisce gli strumenti per farlo) |

L'app **non inventa** i valori mancanti: un comando privo di id configurato non viene inviato e
l'errore viene mostrato a schermo. La schermata **Diagnostica** contiene tutto il necessario per
completare la mappatura senza ricompilare nulla.

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
3. Con la croce direzionale portare il gimbal sulla prima inquadratura, premere **Memorizza punto**.
4. Ripetere per gli altri punti (A, B, C…).
5. Scheda **Sequenza**: impostare durata totale (o durata per tratto), intervallo e tipo di
   movimento (`Lineare` o `Smooth`).
6. Tornare in **Controllo** e premere **AVVIA TIMELAPSE**.
7. **STOP** interrompe tutto immediatamente: ferma il gimbal e la registrazione.

I punti e le impostazioni sono salvati in JSON nella memoria privata dell'app e ricaricati
all'avvio.

---

## Completare la mappatura del protocollo

Tutto avviene nella scheda **Diagnostica**, con la camera connessa.

### 1. Verificare il layout dell'header

L'ipotesi di lavoro predefinita è un header di 16 byte, little endian:

```text
offset 0  uint32  lunghezza totale (header incluso)
offset 4  uint8   versione (2) — usata anche come marcatore di sincronizzazione
offset 5  uint8   tipo (0 richiesta, 1 risposta, 2 notifica)
offset 6  uint16  numero di sequenza
offset 8  uint32  id comando
offset 12 uint32  codice di errore
payload   protobuf
```

Nel log RX compaiono i byte grezzi ricevuti: se i frame vengono scartati («Frame scartato…»),
correggere offset, dimensioni, endianness o il valore del byte di versione finché la decodifica
è pulita. Ogni campo del layout è modificabile a caldo.

### 2. Trovare gli id dei comandi

* **Scanner comandi**: invia un payload vuoto a ogni id di un intervallo e annota chi risponde.
  Da usare con la camera ferma, senza registrazione in corso.
* **Invio manuale**: manda un singolo frame (id in decimale o `0x…` e payload esadecimale) e
  mostra la risposta decodificata campo per campo.
* Una cattura del traffico dell'app ufficiale (`tcpdump`/Wireshark su un hotspot condiviso)
  resta il metodo più rapido e affidabile.

Gli id trovati si inseriscono nei campi della sezione **Id dei comandi**; 0 = comando disattivato.

### 3. Regolare i payload

I payload sono composti campo per campo dal writer protobuf incluso: nella sezione **Parametri
gimbal** si impostano i numeri di campo di pan/tilt, la scala degli angoli (1 = gradi,
10 = decimi di grado), le velocità massime e le eventuali inversioni di segno.

---

## Movimento del gimbal

Due strategie, selezionabili in Diagnostica:

* **Velocità** (default): invia comandi di velocità a ~10 Hz e integra la posizione stimata
  (dead reckoning). Funziona anche se la camera non espone la posizione PTZ.
* **Posizione**: invia direttamente la posizione assoluta con `SET_PTZ_OPTION`. Più preciso,
  ma richiede che quel comando sia supportato e mappato.

Se arriva una posizione reale dalla camera (risposta a `GET_PTZ_OPTION` o notifica
`CAMERA_NOTIFICATION_PTZ_STATE`) la stima viene corretta automaticamente.

L'interpolazione fra due waypoint segue le formule della specifica:

```text
lineare:  position(t) = start + (end - start) * t
smooth:   smooth(t)   = t² * (3 - 2t)
          position(t) = start + (end - start) * smooth(t)
```

---

## Architettura

```text
it.persoft.lunaultra
├─ protocol/     Hex, ProtoWriter, ProtoReader, Ucd2Codec, FrameAssembler
├─ net/          EventLog, SocketBinder, TcpClient, WifiNetworkBinder
├─ camera/       LunaCommand, CommandRegistry, CameraSession, LunaCommands, modelli
├─ gimbal/       GimbalController (jog manuale, dead reckoning, drive verso target)
├─ timelapse/    Waypoint, TimelapseSequence, Interpolation, TimelapseEngine
├─ data/         AppSettings, JsonFileStore (persistenza JSON)
└─ ui/           MainActivity, MainViewModel, schermate Controllo / Sequenza / Diagnostica
```

Il core (tutto tranne `ui/` e `WifiNetworkBinder`) non dipende dall'SDK Android ed è testato
come codice JVM puro: 23 test coprono framing, risincronizzazione dopo byte spuri, roundtrip
protobuf, interpolazione, calcolo delle durate e un giro completo su socket in loopback.

---

## Fuori scope

Editing e download dei media, AI tracking, Deep Track, live streaming, cloud e account Insta360,
controllo multi-camera.

## Nota legale

Progetto non ufficiale, non affiliato a Insta360, basato su reverse engineering del protocollo
di controllo per interoperabilità con la propria camera.
