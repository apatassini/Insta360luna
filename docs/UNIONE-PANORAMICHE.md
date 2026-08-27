# Come l'app unisce le panoramiche

Documento di riferimento sulle **logiche di calcolo** dello stitcher: la geometria, le stime,
le decisioni. Serve a ragionarci sopra, quindi riporta le formule vere e i numeri veri, con il
perché di ogni scelta e — dove esistono — i limiti che non si possono superare.

Codice di riferimento:
`app/src/main/java/it/persoft/lunaultra/stitch/` — `PanoramaGeometry.kt` (geometria pura,
testata), `PanoramaStitcher.kt` (il motore), `StitchTuning.kt` (le manopole),
`MultibandBlender.kt` (la fusione).

---

## 1. L'idea di fondo, e in cosa questo differisce da uno stitcher generico

Uno stitcher generico non sa dove sono state scattate le foto: deve dedurlo cercando
corrispondenze fra tutte le coppie. È la parte fragile, quella che fallisce davanti a un muro
uniforme o a un motivo ripetuto.

Qui **la posizione di ogni scatto è nota in gradi**, perché è l'app stessa ad aver mosso il
gimbal, e il campo visivo dell'obiettivo è noto. Si parte quindi già allineati, e alle
immagini resta solo il **residuo** da correggere.

Questo vale per le panoramiche pianificate. Per le foto scelte a mano gli angoli non ci sono:
si assume una fila a passi uguali e si cerca largo. È una situazione più difficile, ed è il
motivo per cui alcune protezioni descritte più avanti esistono.

La catena completa:

```
foto sorgenti
   │
   ├─ 1. lettura e copia di lavoro ridotta
   ├─ 2. livella (solo senza angoli veri): inclinazione della camera dall'orizzonte
   ├─ 3. tela provvisoria
   ├─ 4. ALLINEAMENTO
   │      ├─ registrazione a piramide  → spostamento globale grossolano
   │      ├─ punti di controllo         → verifica e rifinitura
   │      ├─ bundle adjustment          → spostamento + rollio + focale
   │      └─ deformazione locale        → il residuo che la rotazione non può togliere
   ├─ 5. tela definitiva (sulle posizioni corrette)
   ├─ 6. fotometria: guadagni + vignettatura
   ├─ 7. CUCITURA, un fotogramma per volta
   │      ├─ riconoscimento: quali pixel della tela copre
   │      ├─ taglio: dove passa la giunzione
   │      ├─ fusione multibanda: solo sulla sovrapposizione
   │      └─ pittura diretta: tutto il resto
   └─ 8. ritaglio dei bordi neri, salvataggio, EXIF ereditato
```

---

## 2. Geometria

### 2.1 L'obiettivo come proiezione

Il campo visivo nasce dai dati di catalogo: **20 mm equivalenti × zoom**, riferiti alla
diagonale full-frame (43,266 mm). Da lì, conservando la diagonale nel rapporto scelto:

```
altezzaEq  = 43,266 / √(ratio² + 1)
larghezzaEq = altezzaEq · ratio
FOV_h = 2 · atan( larghezzaEq / (2 · focaleEq) )
```

A 1× in 4:3 dà **81,7° × 66,0°**. La focale in pixel discende dal campo:

```
f = (W/2) / tan(FOV_h / 2)
FOV_v = 2 · atan2(H/2, f)          ← il campo verticale non si assume, si deriva
```

La proiezione prospettica di un raggio già ruotato nel sistema della camera:

```
px = W/2 + f · x/z
py = H/2 − f · y/z        (y in su, py in giù)
```

Un raggio con `z ≤ 0` è dietro la camera e non ha nessun pixel: va scartato, non specchiato.

