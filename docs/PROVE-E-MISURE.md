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

| fase | CPU (partenza) | con GPU su ricognizione e pittura | fusione su GPU | oggi (26 ago) |
|---|---|---|---|---|
| **allineamento** | 27 | 27 | 36 | **36** |
| **cucitura** | **47** | 31 | 28 | **31** |
| — riconoscimento | 4 | 2 | 2 | 2 |
| — **fusione** | **31** | 15 | 13 | **14** |
| — pittura | 9 | 2 | 2 | 2 |
| — possessori | (dentro la fusione) | — | 1 | 1 |
| — apertura originali | 6 | 6 | 6 | 6 |

E dentro le due fasi grosse, dal log del 26 agosto:

```
Dentro l'allineamento:  dettagli riconosciuti 5,3 s · ricerca a piramide 3,4 s
                        · punti di controllo 27,4 s
Dentro la fusione:      griglia ridotta 2,7 s · piramidi 3,3 s
                        · riporto a piena risoluzione 8,1 s
Di cui sulla GPU:       disegno 2,5 s · riporto sulla tela 2,2 s · caricamento sorgenti 0,5 s
```

**I punti di controllo sono il 76% dell'allineamento.** È il collo di bottiglia attuale, ed è
l'oggetto dell'ultima modifica (§5).

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
| `92667c2` | punti di controllo: NCC a passata singola + scansione a passo 2 | **da verificare** |

### 5.1 L'ultima modifica, e cosa deve dire il prossimo log

I punti di controllo facevano due sprechi:

1. **Leggevano il riquadro 13×13 due volte** — una per la media, una per la correlazione.
   Sostituito con l'identità del riquadro a media nulla: siccome `Σp = 0`, allora
   `Σ(t−t̄)·p = Σt·p`, e `Σ(t−t̄)² = Σt² − (Σt)²/n`. Una passata sola.
2. **Provavano tutte e 2401 le posizioni** della finestra di ricerca. Ora la scansione va a
   **passo 2** e poi rifinisce ±2 attorno al migliore: circa un quarto delle posizioni.

Previsione: ~7 volte meno letture, da 27,4 s a **~4 s**.

**Il rischio da guardare nel prossimo log** è che la riga

```
Punti di controllo: 121…513 per giunzione, soglia 90%
```

non crolli. Se i numeri restano su quell'ordine, la scansione grossolana non ha perso nessun
massimo; se scendono, il passo 2 salta sopra picchi stretti e va rimesso a 1 con la sola
passata singola come guadagno.

### 5.2 I prossimi bersagli, in ordine

1. **punti di controllo** — 27,4 s → in verifica
2. **riporto a piena risoluzione** nella fusione — 8,1 s
3. **apertura degli originali** — 6 s, e resta seriale per forza: il decoder JPEG di Android
   non si spartisce

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
