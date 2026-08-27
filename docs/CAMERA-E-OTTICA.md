# La camera, l'ottica, l'esemplare in prova

Tutto quello che si sa della **Insta360 Luna Ultra** come *macchina fotografica* — non come
dispositivo di rete: il protocollo sta nel [README](../README.md), il gimbal in
[GIMBAL-E-TARATURA.md](GIMBAL-E-TARATURA.md), la coda dei file in
[CODA-DEI-FILE-INSTA360.md](CODA-DEI-FILE-INSTA360.md).

Regola di questo documento: **ogni numero dice da dove viene**. «Catalogo» significa che è
scritto nelle specifiche e non l'ha controllato nessuno; «misurato» significa che l'app lo ha
tirato fuori da foto vere di questo esemplare, e dice come.

---

## 1. L'esemplare e l'ambiente di prova

| | |
|---|---|
| Camera | Insta360 Luna Ultra |
| Firmware verificato | **v1.0.288** |
| Indirizzo di controllo | `192.168.42.1:6666` (Wi-Fi della camera) |
| Codice del comando gimbal | **226** (`0x00E2`) |
| Notifica PTZ osservata | **8302** — codice quasi certo, contenuto **non decodificato** |
| Telefono di prova | Adreno (TM) 750, texture fino a **16384 px**, heap Java **512 MB** (`largeHeap`), memoria di sistema libera tipica ~2,6 GB |

Il telefono conta quanto la camera: le due unioni descritte in
[PROVE-E-MISURE.md](PROVE-E-MISURE.md) girano al limite della memoria, e il limite di texture
decide se un fotogramma si dipinge sulla scheda grafica o sulla CPU.

---

## 2. Il fotogramma

Una foto della Luna Ultra a 1× in 4:3 esce di **circa 37 Mpx** (lato lungo ~7000 px). È il
numero che comanda tutto il resto: nove foto da 37 Mpx con il 30% di sovrapposizione fanno
~230 Mpx di contenuto unico, ed è per quello che la tela di una 3×3 arriva a 13.900 × 10.500 e
oltre.

