# Il gimbal e la sua taratura

Il gimbal della Luna Ultra si comanda con il codice **226** (`0x00E2`) e **non dice mai dove
si trova**. Tutto quello che segue discende da questa mancanza.

Codice: `gimbal/GimbalController.kt` (movimento e stima), `gimbal/GimbalCalibrator.kt` (il
flusso), `gimbal/GimbalGyroMeter.kt` (registrazione e misura), `stitch/InstaTrailer.kt`
(lettura del sensore) e `data/GimbalCalibration.kt` (il profilo salvato).

---

## 1. Navigazione a stima, e perché è fragile

Non c'è ritorno di posizione, quindi durante l'uso:

```
posizione += velocitàGiroscopicaMisurata × tempo
```

Prima la velocità nasceva dividendo gli estremi di catalogo per gli impulsi contati fra i fine
corsa. Il difetto era nel numeratore: **la corsa in gradi non l'aveva misurata nessuno** —

```
pan   −57° … +235°     (292° di corsa)
tilt  −57° … +120°     (177° di corsa)
```

Ora quei numeri servono solo a descrivere i limiti. La curva di velocità viene dal giroscopio
della camera: per ogni intensità registra un'andata e un ritorno e integra direttamente i gradi.

Nota sullo zero: i gradi seguono il sistema pubblicato da Insta360, dove **lo zero è
l'inquadratura frontale all'accensione**, non il punto medio meccanico. È per questo che la
Luna ha poca corsa a sinistra dello zero e molta di più a destra.

---

## 2. La scoperta: il gimbal si muove più di quanto gli si chiede

Le nove foto di una panoramica, misurate una contro l'altra riconoscendo i dettagli, dicevano
che a **32° chiesti ne corrispondevano 42**. Il verticale sbagliava del **31%**, l'orizzontale
del **22%**.

Il che implica una corsa reale di circa **232°** in verticale e **357°** in orizzontale. Quel
357 assomiglia a un giro intero troppo per essere un caso.

L'errore è **puramente moltiplicativo**, quindi non c'è niente da rifare: si moltiplica per un
fattore e tutta la scala si rimette a posto. I due fattori vivono nel profilo
(`panAngularScale`, `tiltAngularScale`) e li misura **l'unione delle panoramiche**, a ogni
unione, confrontando quanto le foto si sono spostate davvero con quanto era stato chiesto.

Misura del 26 agosto, nove scatti:

```
Inclinazione presa dalla gravità: 0°→6,9°, 32°→47,0°, −32°→−32,1°
(il gimbal si muove ×1,235 di quanto gli si chiede)
```

---

## 3. Il gimbal non è lineare

Le giunzioni della stessa panoramica, chiesto contro fatto:

| giunzione | chiesti | fatti | rapporto | dettagli |
|---|---|---|---|---|
| verticale 1→6 | +38,89° | +39,32° | ×1,011 | 236 |
| verticale 2→5 | +39,00° | +39,49° | ×1,013 | 11 |
| verticale 3→4 | +39,01° | +39,57° | ×1,014 | 294 |
| verticale 6→7 | +40,15° | +40,31° | ×1,004 | 56 |
| orizzontale 5→6 | −49,13° | −58,19° | ×1,184 | 12 |

Il verdetto lo dice in chiaro:

> **I rapporti ballano di 0,180: non è solo scala storta, il movimento risponde in modo
> diverso a seconda di dove si trova.**

(Quei rapporti sono già *dopo* la correzione ×1,235 sugli angoli nominali: quello che resta è
il residuo non lineare.)

Conseguenza pratica: **chi misura poco misura un tratto di corsa, non la corsa.** Una
panoramica corta, tutta nella stessa zona dell'asse, dà un fattore giusto **per quella zona** e
sbagliato altrove.

---

## 4. L'altalena, e la regola che ne è nata

Successe questo, in due unioni consecutive:

1. Una panoramica di **quattro** foto, verticale, con **una sola giunzione** misurata, a
   ±47°, riscrisse nel profilo un fattore ×1,111.
2. Il giro dopo, la panoramica di **nove** foto a ±32° lo riscrisse indietro a ×1,235.
3. Poi di nuovo le quattro, poi di nuovo le nove.

**Nessuna delle due misure era sbagliata.** Erano misure di due tratti diversi di un asse non
lineare, e ognuna, applicata all'altro tratto, peggiorava le cose.

La regola, per parole dell'utente: *la taratura va fatta solo su panoramiche che hanno almeno
un tot numero di foto.* Tradotta in codice
(`PanoramaStitcher`, costanti in fondo al file):

| costante | valore | perché |
|---|---|---|
| `CALIBRATION_MIN_SHOTS` | **6** | sotto, si sta misurando un tratto |
| `CALIBRATION_MIN_JUNCTIONS` | **2** per asse | una sola giunzione non dice se l'errore è costante |
| `CALIBRATION_MIN_TILT_LEVELS` | **3** altezze diverse | perché la gravità possa tirare una retta che significhi qualcosa |

