# Risultati delle prove

Il registro delle misure fatte sull'hardware vero: tempi, memoria, autocontrolli, qualità
dell'allineamento. Serve a due cose — sapere **dove sta il tempo adesso** (che è l'unica cosa
che dice quale sia la mossa successiva) e non rifare due volte una prova già fatta.

Metodo, stabilito presto e mai più abbandonato: **si misura, non si suppone.** Due volte ho
supposto dove fosse il collo di bottiglia e due volte mi sarei sbagliato (vedi
[VICOLI-CIECHI.md](VICOLI-CIECHI.md)). I cronometri per fase costano poco e hanno risparmiato
più di un refactoring inutile.

---

## 1. Le due panoramiche di riferimento

| | **tre foto** | **nove foto** |
|---|---|---|
| scatti | 3 × 37 Mpx | 9 × 37 Mpx |
| copertura | una fila | 188° × 142° |
| tela | 17.245 × 6.708 (115 Mpx) | 13.896 × 10.526 (146 Mpx, 557 MB) |
| risultato | 15.783 × 5.688 | 13.635 × 9.856 |
| telefono | Adreno 750 | Adreno 750 |

La prima ha guidato l'ottimizzazione CPU (documentata in
[UNIONE-PANORAMICHE.md §7](UNIONE-PANORAMICHE.md#7-prestazioni-dove-va-il-tempo-e-perché):
da **210 s a 30 s**, sette volte). La seconda guida tutto quello che è venuto dopo — la
scheda grafica e la fusione.

---

## 2. La nove foto, tappa per tappa

Tutti i tempi in secondi, dallo stesso telefono, sulla stessa panoramica.

| fase | CPU (partenza) | con GPU su ricognizione e pittura | fusione su GPU | punti di controllo veloci |
|---|---|---|---|---|
| **allineamento** | 27 | 27 | 36 | **16** |
| **cucitura** | **47** | 31 | 28 | **25** |
| — riconoscimento | 4 | 2 | 2 | 2 |
| — **fusione** | **31** | 15 | 13 | **11** |
| — pittura | 9 | 2 | 2 | 2 |
| — possessori | (dentro la fusione) | — | 1 | 1 |
| — apertura originali | 6 | 6 | 6 | 5 |
| **unione intera** | | | 67 | **41** |

E dentro le due fasi grosse, prima e dopo la modifica ai punti di controllo:

```
                                     17:26                18:18
Dentro l'allineamento
  dettagli riconosciuti               5,3 s                4,0 s
  ricerca a piramide                  3,4 s                2,8 s
  punti di controllo                 27,4 s                9,5 s   ← −65%
Dentro la fusione
  griglia ridotta                     2,7 s                2,1 s
  piramidi                            3,3 s                2,5 s
  riporto a piena risoluzione         8,1 s                6,4 s
Di cui sulla scheda grafica
  disegno / riporto / caricamento   2,5 · 2,2 · 0,5      2,2 · 1,8 · 0,6
```

I punti di controllo erano **il 76% dell'allineamento**; adesso sono il 59% di un allineamento
che dura meno della metà. (La fusione cala in proporzione senza che nessuno l'abbia toccata:
la stessa unione, con il telefono più libero — heap 8 MB invece di 11, sistema libero 2842 MB
invece di 2681. È la variabilità da tenere presente leggendo differenze sotto il 20%.)

### 2.1 La fusione, cosa l'ha fatta scendere da 31 a 13

Tre interventi, in ordine di resa:

**Le fette di riga.** La fusione leggeva sette griglie con interpolazione bilineare, **una
lettura per pixel per griglia**: un insieme di lavoro da ~40 MB che non entra in nessuna cache.
Sostituito con `rowSlice` + `lineAt`, che estraggono la riga una volta sola: ~30 KB. Le griglie
sono lisce, quindi l'informazione è la stessa.

**Le piramidi in parallelo.** La maschera si prepara una volta sola, poi i tre canali colore
salgono e scendono la piramide contemporaneamente (`coroutineScope { launch(Dispatchers.Default) }`).
Da 5,9 s a 3,0.

