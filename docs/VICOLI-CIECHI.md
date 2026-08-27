# Vicoli ciechi

Le strade provate che non portavano da nessuna parte, e — dove c'è — quella che ha funzionato
al loro posto. Un vicolo cieco documentato vale quanto una funzione: impedisce di ripercorrerlo.

Ordinati per argomento, non per data.

---

## 1. Il pan dentro i file

**Provato.** Se la coda dei file porta accelerometro e giroscopio, allora forse porta anche il
pan, magari in un blocco che non ho riconosciuto. Ho cercato in due modi: percorrendo la catena
dei blocchi e guardando cosa contiene ognuno, e — più brutalmente — scorrendo tutti gli
scostamenti della coda alla ricerca di una sequenza di valori che correlasse con gli angoli
comandati alla panoramica.

**Perché non funziona.** Il pan **non c'è**, e c'è una ragione fisica per cui non ci può
essere: l'unico sensore assoluto in quella coda è l'accelerometro, e la gravità è simmetrica
attorno alla verticale. Di quanto la camera sia girata in orizzontale un accelerometro non lo
sa, non lo può sapere, e nessuna quantità di ricerca nei byte lo farà comparire.

Cercare per correlazione, per giunta, è pericoloso: dentro un'anteprima JPEG da 1,2 MB si trova
sempre *qualcosa* che assomiglia a una serie crescente, e ci si convince di aver trovato un
formato.