In più il fattore **non si scrive mai se è stato preso in prestito dall'altro asse**
(`borrowed`): un pan dedotto dal tilt non è una misura del pan.

**Cosa resta comunque acceso.** Il gate riguarda **solo la scrittura del profilo**. La
correzione misurata su *questa* panoramica si applica lo stesso a *questa* panoramica — il che
è giusto: là il fattore è misurato esattamente dove serve. Il log dice sempre cosa è successo e
perché:

```
Scala orizzontale misurata ×1,111 non scritta nel profilo: servono almeno 6 scatti, qui sono 4
```

---

## 5. Le strade per misurare la scala

### 5.1 La curva completa: il giroscopio continuo (preferita)

Il blocco `0x0300` di un video — e del suo proxy LRV — contiene l'intera registrazione del
giroscopio a **1 kHz**. La taratura fa, per ogni intensità e per ogni asse:

1. avvia il live stream, indispensabile perché la Luna salvi la cattura;
2. registra un breve video e usa il tratto iniziale fermo per misurare il bias;
3. comanda un movimento positivo, una pausa e lo stesso movimento negativo;
4. scarica solo l'LRV, riconosce i due tratti e integra il quaternione tridimensionale;
5. media i gradi dei due versi e cancella solo il clip temporaneo appena creato.

Le intensità 1–10% durano due secondi, le altre uno. La misura non dipende dal campo visivo,
dalla scena o dalla corsa dichiarata.

### 5.2 Il tilt nelle foto: la gravità (controllo indipendente)

Con tre o più altezze diverse, la gravità dà l'inclinazione vera di ogni scatto e la scala è
la pendenza della retta «comandato → misurato». **Non dipende da niente di ottico**, non
dipende dal riconoscimento dei dettagli, non fallisce su una parete uniforme. È la misura
migliore che il sistema abbia per verificare a posteriori il verticale.

### 5.3 Le foto e la chiusura del giro (rifinitura)

Per il pan la gravità è cieca, ma il giroscopio continuo del video non lo è. Le foto restano
comunque un secondo righello:

- **le giunzioni orizzontali** della panoramica (nella tabella qui sopra: una sola, con 12
  dettagli — troppo poco per fidarsi);
- la **chiusura del giro**, riconoscendo che l'immagine è tornata dov'era.

Queste misure possono rifinire il profilo, ma non sono più la sorgente primaria della curva.

---

## 6. Le prove che il calibratore esegue

| prova | cosa misura | come |
|---|---|---|
| curva di risposta | gradi al secondo per ogni intensità 1…100 | andata e ritorno integrati dal giroscopio a 1 kHz nell'LRV |
| fine corsa | estremi raggiungibili e tempo per attraversarli | la camera annuncia il limite, il tempo si cronometra |
| andata e ritorno | se il modello sa muoversi | 45° in orizzontale, +45°/−30° in verticale al 30%, e si guarda quanto torna indietro |
| ripetibilità dello zero | se lo zero resta lo zero | ritorno a zero e confronto dell'inquadratura entro 28 px |

Il profilo è valido solo se ha almeno **8 intensità** misurate, fra cui **una sotto il 10%** e
**il 100%**, e fine corsa affidabili su entrambi gli assi. Quando non lo è, `invalidReason`
dice **quale intensità e quale asse** mancano — perché «Misure insufficienti (42/42)» non è una
diagnosi: quel 42 su 42 dice che tutti i campioni raccolti erano buoni, e nasconde che a
mancare erano quelli mai raccolti.

Nota su L/M/V: le tre velocità nominali **non entrano nel modello**. Il relativo comando può
andare in timeout e le prove fisiche indicano la stessa velocità; la variabile affidabile è
l'intensità 1…100 mandata direttamente al comando 226.

---

## 7. Riassunto: i numeri di oggi

| grandezza | valore | fonte | fiducia |
|---|---|---|---|
| corsa pan di catalogo | −57°…+235° | specifiche | usata per i limiti, non per la curva |
| corsa tilt di catalogo | −57°…+120° | specifiche | usata per i limiti, non per la curva |
| pan al 20% | **12,86 °/s** | giroscopio, andata/ritorno | scarto 0,12° |
| pan al 50% | **32,14 °/s** | giroscopio, andata/ritorno | scarto 0,42° |
| pan al 80% | **51,30 °/s** | giroscopio, andata/ritorno | scarto 0,02° |
| scostamento dello zero in tilt | **+6,9°** | gravità | ripetibile entro 0,1° |
| non linearità | ±0,180 sul rapporto | giunzioni | reale, non ancora modellata |

---

*© Persoft di Patassini Alessandro — licenza MIT*