L'app **non** lavora sugli originali durante l'allineamento: fa una copia ridotta (di serie
3200 px di lato lungo, 1100 in preparazione dell'anteprima) e cuce poi dagli originali a piena
risoluzione. Il rapporto fra le due — nel log «×2,2 rispetto ai 3200 px di lavoro» — è ciò che
decide se l'originale entra in una texture.

---

## 3. L'ottica: quello che dice il catalogo e quello che fa la lente

### 3.1 Il catalogo

Le lenti sono **standard e dichiarate**: **20 mm equivalenti** a 1×, **60 mm** a 3×, riferiti
alla diagonale full-frame (43,266 mm). Non sono numeri da inventare, e l'app li usa come
punto di partenza:

```
altezzaEq   = 43,266 / √(ratio² + 1)
larghezzaEq = altezzaEq · ratio
FOV_h = 2 · atan( larghezzaEq / (2 · focaleEq) )
```

A 1× in 4:3 dà **81,74° × 66,0°**.

### 3.2 La misura

Quel campo dichiarato **non è quello che il file contiene**. Misurato contro la gravità — cioè
contro un righello che non dipende da nessuna ipotesi ottica — su panoramiche vere:

| panoramica | campo orizzontale misurato | dichiarato |
|---|---|---|
| nove scatti, spiaggia | **77,07°** | 81,74° |
| nove scatti, 26 agosto | **77,67°** | 81,74° |

Cioè **la camera ritaglia circa il 7% del fotogramma** prima di scriverlo. La differenza è
piccola in gradi e enorme in pixel: su una fila di nove scatti un 5% di campo sbagliato è
mezzo fotogramma di errore accumulato all'estremo.

Da quando la misura c'è, il verdetto la scrive sempre:

```
Campo visivo misurato contro la gravità: 77,67° invece dei 81,74° dichiarati
(la camera ritaglia il 7% del fotogramma)
Campo visivo: dichiarato 77,7°, misurato 77,6° — la specifica regge.
```

Le due righe non dicono la stessa cosa: la prima confronta il **catalogo** con la **gravità**,
la seconda confronta il campo che l'app sta usando con quello che le foto, sovrapponendosi,
confermano. La seconda deve tornare sempre; se non torna, l'allineamento sta compensando un
errore ottico con una rotazione sbagliata.

### 3.3 Perché serviva un righello esterno

**Una focale sbagliata e una scala del gimbal sbagliata si compensano a vicenda.** Se la lente
è più stretta del dichiarato *e* il gimbal si muove di più del comandato, le foto si
sovrappongono esattamente come dovrebbero: la coppia di errori è invisibile a qualunque misura
fatta confrontando foto con foto.

Le due cose si separano solo con una misura che non venga dalle immagini. Quella misura è la
**gravità**, letta dall'accelerometro nella coda del file: dà l'inclinazione vera di ogni
scatto, in assoluto. Da lì il campo visivo si ricava per differenza, e il gimbal resta l'unico
imputato.

Questa è, in una riga, la ragione per cui l'app legge i byte dopo la fine del JPEG.

---

## 4. Assetto: quello che la camera sa di sé, e quello che non sa

| grandezza | c'è? | da dove |
|---|---|---|
| **beccheggio** (inclinazione) | sì | accelerometro nella coda del file, in assoluto |
| **rollio** | sì | idem |
| **pan** (rotazione orizzontale) | **no, e non ci può essere** | la gravità è simmetrica attorno alla verticale |
| posizione del gimbal richiesta | sì, ma è *ciò che è stato chiesto* | l'app la conosce perché è lei a comandare |
| posizione del gimbal **vera** | **no** | la camera non la comunica |

L'ultima riga è il fatto più importante di tutto il progetto, ed è costato ore di ricerca
prima di essere accettato: **la Luna Ultra non restituisce la posizione del gimbal**. La
notifica 8302 esiste, arriva, e il suo contenuto non è stato decodificato; nella coda dei file
il pan non c'è; nei tag EXIF non c'è.

Conseguenza diretta: il gimbal naviga **a stima** (`posizione += velocità × tempo`), e ogni
errore sulla velocità si accumula. Vedi [GIMBAL-E-TARATURA.md](GIMBAL-E-TARATURA.md).

### 4.1 Il beccheggio non dichiarato

Sulle nove foto della spiaggia, a inclinazione comandata **zero**, la gravità diceva **+6,86°**:
la camera guardava in su di quasi sette gradi senza dirlo. Era quello, misurato invece che
dedotto, il «mare curvo» — la fila centrale non era orizzontale, e l'orizzonte usciva a arco.

Nel log del 26 agosto lo stesso fenomeno, su tre scatti:

```
Inclinazione presa dalla gravità, non stimata:
  -32°→-32,0° · -32°→-32,1° · -32°→-32,2°
    0°→ 6,8°  ·   0°→ 6,9°  ·   0°→ 6,9°
   32°→47,0°  ·  32°→46,9°  ·  32°→47,0°
```

Tre cose si leggono in quella tabella:

1. **La ripetibilità è ottima**: tre scatti alla stessa inclinazione comandata, a pan diversi,
   danno lo stesso beccheggio entro un decimo di grado. La misura è buona.
2. **Lo zero non è zero**: c'è uno scostamento di ~6,9°.
3. **La scala non è uno**: da 0 a +32 comandati il gimbal fa 40 gradi veri, da 0 a −32 ne fa
   39. Il fattore complessivo, tirato per i minimi quadrati su tutti gli scatti, è **×1,235**.

---

## 5. Come l'app usa questi fatti

- **L'inclinazione non si stima più**: quando la coda del file c'è, il beccheggio di ogni
  scatto viene dalla gravità e la ricerca dell'orizzonte nell'immagine non serve
  (`Orizzonte: non serve cercarlo, l'inclinazione viene dalla gravità`).
- **Il campo visivo si misura** a ogni unione, e il valore misurato — non quello di catalogo —
  entra nella proiezione.
- **Anche le foto del telefono** passano di qui: se non hanno i tag della panoramica ma hanno
  la coda Insta360, l'app legge beccheggio e rollio, spezza le file dove il beccheggio salta
  più del 35% del campo orizzontale, e distribuisce il pan a passi uguali dentro ogni fila
  (`UNIONE MANUALE · LETTA DALLE FOTO`). Il pan resta l'unica cosa ipotizzata, perché è
  l'unica che nessuno ha scritto da nessuna parte.

---

*© Persoft di Patassini Alessandro — licenza MIT*