**La mappa dei possessori una volta per foto.** Era aggiornata dentro la fusione *e* dentro
tutte e due le vie di pittura, cioè più volte sugli stessi pixel. Ora si scrive una volta
sola, alla fine di `pasteFrame`: **1 s** invece che diluita ovunque.

### 2.2 La fusione sulla scheda grafica: il falso passo

La prima versione della fusione in GPU **non guadagnò niente**: la fusione restava 15 s e il
riporto sulla tela *peggiorava*, da 2,1 a 3,4 s. La causa non era lo shader: era un passaggio
CPU intermedio che rileggeva e riscriveva tutti i 155 milioni di pixel della fascia solo per
estrarne l'alfa.

Correzione: **lo shader emette alfa opaca**, così la fascia riletta va dritta nel Bitmap senza
nessun passaggio in mezzo, e la mappa dei possessori si aggiorna dai pesi già in mano. Fatto
questo, la fusione è scesa a 13 s.

Lezione, che vale oltre questo caso: *spostare un calcolo sulla GPU non serve a niente se resta
in mezzo un passaggio che tocca comunque tutti i pixel.*

---

## 3. Gli autocontrolli

Nessuna delle vie in GPU è creduta sulla parola. Uno shader sbagliato non lancia niente e non
si ferma: disegna una panoramica storta, indistinguibile a occhio da un problema di
allineamento. Quindi ogni fotogramma confronta GPU e CPU **prima** di scrivere.

### 3.1 Ricognizione e pittura — riquadro di 96×96 al centro, ~200 campioni

```
Autocontrollo Foto 5: peso Δ0,0 (max 0) · colore Δ0,2 (max 1) su 196/196 campioni
```