> **Nota misurata, e poi corretta.** La stima dai punti di controllo dava 81,6° contro gli
> 81,7° dichiarati, e sembrava chiudere il caso. Non lo chiudeva: **focale e scala del gimbal
> sono degeneri**, una lente più stretta e un gimbal più veloce danno la stessa
> sovrapposizione, quindi quella misura confermava soltanto sé stessa. Contro la gravità — che
> è un righello esterno — il campo vero è **77,7°**: la camera ritaglia il 7% del fotogramma, e
> il gimbal si muove ×1,235 di quanto gli si chiede. Vedi
> [CAMERA-E-OTTICA.md §3](CAMERA-E-OTTICA.md#3-lottica-quello-che-dice-il-catalogo-e-quello-che-fa-la-lente).

### 2.2 Dove sta un fotogramma

```
FramePlacement(
    panDegrees, tiltDegrees,                 ← dal piano della panoramica
    panCorrectionDegrees, tiltCorrectionDegrees,  ← dall'allineamento
    rollDegrees,                             ← rotazione attorno all'asse ottico
    focalScale,                              ← correzione moltiplicativa della focale
)
effectivePan  = panDegrees + panCorrectionDegrees
effectiveTilt = tiltDegrees + tiltCorrectionDegrees
```

### 2.3 Proiezione inversa: dal pixel della tela al pixel della foto

È il cuore. Ogni pixel della tela finita corrisponde a una direzione (longitudine, latitudine);
quella direzione si ruota nel sistema del fotogramma e diventa un punto sull'immagine.

```
lon = longitudine − effectivePan     (radianti)
lat = latitudine                     (radianti)

versore:   x = cos(lat)·sin(lon)
           y = sin(lat)
           z = cos(lat)·cos(lon)

tilt inverso:   yr = y·cosT − z·sinT
                zr = y·sinT + z·cosT

rollio inverso: xr  =  x·cosR + yr·sinR
                yrr = −x·sinR + yr·cosR

pixel:     lens.project(xr · focalScale, yrr · focalScale, zr)
```

**L'ordine delle rotazioni non è indifferente.** Pan e tilt di un gimbal non commutano:
invertirli produce un errore che cresce con la latitudine — invisibile all'orizzonte,
grossolano a sessanta gradi, cioè esattamente dove una panoramica alta va a finire.

`frameToWorld` è l'inverso esatto e serve ai punti di controllo: un dettaglio trovato in un
fotogramma diventa una direzione, e la stessa direzione si cerca nell'altro.

### 2.4 La tela: tre proiezioni

Le colonne sono sempre longitudine lineare. La differenza sta nelle righe. `pixelsPerDegree` è
la scala verticale **all'orizzonte**; `R = pixelsPerDegree · 180/π` è il raggio in pixel.

| proiezione | riga ← latitudine | limite | quando |
|---|---|---|---|
| **Equirettangolare** | `y = ppd · lat` | ±90° | sempre per le sferiche: è l'unica che arriva ai poli e l'unica che un visualizzatore 360° legge |
| **Cilindrica** | `y = R · tan(lat)` | ±75° | di serie per le normali: vicino all'orizzonte le altezze restano naturali |
| **Mercatore** | `y = R · ln(tan(45° + lat/2))` | ±80° | conforme: conserva le forme in piccolo |

Conseguenze che valgono per tutte e tre, e che servono a giudicare un risultato:

- **L'orizzonte è un cerchio massimo, quindi esce come una riga dritta.**
- **Una linea dritta vicina alla camera — il bordo di un molo — è quella che si incurva.**
- Le verticali del mondo restano verticali.

Se esce il contrario — il mare a conca e il molo dritto — non è la proiezione: è il
riferimento verticale sbagliato (§3).

L'altezza in pixel **non è più «gradi × densità»** fuori dall'equirettangolare, quindi il
tetto sul lato lungo si calcola a densità unitaria (l'altezza resta lineare nella densità).
E la riga su cui cade una latitudine si chiede alla tela (`rowOf`), non si calcola a mano.

### 2.5 Quanto grande esce la panoramica

La densità non è un numero fisso: si ricava dal budget di memoria vero.

```
wH = FOV_h + 2·3°        (margine del ritaglio)
wV = FOV_v + 2·3°
budget = heap · 45% − (pixel di un fotogramma · 4) − 32 MB

costo per densità² =  wH·wV · 1                    ← pesi del fotogramma, 1 byte/px
                    + areaPeggioreDiFusione · 3    ← fusione a scala ridotta
                    + areaTela / 4                 ← mappa dei possessori, 1 byte ogni 4 px

densità = √( budget / costoPerDensità² )
```

L'area di fusione peggiore si stima dagli angoli pianificati allargati di 12°, perché
l'allineamento li sposterà. Il risultato è limitato dal richiesto (la densità dei pixel veri
degli originali) e da un tetto assoluto di 24 000 px sul lato lungo.

> **Bug corretto, con sintomo riconoscibile.** La tela veniva dimensionata sugli angoli
> *nominali*, poi l'allineamento spostava i fotogrammi e quelli spostati sporgevano oltre il
> bordo — dove non c'è tela non si dipinge. Con correzioni di un grado non si notava; con una
> correzione di +25,6° se ne perdeva un quarto. Ora la tela si ricostruisce **dopo**
> l'allineamento.

---

## 3. La livella: com'era messa la camera

Vale **solo quando gli angoli veri non ci sono** (foto scelte a mano). Una panoramica
pianificata porta i suoi tilt dal gimbal e non viene toccata.

Il problema: senza angoli si assume che la camera fosse in bolla. Se invece guardava in su di
dodici gradi, una riga orizzontale della foto viene piazzata come se fosse all'altezza
dell'occhio, e in proiezione diventa **un arco con il colmo al centro del fotogramma** — uno
per foto. Da qui il mare a conca.

La misura è possibile perché l'orizzonte, dove c'è, è il gradiente orizzontale più esteso
della foto: chiaro sopra, scuro sotto, alla stessa altezza per quasi tutte le colonne.

```
per ogni colonna (una ogni 4), nella fascia 25%…85% dell'altezza:
    riga del massimo salto verso il buio, misurato fra bande distanti 3 righe
    si tiene se il salto ≥ 18 livelli
serve che almeno il 55% delle colonne lo trovi
e che il loro scarto interquartile sia ≤ 6% dell'altezza
                                        ↓
        inclinazione = atan( (rigaOrizzonte − H/2) / f )      positiva = guardava in su
```

Se le colonne sono poche o in disaccordo — un interno, un muro, nessun orizzonte — non si
inventa niente. Il limite di credibilità è ±40°.

**Di serie il riferimento verticale resta il centro esatto delle foto**, che è la scelta
prevedibile e non sposta l'inquadratura; l'orizzonte viene comunque *misurato* e riportato nel
verdetto. La livella si accende quando si vuole raddrizzare.

> Misura reale su tre foto: **+11,9°, +13,7°, +15,1°**. Il difetto previsto — 3,2° di
> gonfiore per fotogramma più 3,2° di deriva — corrisponde ai 130 px di oscillazione
> dell'orizzonte misurati sulla panoramica prodotta.

---

## 4. Allineamento

### 4.1 Registrazione a piramide: lo spostamento grossolano

Si confronta ogni fotogramma con i vicini già sistemati (al massimo 2: quello di fianco e
quello della fila accanto, così le file di una griglia si richiudono fra loro).

I punti campione nascono su una griglia del fotogramma **fermo** (passo 20 px) e diventano
direzioni nel mondo. A ogni livello della piramide di luminanza si cerca, in una finestra
attorno al risultato del livello precedente, lo spostamento che massimizza la **correlazione
normalizzata** su quei punti:

```
ZNCC = (n·ΣFM − ΣF·ΣM) / √[(n·ΣFF − ΣF²)(n·ΣMM − ΣM²)]
```

La correlazione normalizzata è indifferente all'esposizione: è il motivo per cui si usa questa
e non una differenza di luminanza.

Programma di ricerca (livello, raggio pan, raggio tilt, passo):

| livello | raggio pan | raggio tilt | passo |
|---|---|---|---|
| 3 (⅛) | 60° largo / 8° stretto | 20° / 8° | 1,2° / 0,8° |
| 2 (¼) | 1,2° | 1,2° | 0,3° |
| 1 (½) | 0,4° | 0,4° | 0,12° |
| 0 (piena) | 0,12° | 0,12° | 0,05° |

Dalla nebbia al dettaglio: al livello sfocato i motivi ripetuti — le foglie di una palma a
ventaglio — sono una massa unica e non ingannano; al livello fine resta solo la rifinitura. È
l'ordine con cui lavorano i programmi seri, ed è il contrario di una ricerca locale a piena
risoluzione, che sulla palma si aggancia alla foglia sbagliata con grande convinzione.

Sotto una correlazione finale di 0,30 non è un allineamento, è un caso: si scarta.

### 4.2 I punti di controllo: la giuria

Nel fotogramma fermo si scelgono i dettagli con carattere **in entrambe le direzioni**
(`min(|dx|, |dy|) ≥ 5`, il criterio che distingue un angolo da un bordo), uno per cella di una
griglia che si stringe finché i candidati non bastano (da 40 px fino a 14).

Ognuno si ritrova nell'altro fotogramma per correlazione piena, in un raggio di **0,7°**
attorno a dove il piazzamento globale lo prevede — più piccolo del passo di una foglia di
palma, così la foglia sbagliata non è più raggiungibile. Il ritaglio confrontato è 13×13.

**La soglia di qualità è un punto di partenza, non un ultimatum.** Ogni corrispondenza porta
il proprio punteggio; se a quella qualità i superstiti non bastano (obiettivo 40, minimo 12),
la soglia scende a scalini di 0,05 fino al pavimento di 0,60, e la nota dice dove si è
fermata.

> **Perché è indispensabile.** Con la qualità chiesta al 100% sono sopravvissuti *0 punti su
> 75* e *1 su 493*. Senza punti si spengono insieme: bundle adjustment, rollio, focale,
> deformazione locale, fotometria e persino la misura del campo visivo — e tutte le ricette
> di prova diventano identiche fra loro. Una soglia che può azzerare in silenzio metà del
> motore non va offerta senza rete.

**I punti fanno anche da giuria.** La piramide, davanti a un cielo di nuvole e a un mare
increspato — due superfici che si somigliano ovunque — sa agganciarsi al posto sbagliato con
grande convinzione: si è vista proporre +22,7° con il 91% di concordanza. Oltre **6°** di
correzione la proposta viene messa alla prova contro l'ipotesi «non spostare niente», e vince
quella che i punti confermano (serve che l'alternativa ne trovi almeno 1,5× tanti).

### 4.3 Bundle adjustment: spostamento, rollio, focale insieme

Ogni punto porta il suo residuo `(rLon, rLat)` e la sua posizione `(u, v)` in gradi dal centro
del fotogramma mobile. Tre effetti si distinguono per come muovono i punti:

- uno **spostamento** muove tutti i punti dello stesso vettore;
- una **rotazione attorno all'asse ottico** li muove tangenzialmente, `(−v, +u)·R`;
- un **errore di focale** li muove radialmente, `(−u, −v)·d`.

Da cui il sistema lineare, due equazioni per punto:

```
rLon = a − v·R − u·d
rLat = b + u·R − v·d
```

Quattro incognite `(a, b, R, d)`, risolte ai minimi quadrati con eliminazione di Gauss, più
una passata di potatura dei fuori posto (oltre 2,5× il residuo mediano). È la linearizzazione
per piccoli angoli del Levenberg–Marquardt di Hugin e Autopano.

Limiti: rollio oltre **4°** vuol dire punti cattivi e si torna alla sola traslazione; la
focale invece **si limita, non si annulla** — di serie ±20%, tetto ±35%.

> **Bug corretto.** Bastava che la focale vera fosse più del 4% diversa dalla specifica perché
> l'*intera* correzione — traslazione e rollio compresi — venisse scartata in silenzio. Una
> specifica ottimistica non è un buon motivo per rinunciare all'allineamento.

### 4.4 Deformazione locale: la parallasse

**Quello che nessuna rotazione può togliere.** Il gimbal non ruota attorno al centro ottico
dell'obiettivo ma attorno a un asse che gli sta qualche centimetro dietro: fra uno scatto e
l'altro la camera **trasla**, e ciò che è vicino scorre più di ciò che è lontano. Se allinei
il muro in fondo, la tenda davanti resta fuori posto, e viceversa.

Quello che si può fare è quello che fa Autopano: prendere i punti rimasti fuori posto **dopo**
l'allineamento globale e trasformarli in un campo di spostamento morbido.

```
per ogni nodo di una griglia 13×9 sul fotogramma:
    spostamento = Σ w·d / Σ w      con  w = exp( −dist² / 2σ² )
    σ = latoLungo / divisore
    limitato a ±(latoLungo · frazione)
```

| forza | divisore (σ) | limite |
|---|---|---|
| leggera | 4 | 1,5% |
| media | 6 | 2,5% |
| forte | 10 | 4,0% |

«Forte» non vuol dire solo *di più*: vuol dire più **locale**. Una parete vista di scorcio da
una foto e quasi frontale dall'altra ha bisogno che il dettaglio venga **allargato
progressivamente**, non spostato in blocco — e solo un campo che varia in fretta lo sa
esprimere. Servono però punti fitti: un campo stretto senza punti che lo reggano resta fermo.

Dove non ci sono punti il campo va a zero: non inventa niente. Servono almeno 24 punti.

---

## 5. Fotometria

Due scatti dello stesso posto a esposizione automatica non hanno la stessa luminosità, e
l'obiettivo scurisce ai bordi. Senza correzione la sfumatura non nasconde niente, perché non
è il confine a vedersi ma il salto di tono ai suoi due lati.

Guadagni e vignettatura si stimano **insieme**, dai punti di controllo: lo stesso punto del
mondo visto da due foto a raggi diversi dal centro è l'informazione che li separa.

```
modello di vignettatura:  V(r) = 1 + a·r² + b·r⁴     r² normalizzato sulla semidiagonale²

per ogni coppia di luminanze (Lf sul fisso, Lm sul mobile):
    ln(Lm/Lf) = ln Gf − ln Gm + a·(r²m − r²f) + b·(r⁴m − r⁴f)
```

Sistema lineare nelle incognite `ln G` (uno per fotogramma, il primo fissato a riferimento) e
`a, b` condivisi. Servono almeno 40 campioni, scartando i punti vicini alla saturazione
(luminanza fuori da 12…242), dove il rapporto non dice più niente di vero.

Applicazione per pixel:

```
fattore(x, y) = G / max(V(r), 0,4)
```

Limiti: guadagni in 0,5…2,0, coefficienti di vignettatura in ±0,8. Se i campioni non bastano
si ripiega sulla vecchia catena delle mediane.

---

## 6. Cucitura

### 6.1 Dove passa la giunzione

Il taglio **non** cade a metà strada. La parallasse non si può togliere, ma si può **scegliere
dove tagliare**: se il taglio passa dove le due foto già mostrano la stessa cosa — un muro
uniforme, un bordo che in entrambe cade nello stesso posto — del disaccordo non resta traccia
visibile; se passa in mezzo a un oggetto vicino, quell'oggetto si sdoppia o si tronca.

```
costo di una cella = |ΔR| + |ΔG| + |ΔB| fra tela e nuovo, dove entrambi ci sono
                     10 000 dove uno dei due manca (proibito)

programmazione dinamica lungo la banda:
    ogni passo sceglie una posizione, spostandosi al più di una cella dal precedente
    si accumula in doppia precisione (su bande lunghe la virgola semplice perde le differenze)
    si ritorna indietro dal minimo dell'ultimo passo
```

Il verso del taglio segue la banda: fra due foto affiancate la sovrapposizione è una striscia
alta e stretta, quindi il taglio scende.

**Da che parte sta il nuovo** si ricava da una covarianza: quanto il vantaggio del nuovo sulla
tela — la differenza dei pesi di sfumatura — cresce spostandosi lungo la banda. La sua forza
dice anche quanto fidarsi: quando la sovrapposizione è su **due** lati (l'ultimo scatto di un
giro che si richiude, o una griglia dove il fotogramma tocca il vicino di fianco e quello
sopra) il nuovo domina in mezzo e la tela alle estremità, la covarianza si annulla, e vuol
dire che un taglio solo non può separarli: lì si torna alla mediana geometrica.

### 6.2 Fusione multibanda, solo dove serve

Della fusione multibanda serve solo la **correzione** — la differenza fra la fusione vera e il
montaggio a taglio netto — ed è per natura a bassa frequenza, perché le bande fini cambiano
mano in un pixel.

Quindi: si costruiscono vecchio, nuovo e maschera a passo `s`, si fonde lì, si sottrae il
montaggio netto ridotto, e la differenza si riapplica riga per riga al montaggio netto a piena
risoluzione. Con `s = 1` è la fusione esatta; con `s` maggiore la memoria cala col quadrato e
il dettaglio fine resta pieno, perché viene dal montaggio, non dalla piramide. La scala si
sceglie sul budget di 64 MB: `s ∈ {1, 2, 4, 8}`.

La fusione lavora **solo sulla sovrapposizione** più 96 px di contesto. Su una panoramica
reale: finestra 7406×6708, fusione su 2403×5975 — il 29%.

Tre errori corretti qui, con sintomi riconoscibili:

| sintomo | causa |
|---|---|
| **trattini orizzontali** | il passaggio finale leggeva la mappa dei possessori mentre la scriveva: nella coppia di righe, la dispari trovava il peso appena scritto dalla pari (stessa cella a mezza risoluzione) e la decisione si alternava. Ora un'**istantanea** presa prima di ogni scrittura |
| **puntini** | i pesi confrontati erano byte quantizzati: ai pareggi la scelta cambiava pixel per pixel. Ora virgola mobile, letta con interpolazione |
| **barre scure** | una sola correzione multibanda applicata anche ai pixel la cui decisione a piena risoluzione era l'opposta. Ora **due** correzioni, rispetto al nuovo e rispetto al vecchio, e ogni pixel usa quella della propria sorgente |

### 6.3 Il resto del fotogramma

Fuori dalla sovrapposizione ogni pixel nuovo cade su tela vuota per costruzione: niente da
fondere, pittura diretta riga per riga.

Il peso di un pixel nella fusione segue la distanza dal bordo più vicino, normalizzata sulla
metà del lato corto, al quadrato (una rampa lineare a volte lascia intravedere una banda).

---

## 7. Prestazioni: dove va il tempo, e perché

Storia misurata su una panoramica reale — 3 foto da 37 Mpx, tela 17245×6708 (115 Mpx),
risultato 15783×5688:

| | inizio | −allocazioni | −trigonometria | −chiamate native | −trig. allineamento |
|---|---|---|---|---|---|
| **cucitura** | 205 s | 119 s | 58 s | **24 s** | 24 s |
| riconoscimento | — | 32 s | 4 s | 4 s | 4 s |
| fusione | — | 17 s | 14 s | 7 s | 8 s |
| pittura | — | 65 s | 36 s | 9 s | 9 s |
| apertura originali | — | 2 s | 2 s | 2 s | 1 s |
| **allineamento** | 27 s | 27 s | 19 s | 20 s | **3 s** |
| **unione intera** | ~210 s | | | 46 s | **30 s** |

Sette volte più veloce, e il costo è ora ripartito quasi equamente fra riconoscimento,
fusione e pittura — cioè non c'è più un collo di bottiglia singolo da attaccare.

Le tre lezioni, in ordine di resa:

**1. Le allocazioni nei cicli per-pixel.** `intArrayOf(16, 8, 0)` scritto dentro un ciclo che
gira una volta per pixel alloca un array a ogni giro; con `.withIndex()` si aggiunge un oggetto
indice per canale. Su cento milioni di pixel sono centinaia di milioni di oggetti, e il
netturbino che li raccoglie **ferma tutti i fili** — motivo per cui il contatore dei core
segnava uno anche con otto thread al lavoro. Non era il parallelismo che mancava: veniva
continuamente interrotto.

**2. La trigonometria che non dipende dal pixel.** La proiezione inversa calcola otto seni e
coseni, e nessuno degli otto dipende davvero dal pixel: inclinazione e rollio sono costanti per
fotogramma, la latitudine cambia solo di riga, la longitudine solo di colonna — e le colonne
sono le stesse per tutte le righe, quindi si tabulano una volta. Inoltre restituire un oggetto
per pixel è un'altra allocazione per pixel. `FrameProjector` fa lo stesso conto con **zero
trigonometria e zero allocazioni**, e un test lo confronta con la versione leggibile su cinque
piazzamenti — con correzioni, rollio e focale — pretendendo lo stesso pixel a un centesimo.

Nell'allineamento vale la stessa cosa in forma più forte: le direzioni campione **non cambiano
mai**, quindi seno e coseno si tabulano con loro, e al candidato resta da togliere la propria
longitudine con la formula di sottrazione `sin(a−b) = sin a·cos b − cos a·sin b` — due
moltiplicazioni al posto di due funzioni.

**3. Le chiamate native per pixel.** Leggere dal Bitmap a piena risoluzione costava una
chiamata nativa per pixel. La via d'uscita viene dalla geometria: la tela ha all'incirca la
stessa densità dell'originale — 84,4 px/grado contro 86 — quindi colonne vicine sulla tela
cadono su colonne vicine nella foto, e la scansione procede ordinata. Un blocco di 64×16 pixel
in heap (4 KB per filo) fa sì che quasi ogni lettura lo trovi già in mano: si attraversa il
confine una volta ogni sessanta pixel.

**Il parallelismo** distribuisce le righe a coppie allineate alla mappa dei possessori (che
vive a mezza risoluzione): così ogni lavoratore ha le sue righe di mappa in esclusiva e non
serve nessun lucchetto. Un proiettore e un blocco di lettura per riga, di proprietà del filo
che li usa.

> **Metodo.** Due volte ho supposto dove fosse il collo di bottiglia e due volte mi sarei
> sbagliato: la decodifica JPEG, che misurata è risultata 2 s su 205; e il «lavoro doppio» del
> riconoscimento, che dopo le altre correzioni vale 4 s e non merita più di essere toccato. I
> cronometri per fase costano poco e hanno risparmiato due refactoring inutili.

**Resta seriale una cosa sola:** aprire gli originali, perché il decoder JPEG di Android non si
spartisce. Sono 2 s su file da 37 Mpx — e non è «per forza», come si è creduto a lungo: un file
non si apre in parallelo con sé stesso, ma **due file diversi sì**. Da lì viene l'apertura
anticipata del prossimo originale mentre si dipinge quello corrente
([PROVE-E-MISURE.md §5.3](PROVE-E-MISURE.md#53-lapertura-degli-originali-che-non-era-seriale-per-forza)).

### 7.1 La scheda grafica

Con il collo di bottiglia singolo sparito, il passo successivo non è togliere altro lavoro alla
CPU: è darne una parte a chi lo fa per mestiere. Riconoscimento e pittura sono per-pixel,
indipendenti riga per riga, e la pittura in più fa a ogni pixel un campionamento bilineare —
che sulla CPU costa quattro letture più l'interpolazione e sull'unità di campionamento di una
GPU **non costa niente**, la fa l'hardware.

Il frammento (`GpuStitchRenderer`) è la traduzione riga per riga di `FrameProjector` +
`featherWeight` + `factorAt`. Restituisce un intero già in formato Bitmap: OpenGL rilegge i byte
in ordine R,G,B,A, quindi lo shader scrive le componenti al contrario e l'intero little-endian
che ne esce è già `0xAARRGGBB`. L'alfa non è trasparenza, è **il peso della sfumatura**.

Tre vincoli decidono la forma:

- **La tela non entra in una texture.** 17245 px superano gli 8192 dichiarati da molti telefoni,
  quindi si disegna a fasce e ogni fascia si rilegge in un vettore di 8 MB, riusato.
- **Il contesto grafico appartiene a un filo.** Le corutine cambiano filo alle sospensioni: c'è
  un filo solo, suo, acceso con la tela e spento con lei.
- **Niente può far fallire un'unione.** Ogni chiamata è dentro una `runCatching`; un fallimento
  spegne la GPU per il resto dell'unione e la CPU riprende da dove era. Non c'è un caso in cui
  la panoramica esce peggio: esce uguale, più lentamente, e il log dice perché.

**L'autocontrollo** è la parte che rende la cosa accettabile. Uno shader sbagliato non lancia
niente e non si ferma: disegna una panoramica storta, indistinguibile a occhio da un problema di
allineamento. Quindi prima di scrivere un solo pixel si disegna un riquadro di 96×96 nel centro
del fotogramma e lo si ricalcola con le funzioni CPU vere, confrontando ~200 campioni:

| grandezza | tolleranza | perché |
|---|---|---|
| peso della sfumatura | Δ medio ≤ 3 su 255 | è pura geometria: due strade che proiettano uguale danno pesi uguali |
| colore | Δ medio ≤ 6 su 255 | in più c'è l'interpolazione in virgola fissa dell'hardware: qualche livello su un bordo netto è fisiologico, decine no |

Gli scarti misurati finiscono **sempre** nel log, che passi o non passi: è quello che serve per
correggere lo shader senza avere il telefono in mano.

Le due manopole si accendono una alla volta (Impostazioni → Unione foto → Scheda grafica), e
ogni unione riporta per ogni foto «ricognizione su GPU/CPU, pittura su GPU/CPU» più la riga
**«Di cui sulla scheda grafica: disegno … · riporto sulla tela … · caricamento delle
sorgenti …»**. Quella riga è la sola cosa che dice quale sia la mossa successiva: se cala il
disegno ma non il riporto, il collo di bottiglia si è solo spostato sulla copia CPU delle fasce,
e la risposta è metterle in pipeline (disegnare la fascia *n+1* mentre si riporta la *n*) invece
di alternarle.

La pittura in GPU ha una condizione in più: l'originale deve entrare in una texture. A ×2,2 sui
3200 px di lavoro il lato lungo è ~7040, sotto il limite di 8192; se un giorno non lo fosse,
quel fotogramma si dipinge in CPU e il log lo scrive.

Misurato su Adreno 750, tre foto da 37 Mpx, una manopola per volta:

| | CPU | su GPU | di cui disegno | di cui riporto |
|---|---|---|---|---|
| riconoscimento | 4 s | **1 s** | 0,5 s | 0,8 s |
| pittura | 9 s | **1 s** | 0,4 s | 1,0 s |

Autocontrollo: peso Δ0,0 su 255, colore Δ0,1 su 255 su 196 campioni su 196. Le due strade
danno lo stesso pixel a un livello di differenza — cioè l'arrotondamento dell'interpolazione,
niente altro.

Il riporto sulla tela (CPU) è ora **il doppio** del disegno: la pipeline fra le due — disegnare
la fascia *n+1* mentre si riporta la *n* — recupererebbe al massimo il minore dei due, mezzo
secondo. Non è più lì che sta il tempo: con entrambe accese il pezzo grosso della cucitura
diventa la **fusione multibanda**, 7-8 s.

### 7.2 La memoria, che è un vincolo diverso dal tempo

Con nove foto l'unione moriva di `OutOfMemoryError` mentre allocava 30.771.216 byte — cioè
3200 × 2404 × 4, **un vettore di lavoro di una foto**. Il tetto è la heap Java (512 MB con
`largeHeap`); i Bitmap non ci stanno dentro, vivono in memoria nativa.

Per foto si tenevano:

| | prima | dopo |
|---|---|---|
| `pixels` (IntArray) | 30,8 MB | **0** — la luminanza si costruisce riga per riga dal Bitmap |
| `gray` | 30,8 MB (float) | **7,7 MB** (byte) |
| piramide (liv. 1-3) | 10,1 MB | **2,5 MB** |
| **totale per foto** | **71,7 MB** | **10,2 MB** |
| **nove foto** | **645 MB** ✗ | **92 MB** ✓ |

Tre correzioni, nessuna delle quali tocca la risoluzione del risultato:

**La luminanza in byte.** Sta fra 0 e 255: un float ne usa quattro e non aggiunge
un'informazione che nell'immagine di partenza non c'è. In più questi vettori si leggono in
lungo e in largo, quindi meno byte significa anche meno letture dalla RAM — la registrazione a
piramide è limitata dalla banda, non dal calcolo.

**Il vettore dei pixel non si costruisce.** Serviva solo a fabbricare la luminanza, e per
quello basta un buffer di una riga riusato.

**Liberare all'ultimo uso.** La regola vecchia era «più lontano di due campi visivi dal
fotogramma corrente», e su una griglia non scattava mai — in una griglia nessuna foto è mai
davvero lontana. La domanda giusta è «qualcuno più avanti lo userà ancora come vicino?», e si
risponde con la stessa funzione che sceglie i vicini. Sbagliare previsione non fa danno: la
luminanza è pigra e si rifà dal Bitmap.

**La proiezione su più file.** Con tre file si arriva a ~78° dall'orizzonte. La cilindrica si
ferma a 75°: la tela diventerebbe 36.000 px di altezza invece di 13.000, **e le file esterne
verrebbero schiacciate sull'ultima riga**. Oltre i 65° si scende da sola a Mercatore, oltre i
72° a equirettangolare, scrivendolo nel log.

**Quello che resta.** La tela di una 3×3 è 17.217 × 13.166 = 227 Mpx = **907 MB di Bitmap
nativo**, e quei 227 Mpx sono onesti: nove foto da 37 Mpx con il 30% di sovrapposizione fanno
~230 Mpx di contenuto unico. Non c'è niente da tagliare — c'è da smettere di tenerla tutta in
RAM: tela su file mappato in memoria e JPEG scritto a bande di 16 righe (Android non ha un
encoder incrementale pubblico, va scritto). Memoria O(larghezza × 16) per qualunque
dimensione. Fino ad allora `chooseDensity` conta anche i byte della tela, con un tetto
dichiaratamente provvisorio di due volte la heap.

---

## 8. Le manopole e le ricette

Tutte in `StitchTuning`, regolabili da Impostazioni → «Unione foto».

| manopola | di serie | effetto |
|---|---|---|
| proiezione | Cilindrica | la sferica ignora la scelta |
| livella l'orizzonte | accesa (ricetta C) | riferimento verticale dall'orizzonte invece che dal centro foto |
| taglio sul minimo disaccordo | acceso | giunzione dove le foto concordano |
| deformazione locale | spenta (ricetta C) | assorbe la parallasse |
| forza della deformazione | media | quanto è locale il campo |
| libertà sulla focale | 20% | quanto la focale misurata può discostarsi |
| qualità dei punti | 80% | punto di partenza, scende da sola se non bastano |
| quantità dei punti | normale | ×1, ×2, ×4 |
| sfumatura multibanda | accesa | spenta = taglio netto, diagnostico |
| ricognizione su GPU | spenta | pesi e geometria sulla scheda, con rete di sicurezza CPU |
| pittura su GPU | spenta | proiezione, campionamento e fotometria sulla scheda, idem |

Le **ricette A…F** sono sei configurazioni che differiscono ognuna dalla precedente per **una
cosa sola**, così la lettera in cui un difetto sparisce dice da sola chi era il colpevole.
Esistono a piena risoluzione (applicabili con una pastiglia) e in versione di prova a 1024 px,
dove la modalità test le esegue tutte in fila sulla stessa terna di foto salvandole in galleria
come `Panorama_TEST_<lettera>`.

```
A  com'era: camera in bolla, taglio a metà strada, nessuna deformazione
B  + livella l'orizzonte
C  + taglio sul minimo disaccordo e focale libera     ← di serie
D  + deformazione locale media
E  + deformazione locale forte
F  come E ma a taglio netto: mostra nuda la giunzione
```

---

## 9. Limiti che restano

- **La parallasse non si elimina.** Il gimbal non ruota attorno al centro ottico. Il taglio
  intelligente e la deformazione locale la *nascondono*; non la tolgono. Oggetti molto vicini
  con sfondo lontano restano il caso difficile.
- **Una sovrapposizione povera non si recupera.** Sotto ~12° non c'è materiale in comune né
  per allinearsi né per nascondere una giunzione: il verdetto lo dichiara, ma il limite è lo
  scatto, non il programma.
- **Senza orizzonte visibile la livella non misura.** Resta il valore a mano.
- **Chiudere l'app dalle app recenti interrompe l'unione.** Il servizio in primo piano protegge
  dal congelamento quando si cambia applicazione, ma non dalla distruzione dell'Activity: il
  lavoro vive nel ViewModel. Renderlo immune richiederebbe spostare la cucitura dentro il
  servizio.
- **Il verdetto è la fonte da leggere**, non il log completo: sovrapposizione minima, punti
  tenuti e a quale soglia, campo visivo misurato contro dichiarato, deformazione applicata,
  con gli avvisi in rosso quando qualcosa non ha funzionato.

---

*© Persoft di Patassini Alessandro — licenza MIT*
