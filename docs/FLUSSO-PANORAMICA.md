# Dal gimbal alla panoramica: il flusso di lavoro

Come una panoramica attraversa l'app, dallo scatto al file salvato — e dove l'utente decide
cosa. La matematica dell'unione sta in [UNIONE-PANORAMICHE.md](UNIONE-PANORAMICHE.md); qui c'è
la **catena**.

---

## 1. I quattro momenti

```
SCATTO                       il gimbal percorre la griglia, la camera scatta,
                             ogni foto porta i suoi angoli nei tag
   │
   ├─ JOB                    la panoramica diventa un lavoro in attesa: resta lì
   │                         finché non la si guarda. Niente parte da solo.
   │
   ├─ PREPARAZIONE           allineamento a bassa risoluzione (1100 px di lato lungo),
   │                         una volta sola, e il risultato si salva su file
   │
   ├─ PUNTO DI FUGA          si sceglie a mano dove guarda la panoramica, con che
   │                         proiezione e fin dove arriva la tela — vedendo
   │                         la deformazione dal vivo su un'anteprima ridotta
   │                         → «Salva» chiude qui. L'unione si lancia quando si vuole.
   │
   └─ UNIONE                 dagli originali a piena risoluzione, con il punto di
                             fuga scelto. Anche tutte in fila, se i job sono più d'uno.
```

Il principio dietro la divisione: **le due parti costose sono separate dalle due decisioni.**
L'allineamento si fa una volta e si conserva; la scelta del punto di vista è istantanea perché
lavora sul conservato; l'unione a piena risoluzione parte quando c'è tempo, non quando c'è
voglia di decidere.

---

## 2. Il job

Un job è una panoramica scattata e non ancora unita. Vive in una scheda a tutta larghezza,
scorrevole, alta al più mezzo schermo, e ogni riga porta la **miniatura dello scatto centrale**
— perché di una panoramica si riconosce la scena, non il nome dei file.

- **toccare la miniatura** = entrare in preparazione e da lì nell'editor del punto di fuga;
- bordo verde = il punto di fuga è già stato scelto e salvato;
- **«Uniscile tutte (N)»** compare da due job in su.

Quando un job si cancella o si conclude, la sua cartella di preparazione se ne va con lui
(`PanoPrepStore.discard`), e all'avvio le cartelle orfane vengono raccolte.

---

## 3. La preparazione, e perché esiste un file

Entrando in un job la prima volta, l'app allinea le foto **a 1100 px di lato lungo**: abbastanza
per misurare gli angoli, abbastanza poco da metterci pochi secondi.

Il risultato si scrive in `filesDir/anteprime/<idJob>/`:

| file | contenuto |
|---|---|
| `piano.json` | per ogni foto: etichetta, pan, tilt, rollio, scala focale, guadagno, vignettatura — più campo visivo e proiezione |
| miniature JPEG | una per foto, lato lungo **720 px**, qualità 88 |
| `anteprima.jpg` | la panoramica ridotta come è stata salvata |

Rientrare nel job **non riallinea niente**: si riapre il piano e si dipinge. Era il difetto
segnalato dall'utente — *«ogni volta che rientro in un job passa sempre per l'allineamento»* — e
la soluzione è questo file.

La stessa interfaccia (`PanoramaPreview`) è implementata da due classi: quella che dipinge dai
fotogrammi ancora aperti durante l'unione, e quella che dipinge dalla cache. Non possono
divergere perché la funzione che applica il punto di vista è **una sola**, condivisa fra
l'anteprima e l'unione vera.

---

## 4. Dove si infila la fase intermedia

Dentro `stitch(...)`, subito dopo che le posizioni corrette sono note e **prima** che i
fotogrammi di lavoro vengano chiusi. È l'unico punto valido: gli angoli ci sono già, la tela
non è ancora dimensionata, e le copie ridotte sono ancora in mano.

L'anteprima restituisce una risposta:

- **`Stitch(view)`** — hai deciso, procedi con l'unione da questo punto di vista;
- **`StopHere(view)`** — hai salvato, l'unione non si fa adesso (e il piano è su disco).

---

## 5. L'editor del punto di fuga

Regola dell'interfaccia, dettata dall'uso: **i comandi che modificano la foto stanno attaccati
alla foto.**

```
┌───────────────────────────────┐
│                               │
│         la panoramica         │  ← la foto prende tutto lo spazio che avanza
│              ✛                │     mirino sempre visibile al centro
│                               │     un tocco porta lì il punto di fuga
├───────────────────────────────┤
│  rollio   ──────●──────       │  ← subito sotto la foto
│  [auto] [sfera] [cil] [merc]  │  ← tutte e quattro le proiezioni, con icona
│  [tutta] [55°] [65°] [75°]    │  ← fin dove arriva la tela
│  188° × 142° · 74 px/grado    │
│  [Com'era] [Decidi tu] [Salva] [Cuci] │
└───────────────────────────────┘
```

**Il trascinamento** sposta il punto di fuga dal vivo. Durante il trascinamento l'anteprima si
ridipinge a **360 px** di lato lungo invece di 720: al dito serve la reattività, non i dettagli.
I ridisegni **non si annullano mai a vicenda** — se ne arriva uno mentre l'altro è in corso, il
secondo si segna «da rifare» e parte appena il primo finisce. Annullare a metà è quello che
produce lo scatto.

**Il tocco** porta il mirino dove sta il dito, ma solo se il dito è **dentro l'immagine
disegnata** (non nelle bande nere ai lati) e non oltre **40° in orizzontale, 25° in verticale**:
un tocco è un'indicazione, non un salto.

Le quattro proiezioni hanno un'icona disegnata a mano — arco con punto (automatica), globo
(sferica), cilindro (cilindrica), griglia (Mercatore) — perché quattro parole in fila su uno
schermo di telefono non si leggono, e quattro forme sì.

---

## 6. Le foto che stanno già sul telefono

Dal menu del **telefono stilizzato** (in alto a destra, prima voce: *«Panoramica da foto del
telefono»*) si sceglie un gruppo di foto qualunque e le si unisce.

Se non hanno i tag della panoramica, l'app **non si arrende agli angoli inventati**: legge la
coda Insta360 di ogni file e ne ricava beccheggio e rollio veri. Poi

- spezza le **file** dove il beccheggio salta più del **35%** del campo orizzontale;
- distribuisce il **pan** a passi uguali dentro ogni fila — è l'unica cosa che nessuno ha
  scritto da nessuna parte, quindi l'unica ipotizzata;
- passa inclinazione e rollio misurati all'allineamento, esattamente come per le panoramiche
  pianificate.

Il log lo dichiara: `UNIONE MANUALE · LETTA DALLE FOTO`. Se la coda non c'è (foto non
Insta360), si torna alla fila a passi uguali e alla ricerca larga.

---

## 7. Il verdetto

Alla fine di ogni unione il log scrive un **verdetto**: sovrapposizione minima, campo visivo
misurato contro dichiarato, inclinazioni prese dalla gravità, giunzioni chiesto-contro-fatto,
proiezione scelta e perché, deformazione in cima, punti tenuti e a quale soglia, tempi per
fase.

È la fonte da leggere, non il log intero. È scritto per essere letto **sul telefono**, in
italiano, con i conti in chiaro: chi legge deve poter capire se una panoramica è venuta storta
e di chi è la colpa senza aprire un debugger.

---

*© Persoft di Patassini Alessandro — licenza MIT*