Nove foto su nove, sempre. Tolleranze: peso Δ medio ≤ 3 su 255 (è pura geometria, deve tornare
quasi esatto), colore Δ medio ≤ 6 su 255 (c'è in più l'interpolazione in virgola fissa
dell'hardware). Misurato: **peso esatto, colore un livello**. Cioè l'arrotondamento, niente
altro.

### 3.2 Fusione — una riga sonda ricalcolata in CPU e rimessa a posto

```
Autocontrollo fusione Foto 6: colore Δmax 1 · 0 pixel oltre 2 su 2228
Autocontrollo fusione Foto 2: colore Δmax 0 · 0 pixel oltre 2 su 6189
Autocontrollo fusione Foto 7: colore Δmax 2 · 0 pixel oltre 2 su 6096
```

Tolleranza **2 livelli** (`GPU_BLEND_TOLERANCE`), perché la correzione multibanda viaggia in
mezza precisione, che ha un passo di un ottavo di livello, e l'arrotondamento finale può cadere
da una parte o dall'altra. Si accetta **un pixel fuori ogni cento**
(`GPU_BLEND_OFFENDER_SHARE`): sul confine del taglio i pesi sono identici fino all'ultima cifra
e le due strade possono cadere da parti diverse. **Misurato: zero fuori tolleranza, sempre, su
tutte e nove le foto.**

Se un autocontrollo fallisce, la GPU si spegne per il resto dell'unione e la CPU riprende da
dove era. Non c'è un caso in cui la panoramica esce peggio: esce uguale, più lentamente, e il
log dice perché.

---

## 4. Qualità dell'allineamento, nove foto

```
Sovrapposizione minima 19°
Campo visivo: dichiarato 77,7°, misurato 77,6° — la specifica regge
Punti di controllo: 121…513 per giunzione, soglia 90%
Deformazione locale su 8 fotogrammi, fino a 15 px
Correzione massima dell'allineamento: 6,92°
Fotometria: guadagni 0,80 … 1,32 · vignettatura a=−0,053 b=0,126 (1886 campioni)
```

Per fotogramma, i due estremi:

| | punti | sopra il 90% | corretto | rollio | focale | concordanza | deformazione |
|---|---|---|---|---|---|---|---|
| Foto 8 (il migliore) | 939 | 513 | −6,09° / −0,39° | +0,08° | ×1,001 | **85%** | 2,4 px |
| Foto 7 (il peggiore) | 2748 | 218 | −5,18° / −0,07° | +0,24° | ×1,006 | **38%** | **15,4 px** |
| Foto 2 (il caso limite) | 3611 | 121 | −0,03° / −0,01° | −0,04° | ×0,999 | **0%** | 5,7 px |

Foto 2 è istruttivo: *«la ricerca grossolana non ha trovato niente di affidabile: parte dagli
angoli del gimbal e rifinisce con i soli punti di controllo»*, poi *«al primo giro non c'era
niente da misurare, ripreso con i vicini allineati nel frattempo»*. Concordanza 0% e
correzione ~0: il fotogramma è rimasto dove il gimbal diceva, e non è un fallimento — è il
comportamento voluto quando l'immagine non ha niente da dire.

**Le focali misurate stanno tutte fra ×0,999 e ×1,006.** Quando la focale di partenza è giusta,
il bundle adjustment non la muove: è la conferma indipendente che il 77,6° misurato è il numero
vero.

---

## 5. Cronologia delle modifiche di velocità

| build | intervento | effetto misurato |
|---|---|---|
| — | allocazioni fuori dai cicli per-pixel | cucitura 205 → 119 s (tre foto) |
| — | trigonometria tabulata, `FrameProjector` | 119 → 58 s; allineamento 20 → 3 s |
| — | blocchi 64×16 invece di chiamate native per pixel | 58 → 24 s |
| — | luminanza in byte, niente vettore pixel, liberare all'ultimo uso | 645 → 92 MB su nove foto |
| — | ricognizione e pittura su GPU | riconoscimento 4→1 s, pittura 9→1 s (tre foto) |
| — | fette di riga nella fusione | griglia ridotta a 2,4–2,7 s |
| — | piramidi a tre canali in parallelo | 5,9 → 3,0 s |
| — | possessori una volta per foto | 1 s, e la fusione smette di riscriverli |
| — | fusione su GPU con alfa opaca | fusione 15 → 13 s, riporto 3,4 → 2,1 s |
| `92667c2` | punti di controllo: NCC a passata singola + scansione a passo 2 | **27,4 → 9,5 s**, unione 67 → 41 s |
| — | apertura del prossimo originale mentre si dipinge questo | da verificare |
| — | copie di lavoro scalate dal decodificatore in un passaggio solo | da verificare |
| — | foto scelte dal telefono copiate quattro per volta | da verificare |

### 5.1 L'ultima modifica, e cosa deve dire il prossimo log

I punti di controllo facevano due sprechi:

1. **Leggevano il riquadro 13×13 due volte** — una per la media, una per la correlazione.
   Sostituito con l'identità del riquadro a media nulla: siccome `Σp = 0`, allora
   `Σ(t−t̄)·p = Σt·p`, e `Σ(t−t̄)² = Σt² − (Σt)²/n`. Una passata sola.
2. **Provavano tutte e 2401 le posizioni** della finestra di ricerca. Ora la scansione va a
   **passo 2** e poi rifinisce ±2 attorno al migliore: circa un quarto delle posizioni.

Previsione: ~7 volte meno letture, da 27,4 s a ~4 s. **Misurato: 9,5 s** — la previsione era
ottimista di un fattore due (la rifinitura ±2 attorno a ogni massimo costa più di quanto
stimato), ma la direzione era giusta.

**Il rischio era che la scansione grossolana saltasse i picchi stretti.** Non è successo:

```
prima   Punti di controllo: 121…513 per giunzione, soglia 90%
dopo    Punti di controllo: 116…510 per giunzione, soglia 90%
```

Gli stessi punti, trovati leggendo un settimo dei pixel. Le correzioni per fotogramma
coincidono entro tre centesimi di grado, e Foto 7 — il caso peggiore — è perfino migliorata:
concordanza dal 38% all'80%, deformazione locale da 15,4 a 12,6 px.

### 5.3 L'apertura degli originali, che non era seriale per forza

Per mesi qui c'è stato scritto che i 5-6 secondi di apertura degli originali erano
incomprimibili, perché **il decoder JPEG di Android non si spartisce fra più fili**. È vero, e
non c'entra: un file non si apre in parallelo con sé stesso, ma **due file diversi sì**.

Dipingere un fotogramma dura 2,7 s; aprirne uno ne dura circa 0,55. Quindi mentre si dipinge il
fotogramma *n* si apre quello *n+1*, e quando tocca a lui il suo Bitmap è già in memoria:
l'attesa scende a zero per tutti tranne il primo.

Il costo è **un originale in più in memoria nativa** — 147 MB su foto da 37 Mpx. Non si prende
per scontato: prima si chiede a `MemoryBudget.spareBytes` quanto resta libero dopo la tela, e
si apre in anticipo solo se ci sta tre volte (quello dipinto, quello che si apre, e la copia
che la rotazione EXIF fa nascere per un istante). Sul telefono di prova restano ~1550 MB
liberi contro 442 MB richiesti; su un telefono stretto la condizione è falsa e tutto torna
come prima, senza dire niente a nessuno.

Il log dice quanti ne ha aperti in anticipo: `apertura originali 1 s (8 aperti in anticipo)`.

### 5.8 La prima misura della resa: due core su otto

Il 27 agosto, nove foto, con il contatore acceso:

```
Tempi: allineamento 17 s · cucitura 19 s
Resa dei core (su 8): allineamento 1,9 · cucitura 2,0
Dentro la scheda: calcolo 0,3 s · rilettura e attesa 1,6 s · 2% del tempo di cucitura
```

**Due core su otto.** Avevo scritto qui sopra, ragionando, che «con `parallelRows` e i candidati
spartiti la CPU è probabilmente già satura». Era falso, e la misura l'ha detto al primo colpo:
tre quarti della macchina sta a guardare per tutta l'unione. È esattamente il motivo per cui il
contatore esiste — l'intuizione su queste cose sbaglia, e sbaglia in grande.

**E la scheda non è il collo di bottiglia**: calcola tre decimi di secondo su diciannove di
cucitura. Il tempo che le si attribuiva — «disegno 2,6 s» — è quasi tutto attesa e travaso di
pixel, non calcolo. Uno shader più furbo non servirebbe a niente; semmai servirebbe darle **più
lavoro per volta**.

Il primo sospettato per i due core è il modo in cui la tela viene letta e riscritta: due
chiamate native per riga, larghe quanto tutta la tela — tredicimila pixel per prenderne
cinquemila, quattromila volte per fotogramma, con otto lavoratori che si accalcano sullo stesso
Bitmap. Ora quel passaggio legge e riscrive **a fasce**, sul solo pezzo di tela che il
fotogramma tocca. Per sapere se era davvero quello, ogni sotto-fase ha il suo contatore di core.

### 5.5 La resa dei core, che è la domanda giusta prima di parallelizzare

Parallelizzare «ancora un po'» non si decide dai tempi: una fase che dura dieci secondi può
tenere otto core occupati — e allora non c'è niente da spartire — oppure uno solo, e allora ce
ne sono sette fermi. I due casi durano uguale e chiedono cose opposte.

Il numero che li separa c'è, e costa niente: il **tempo di calcolo di tutti i fili** diviso il
tempo passato. `Process.getElapsedCpuTime()` lo dà per l'intero processo; la differenza fra
inizio e fine di una fase, divisa per la sua durata, è quanti core quella fase ha tenuto
occupati in media. Il verdetto ora lo scrive:

```
Resa dei core (su 8): allineamento 5,2 · cucitura 3,1
```

Da lì la decisione è meccanica. Vicino al numero dei core: la fase usa già tutta la macchina, e
l'unico modo di accorciarla è **fare meno lavoro**, non spartirlo meglio — è la strada che ha
portato i punti di controllo da 27,4 s a 9,5. Molto sotto: c'è qualcuno che aspetta, e va
trovato chi.

### 5.7 E la scheda grafica? Quello che si può misurare e quello che no

Per la CPU la domanda «quanti core sto occupando» ha una risposta esatta. Per la scheda **no**,
e non per pigrizia: OpenGL ES non dice quanti processori abbia una GPU né quanti ne siano
occupati. Non c'è una chiamata che lo chieda. Si sa il nome — `Adreno (TM) 750` — e da lì si
può andare a leggere una scheda tecnica, ma è una consultazione, non una misura, e non dice
niente su quanto la stiamo usando.

Quello che si può misurare sono due tempi, e sono i due che servono:

| | cosa è | come si misura |
|---|---|---|
| **calcolo** | quanto la scheda dichiara di aver passato a eseguire i nostri disegni | `GL_EXT_disjoint_timer_query`, un cronometro che sta sulla scheda; c'è quasi sempre su Adreno e Mali, ma è un'estensione |
| **rilettura e attesa** | quanto il nostro filo passa dentro `glReadPixels`: aspettare che finisca, più travasare i pixel | tempo di parete attorno alla chiamata, sempre disponibile |

La separazione è la cosa utile. Le chiamate di disegno tornano subito — la scheda lavora per
conto suo — quindi il «disegno 2,2 s» del verdetto **non** è il tempo del disegno: è disegno più
attesa. Se il calcolo dichiarato è mezzo secondo e l'attesa è un secondo e mezzo, il collo di
bottiglia non è lo shader: è il travaso, e si cura rileggendo in modo asincrono (con un buffer
di pixel) invece di riscrivere il codice del disegno. Se invece il calcolo è quasi tutto, allora
sì che si guarda lo shader.

Il verdetto scrive entrambe le righe, e quando il cronometro non c'è lo dichiara invece di
stampare uno zero che sembrerebbe una misura.

### 5.6 Le due cose che aspettavano

**La scheda e i core, nella pittura.** La scheda disegnava una fascia mentre gli otto core
stavano a guardare; poi i core la riportavano sulla tela mentre la scheda stava a guardare. Nel
log si leggeva in chiaro — «disegno 2,2 s · riporto 1,8 s» — quattro secondi per due lavori che
non si toccano. Con due vettori di fascia invece di uno, il disegno della prossima si sovrappone
al riporto della precedente e il tempo diventa il **maggiore** dei due invece della somma.
Costa otto megabyte di heap e una regola: prima di disegnare sopra un vettore si aspetta che il
suo riporto sia finito, così sulla tela non scrive mai più di uno alla volta.

**Il decoder e tutto il resto**, già risolto in §5.3: il prossimo originale si apre mentre si
dipinge questo.

### 5.4 I prossimi bersagli, in ordine

1. **punti di controllo** — 9,5 s, ancora il pezzo più grosso dell'allineamento
2. **riporto a piena risoluzione** nella fusione — 6,4 s
3. **dettagli riconosciuti** — 4,0 s

E una misura che finora non c'era: **la lettura delle copie di lavoro**. Nove foto da 37 Mpx
lette e ridotte a 3200 px non sono gratis, ma nessun cronometro le contava — stavano dentro i
quattro secondi che nel conto delle fasi non tornavano. Adesso il verdetto scrive «lettura
delle copie di lavoro N,N s», ed è la prossima cosa da guardare.

Nel frattempo quella lettura fa metà del lavoro di prima: il decodificatore riduce solo per
potenze di due, quindi da 7008 px si scendeva a 3504 e poi si riscalava a 3200 — un Bitmap
grande più una copia. Chiedendo la riduzione con `inDensity`/`inTargetDensity` il
decodificatore consegna direttamente la misura giusta: un Bitmap invece di due, e una passata
in meno su ogni pixel. Se un decodificatore ignorasse le densità, il vecchio ridimensionamento
è ancora lì come rete di sicurezza.

---

## 6. Memoria

| | prima | dopo |
|---|---|---|
| per foto (`pixels`, `gray`, piramide) | 71,7 MB | **10,2 MB** |
| nove foto | 645 MB ✗ (`OutOfMemoryError`) | **92 MB** ✓ |

Dal log del 26 agosto, a unione in corso:

```
Memoria: heap 11/512 MB · nativa 32 MB · sistema libero 2681 MB (soglia 216 MB)
         → tela fino a 1367 MB
Tela 13896×10526 a 74,0 px/grado (557 MB in memoria, heap 512 MB)
```

La tela vive in memoria **nativa**, non in heap Java: è per questo che 557 MB convivono con
una heap da 512. Il limite vero è la memoria di sistema libera, e `chooseDensity` la conta.

---

*© Persoft di Patassini Alessandro — licenza MIT*