**Cosa si fa invece.** Il pan si misura **a posteriori**, dalle foto: giunzioni orizzontali
della panoramica, o chiusura del giro contro i fine corsa. Vedi
[GIMBAL-E-TARATURA.md §5](GIMBAL-E-TARATURA.md#5-le-due-strade-per-misurare-la-scala).

**Quello che resta da provare**, l'unico filo non tirato: su un **video `.insv`** la traccia
giroscopica potrebbe essere continua invece che 0,4 s attorno allo scatto. Se lo fosse, si
integra e si ottiene il pan. Basterebbero dieci secondi di ripresa mentre il gimbal gira.

---

## 2. La posizione del gimbal dalla camera

**Provato.** Chiedere alla camera dove si trova il gimbal: scansione dei codici di messaggio,
lettura della notifica **8302** (che arriva davvero, e in corrispondenza dei movimenti).

**Perché non funziona.** Il contenuto della 8302 non è stato decodificato, e nessuno dei
progetti di reverse engineering pubblici lo ha fatto: la documentazione del buco nel protocollo
di `Ripwords/insta360-luna-ultra-desktop` è esattamente il documento che dice che quei numeri
non sono pubblici.

**Cosa si fa invece.** Navigazione a stima, e la scala si corregge misurando le panoramiche.
Tutto l'impianto della taratura esiste per questo.

---

## 3. La lente inventata

**Provato.** Misurando le foto risultava un campo visivo più stretto del dichiarato, e la
prima reazione è stata scrivere in codice la focale che tornava:

```kotlin
const val MEASURED_EQUIVALENT_FOCAL_MM = 21.73f   // ← sbagliato
```

**Perché non funziona.** Le lenti sono **standard e pubblicate**: 20 mm equivalenti a 1×, 60 a
3×. Non è un numero che si aggiusta, è un fatto della camera. Scriverne un altro significa
nascondere l'errore vero da qualche altra parte — e infatti l'errore vero era il gimbal, che si
muove ×1,235 di quanto gli si chiede.

Detto dall'utente, che aveva ragione: *«le lenti sono standard, puoi andarti a vedere le
specifiche. Non è che me le invento io.»*

**Cosa si fa invece.** La costante è tornata a 20 mm, e il campo visivo **si misura a ogni
unione** contro la gravità, senza mai essere scritto a mano. Il verdetto riporta entrambi i
numeri, dichiarato e misurato.

---

## 4. Correggere focale e rotazione insieme

**Provato.** Lasciare che il bundle adjustment sistemasse sia il campo visivo sia gli angoli
del gimbal, confrontando le foto fra loro.

**Perché non funziona.** **Sono degeneri.** Una lente più stretta del vero e un gimbal che si
muove più del comandato producono *esattamente* la stessa sovrapposizione. Nessuna misura fatta
confrontando foto con foto può separarli: il sistema ha infinite soluzioni, tutte con lo stesso
residuo, e l'ottimizzatore si ferma su quella che capita.

**Cosa si fa invece.** Serve un **righello esterno**, cioè una misura che non venga dalle
immagini. È la gravità. Con l'inclinazione vera in mano il campo visivo esce per differenza, e
il gimbal resta l'unico imputato. Poi il bundle adjustment può muovere la focale del ±20% — e
misurato, non la muove: sta fra ×0,999 e ×1,006.

---

## 5. Tarare su qualunque panoramica

**Provato.** Ogni unione riscriveva la scala del gimbal nel profilo. Sembrava ovvio: più misure,
meglio è.

**Perché non funziona.** Il gimbal **non è lineare** — i rapporti misurati ballano di 0,180.
Una panoramica di quattro foto verticali, con una sola giunzione, misura un *tratto* di corsa e
riscrive un profilo che veniva da nove foto su tre altezze; il giro dopo, le nove lo riscrivono
indietro. **Nessuna delle due misure è sbagliata**, ed è per questo che l'altalena non si
risolve scegliendo la «più giusta».

**Cosa si fa invece.** Il profilo si riscrive solo con almeno **6 scatti**, **2 giunzioni** su
quell'asse e **3 altezze** diverse per la gravità, e mai con una scala presa in prestito
dall'altro asse. La correzione di *questa* panoramica si applica lo stesso a *questa*
panoramica. Dettagli in [GIMBAL-E-TARATURA.md §4](GIMBAL-E-TARATURA.md#4-laltalena-e-la-regola-che-ne-è-nata).

---

## 6. La cilindrica su più file

**Provato.** La cilindrica è la proiezione preferita per le panoramiche larghe — l'utente la usa
per quasi tutto — quindi tenerla anche sulle griglie a più file.

**Perché non funziona.** La cilindrica allunga i pixel in verticale di **sec²φ**. A 79°
dall'orizzonte sono **14 volte**: pixel che nelle foto non esistono e che la tela dovrebbe
inventarsi. Su una 3×3 la tela passerebbe da 13.000 a 36.000 px di altezza, e le file esterne
verrebbero schiacciate sull'ultima riga.

**Cosa si fa invece.** Sopra i 65° si scende a **Mercatore** (sec φ), sopra i 72° a
**equirettangolare** (×1), scrivendolo nel verdetto con il conto in chiaro:

```
Proiezione: Equirettangolare invece di Cilindrica — la panoramica arriva a 79° dall'orizzonte,
dove la cilindrica allungherebbe i pixel di 14,0 volte in verticale
```

E siccome la scelta automatica resta una scelta fatta da un programma, ora c'è la
**fase intermedia**: si vede l'anteprima ridotta, si sceglie proiezione e punto di fuga a mano,
si guarda la deformazione in tempo reale.

---

## 7. La decodifica JPEG come collo di bottiglia

**Supposto.** L'unione dura 205 secondi, apre file da 37 Mpx: sarà la decodifica.

**Misurato.** **2 secondi su 205.** L'uno per cento.

**Cosa era davvero.** Le allocazioni dentro i cicli per-pixel: `intArrayOf(16, 8, 0)` scritto
dentro un ciclo che gira cento milioni di volte fa cento milioni di array, e il netturbino che
li raccoglie **ferma tutti i fili** — motivo per cui il contatore dei core segnava uno anche con
otto thread al lavoro. Non mancava il parallelismo: veniva continuamente interrotto.

Stessa storia con il «lavoro doppio» del riconoscimento, che dopo le altre correzioni vale 4 s e
non merita di essere toccato.

**La lezione.** Un cronometro per fase costa poco e ha risparmiato due refactoring inutili.

---

## 8. La fusione in GPU, prima versione

**Provato.** Portare la fusione multibanda sullo shader, come già ricognizione e pittura.

**Perché non funzionava.** Guadagno **zero**: la fusione restava 15 s e il riporto sulla tela
*peggiorava*, da 2,1 a 3,4 s. Lo shader era giusto — l'autocontrollo lo confermava — ma in mezzo
restava un passaggio CPU che rileggeva e riscriveva tutti i **155 milioni di pixel** della
fascia solo per estrarne l'alfa.

**Cosa si fa invece.** Lo shader emette **alfa opaca**, la fascia riletta va dritta nel Bitmap,
e la mappa dei possessori si aggiorna una volta per foto dai pesi già in mano. Fusione 15 → 13 s,
riporto 3,4 → 2,1 s.

**La lezione, generale.** Spostare un calcolo sulla GPU non serve a niente finché resta in mezzo
un passaggio che tocca comunque tutti i pixel.

---

## 9. Il trascinamento «a colpi» nell'anteprima

Tre tentativi, due sbagliati.

**Primo tentativo — il rimbalzo.** «Ci sarà il debounce da 90 ms che accorpa i movimenti.»
Tolto. Nessun cambiamento.

**Secondo tentativo — il rilevatore di gesti.** «Sarà `detectDragGestures` che perde il dito.»
Riscritto a mano con `awaitEachGesture` / `awaitFirstDown` / `awaitPointerEvent`. Nessun
cambiamento. *Quella riscrittura non poteva funzionare*, e va detto: il gesto non era il
problema.

**La causa vera.** Il modificatore era

```kotlin
.pointerInput(painted) { … }     // ← painted cambia a ogni ridisegno
```

e `Modifier.pointerInput(key)` **si riavvia quando la chiave cambia**, uccidendo il gesto in
corso. L'anteprima si ridisegnava due o tre volte al secondo, quindi ogni frazione di secondo
il dito veniva «lasciato». Da fuori: tre scatti e poi si blocca.

**La correzione.** `pointerInput(Unit)` — il blocco non si riavvia mai — e l'immagine corrente
letta dentro il gesto con `rememberUpdatedState`.

---

## 10. Il tocco che voltava la panoramica

**Il difetto.** Un tocco vicino al bordo dello schermo spostava il punto di fuga di ottanta
gradi.

**La causa.** Il tocco veniva letto sul riquadro intero, **bande nere comprese**: un dito nel
nero, fuori dall'immagine disegnata, veniva convertito in gradi come se fosse sull'immagine.

**La correzione, doppia.** I tocchi fuori dall'immagine disegnata si ignorano, e un singolo
tocco non può spostare più di **40° in orizzontale e 25° in verticale** — perché un tocco è
un'indicazione, non un salto.

---

## 11. La firma dell'APK

**Il sintomo.** L'aggiornamento in-app falliva: *«firma non valida»*.

**Prima diagnosi.** Digest SHA-256 diverso perché la CDN di GitHub serviva un file vecchio
dallo stesso indirizzo. **Corretta dall'utente**: era la *firma*, non il digest.

**Verifica.** Il keystore è nel repository e non è cambiato dal 22 agosto: la firma **non poteva**
essere diversa. A quel punto ho scoperto che GitHub pubblica anche il campo `digest` nella
release — il che rendeva coerente proprio la teoria della CDN scaduta, cioè la prima.

**Il vicolo cieco vero**, e l'unica cosa che poteva fare danno: aver consigliato di
**disinstallare l'app** per risolvere. Ritirato. Disinstallare avrebbe portato via impostazioni,
profilo del gimbal e lavori in attesa per un problema che stava in una cache.

**Cosa si fa adesso.** Il download riprova una volta sola con un indirizzo mai visto
(`?fresh=<millisecondi>`) e intestazioni che vietano la cache; e quando l'installazione viene
rifiutata, il log dice **perché**: nome del pacchetto, `versionCode`, e SHA-256 del certificato
di firma dell'APK scaricato accanto a quello installato. Non si tira più a indovinare.

In più il nome dell'allegato porta il commit (`luna-<commit>.apk`), così due build diverse non
possono mai condividere un indirizzo.

---

## 12. La forza bruta per trovare il posto delle foto

**Provato (a metà, e per fortuna).** Per capire dove va una foto che non porta angoli, la strada
ovvia è: proietta ogni foto in coordinate del mondo, e per **ogni coppia** prova **tutte le
posizioni possibili** una sull'altra, tenendo quella dove si somigliano di più. È correlazione
pura, si parallelizza benissimo, e con venti foto — centonovanta coppie per decine di migliaia di
posizioni ciascuna — chiede una scheda grafica per essere sopportabile.

**Perché non serve.** Le firme dei dettagli danno la stessa risposta **senza scandire niente**.
Ogni dettaglio riconoscibile diventa 256 bit che dipendono solo da com'è fatto; due firme che si
somigliano sono lo stesso dettaglio, ovunque sia finito. Da lì lo spostamento si legge, non si
cerca: ogni abbinamento ne propone uno, e la maggioranza vince.

È la stessa lezione dei punti di controllo, che sono passati da 27,4 s a 9,5 s **smettendo di
provare tutte le posizioni**. Due volte lo stesso errore, due volte la stessa cura.

**Il numero che chiude la questione.** Venti foto, quattro file da cinque, in ordine sbagliato:
centonovanta coppie provate, trentuno giunzioni trovate, **zero** fra foto che non si toccano,
errore massimo di posizione 1,4°. Il tutto in una manciata di secondi di CPU. Una scheda grafica
qui non avrebbe niente da accelerare che valga il codice per usarla — e il posto giusto per
guardarla è il log, dove i tempi ci sono scritti.

---

## 13. Le cose di Kotlin che sembrano funzionare e non funzionano

**Il costruttore privato annidato.** In Java la classe esterna vede i membri privati delle sue
classi annidate. **In Kotlin no**: `Cannot access 'PanoramaStitcher.Preview' constructor: it is
private`.

Renderlo `internal` scatena il seguito: `Property 'internal' exposes its 'private-in-class'
type 'GrayLevel'` — cioè per esporre il costruttore bisognerebbe esporre anche tutti i tipi che
tocca, e la classe interna smetterebbe di essere interna.

**La correzione.** Un'**interfaccia** pubblica (`PanoramaPreview`) e una `private inner class`
che la implementa. L'esterno vede l'interfaccia, i tipi privati restano privati, e come effetto
collaterale l'anteprima dal vivo e quella dalla cache diventano due implementazioni della stessa
cosa — che è esattamente ciò che impedisce loro di divergere.

---

## 14. Gli attrezzi che mentono

**Il controllo delle parentesi.** Uno script che conta graffe per trovare i file sbilanciati
segnalava un file **non modificato**. Causa: i nomi dei test in Kotlin stanno fra apici
inversi, e un apostrofo dentro un nome del genere veniva letto come inizio di un carattere
letterale — da lì in poi il conteggio era spazzatura.

**La CI incastrata.** Una esecuzione rimasta in coda che non si poteva né completare né
annullare (*«Cannot cancel a workflow re-run that has not yet queued»*), e nel frattempo i push
smettevano di creare esecuzioni nuove. Sbloccata permettendo a `workflow_dispatch` di
pubblicare la release e lanciandola a mano.

**La lezione comune.** Quando un attrezzo dice una cosa impossibile — un file mai toccato è
sbilanciato, un push non fa partire niente — il sospettato numero uno è l'attrezzo.

---

*© Persoft di Patassini Alessandro — licenza MIT*
