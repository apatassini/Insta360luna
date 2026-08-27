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

**Due dita ingrandiscono**, fino a quattro volte. Serve a controllare, non a modificare: con la
panoramica intera in trecento pixel due foto attaccate bene e due attaccate male si somigliano.
Il punto di fuga non cambia di una virgola — cambia solo quanto da vicino lo si guarda. E
ingrandendo l'anteprima **si ridisegna più fitta** invece di essere gonfiata: guardare da vicino
dei pixel ingranditi direbbe solo che sono pixel ingranditi. Con l'immagine ingrandita anche il
dito diventa fine, perché lo stesso pixel di schermo vale meno gradi. La riga dei numeri dice
«ingrandita ×2,4» e toccarla torna a uno.

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

Qui non c'è nessun angolo scritto da nessuna parte, e **l'ordine con cui le foto arrivano non è
un'informazione**: può essere l'ordine dei tocchi, quello alfabetico, quello di scansione della
cartella. Anche fosse l'ordine di scatto direbbe la successione, non la forma — su più file
nessun ordine lineare dice che la sesta foto sta *sopra* la prima invece che a destra.

Quindi il posto **si trova guardando le foto**:

1. ogni foto si legge in piccolo (600 px di lato lungo, in bianco e nero) e se ne prendono i
   **dettagli riconoscibili** — ognuno diventa una firma di 256 bit che dipende solo da com'è
   fatto, non da dove sta;
2. **ogni coppia** si abbina, e ogni abbinamento dice di quanti gradi una foto sta più in là
   dell'altra. Gli abbinamenti giusti concordano tutti sullo stesso numero, quelli sbagliati
   cadono ognuno per conto suo: si vota, e la maggioranza è la giunzione;
3. le giunzioni fanno un **grafo**, si tiene l'albero di peso massimo — quelle di cui ci si fida
   di più — e le posizioni si propagano da una foto sola, la più legata a tutte.

L'inclinazione, quando la coda Insta360 c'è, non si stima nemmeno qui: viene dalla gravità, che
è l'unica misura assoluta di tutto il giro. Resta da trovare solo il pan.

**Non tutte devono starci per forza — e quelle di troppo non si buttano.** Le foto legate fra
loro formano gruppi, e **ogni gruppo è un lavoro**. Sei foto scelte di cui quattro sono una
spazzata e due un altro momento diventano *due* panoramiche, una da quattro e una da due, e chi
le ha scelte decide quale unire. Le foto che non si attaccano a nessun'altra restano fuori (una
foto da sola non è una panoramica) e il log dice quali.

Il riconoscimento si fa **subito, all'import**: costa tre decimi di secondo su sei foto, e il
momento giusto è quando l'utente sta ancora guardando — non un'ora dopo, quando lancia l'unione
e scopre che mancava metà panoramica.

Alla fine c'è la **controprova**: l'albero si regge su n−1 giunzioni, tutte le altre non hanno
votato e sono testimoni indipendenti. Se le posizioni sono giuste, ognuna deve ritrovarsi
d'accordo. Il verdetto scrive quante concordano — e quando non concordano lo dice, invece di
consegnare una panoramica sbagliata con l'aria di essere giusta.

Il log lo dichiara: `UNIONE MANUALE · POSTO TROVATO DALLE FOTO`, con le posizioni trovate e i
tempi. Se nessuna coppia si riconosce si torna alla fila a passi uguali, e anche quello sta
scritto.

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
