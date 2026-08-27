# La coda dei file Insta360

Ogni foto della Luna Ultra ha, **dopo il marcatore di fine JPEG**, un blocco che tutti i visori
ignorano. Dentro c'è una traccia inerziale a mille campioni al secondo. È da lì che vengono
l'inclinazione e il rollio veri di ogni scatto, e con quelli il campo visivo misurato.

Formato ricostruito leggendo i file di questo esemplare, byte per byte. Implementazione:
`app/src/main/java/it/persoft/lunaultra/stitch/InstaTrailer.kt`.

---

## 1. Come si riconosce

Gli **ultimi 32 byte** del file sono la firma, in ASCII:

```
8db42d694ccc418790edff439fe026bf
```

Se non c'è, non c'è coda: il file è una foto normale e basta.

## 2. La struttura, letta all'indietro

La coda finisce così:

```
[ … blocchi … ][ dimensione totale u32 ][ versione u32 ][ firma 32 byte ]
                 ↑ −40                                    ↑ −32          ↑ fine
```

I blocchi sono una **catena percorsa dalla fine verso l'inizio**. Il primo piede sta a **78
byte dalla fine**:

```
 piede:  [ id u16 LE ][ dimensione u32 LE ]        ← 6 byte
 dati:   i `dimensione` byte immediatamente PRIMA del piede
 prossimo piede: 6 byte prima dell'inizio dei dati
```

In pseudo-codice:

```
piede = lunghezza − 78
ripeti:
    id, dimensione = leggi 6 byte a `piede`
    inizio = piede − dimensione
    se id == quello cercato → i dati stanno in [inizio, piede)
    piede = inizio − 6
```

### 2.1 I blocchi trovati su una foto vera

| id | dimensione | contenuto |
|---|---|---|
| `0x0101` | 2839 byte | metadati — comincia col numero di serie della camera |
| `0x0200` | 1.179.688 byte | anteprima compressa |
| **`0x0300`** | **8000 byte** | **traccia inerziale: 400 campioni da 20 byte** |
| `0x0900` | 240 byte | esposizione: cinque voci da 48 byte, con marca in millisecondi |
| `0x2a01` | 63 byte | parametri di scatto |

---

## 3. Il blocco inerziale `0x0300`

Un campione è **20 byte**:

```
 0   8   marca temporale u64 LE, in MICROSECONDI
 8   2   accelerometro asse 0   int16 «binario con scostamento»
10   2   accelerometro asse 1
12   2   accelerometro asse 2
14   2   giroscopio asse 0
16   2   giroscopio asse 1
18   2   giroscopio asse 2
```

**Binario con scostamento**: il valore vero è il numero senza segno **meno 32768**. Non è un
complemento a due; leggerlo come tale dà valori assurdi che sembrano rumore.

| | |
|---|---|
| frequenza | **1 kHz** — fra un campione e il successivo passa un millesimo di secondo |
| campioni per foto | ~400 (0,4 s attorno allo scatto) |
| scala accelerometro | **1 g = 4096 LSB** |

### 3.1 Gli assi

Ricavati dai dati, non da una specifica:

| asse | direzione |
|---|---|
| 0 | **ottico** — avanti, dove guarda l'obiettivo |
| 1 | trasversale |
| 2 | **alto** della camera |

A camera in bolla la gravità sta tutta sull'asse 2, **negativa**.

### 3.2 Da gravità ad assetto

Si mediano tutti i campioni buoni (accelerometro soltanto: a camera ferma misura la gravità,
cioè indica dov'è il basso), poi:

```
modulo = √(avanti² + trasversale² + alto²)
beccheggio = asin( avanti / modulo )        positivo verso l'alto
rollio     = atan2( trasversale, −alto )
```

**Il pan non c'è e non ci può essere**: la gravità è simmetrica attorno alla verticale, quindi
di quanto la camera sia girata in orizzontale un accelerometro non lo sa. Nessuna lettura di
questo blocco potrà mai dare il pan.

### 3.3 Quanto è buona la misura

Sulle nove foto della spiaggia:

- il **modulo** del vettore è costante entro il **4 per mille** — se lo fosse meno, vorrebbe
  dire che la camera si stava muovendo e la misura non sarebbe gravità;
- tre scatti alla **stessa inclinazione comandata** ma a pan diversi danno lo stesso beccheggio
  entro **tre centesimi di grado**.

Sotto un centinaio di campioni mediati la misura vale poco, e l'app la scarta
(`MIN_SAMPLES = 50` come soglia dura).

---

## 4. Il ripiego: riconoscere la traccia dal comportamento

Se la catena non si legge — formato diverso, file troncato — l'app torna a **cercare** la
traccia dentro gli ultimi 48 KB: scorre gli scostamenti e tiene il primo dove una marca
temporale a 64 bit cresce di **un millesimo di secondo ogni venti byte per almeno cinquanta
campioni di fila**.

Cinquanta passi giusti di fila non capitano per caso, e nessun'altra cosa dentro quella coda —
l'anteprima compressa, i parametri di scatto — si comporta così.

Perché allora leggere la catena, se la ricerca funziona? Per due ragioni: si va dritti al
blocco giusto invece di frugare, e soprattutto **non si può sbagliare blocco** — dentro un
JPEG compresso da 1,2 MB, prima o poi, otto byte che sembrano una marca temporale che avanza si
trovano.

---

## 5. Quello che nella coda **non** c'è

Cercato e non trovato, con il metodo descritto in
[VICOLI-CIECHI.md](VICOLI-CIECHI.md#1-il-pan-dentro-i-file):

- il **pan** o qualunque angolo orizzontale, in nessuna forma;
- la posizione comandata al gimbal;
- un identificativo di sequenza della panoramica.

Il giroscopio c'è (tre assi per campione) ma su una **foto** copre 0,4 s attorno allo scatto:
non è una traccia continua fra uno scatto e l'altro, quindi non si può integrare per ricavare
di quanto la camera ha girato. Su un **video** `.insv` la traccia potrebbe essere continua —
è l'unica strada rimasta per misurare il pan dai file, e non è ancora stata provata.

---

*© Persoft di Patassini Alessandro — licenza MIT*
