# Documentazione del progetto

Tutto quello che si sa, diviso per argomento. Regola comune a tutti i documenti: **ogni numero
dice da dove viene** — «catalogo» vuol dire scritto nelle specifiche e mai verificato,
«misurato» vuol dire tirato fuori da foto vere di questo esemplare, con il metodo accanto.

| documento | cosa contiene |
|---|---|
| [CAMERA-E-OTTICA.md](CAMERA-E-OTTICA.md) | l'esemplare, il fotogramma, le lenti dichiarate contro quelle misurate, cosa la camera sa di sé e cosa no |
| [CODA-DEI-FILE-INSTA360.md](CODA-DEI-FILE-INSTA360.md) | il formato dei blocchi dopo la fine del JPEG e la traccia inerziale a 1 kHz, byte per byte |
| [GIMBAL-E-TARATURA.md](GIMBAL-E-TARATURA.md) | navigazione a stima, la scala ×1,235, la non linearità, le regole per riscrivere il profilo |
| [FLUSSO-PANORAMICA.md](FLUSSO-PANORAMICA.md) | job → preparazione → punto di fuga → unione, e le foto già sul telefono |
| [UNIONE-PANORAMICHE.md](UNIONE-PANORAMICHE.md) | la matematica: proiezioni, allineamento, fotometria, cucitura, fusione multibanda |
| [PROVE-E-MISURE.md](PROVE-E-MISURE.md) | i tempi misurati build per build, gli autocontrolli GPU, la memoria, i prossimi bersagli |
| [VICOLI-CIECHI.md](VICOLI-CIECHI.md) | le strade provate che non portavano da nessuna parte, e cosa si è fatto invece |
| [FIRMA-E-PUBBLICAZIONE.md](FIRMA-E-PUBBLICAZIONE.md) | come si firma l'APK, dove stanno le chiavi, e perché il passaggio costa una disinstallazione |

Il protocollo di controllo (framing UCD2, comandi, codici, galleria, aggiornamenti) sta nel
[README principale](../README.md).

---

## I quattro fatti che spiegano quasi tutto il resto

1. **La camera non dice dove si trova il gimbal.** Nessun ritorno di posizione, in nessuna
   forma: né dal protocollo, né dai file. Quindi il gimbal naviga a stima e la scala va
   corretta misurando le panoramiche.
2. **Il pan non è nei file e non ci può essere.** L'unico sensore assoluto è l'accelerometro, e
   la gravità è simmetrica attorno alla verticale.
3. **Focale e rotazione sono degeneri.** Una lente più stretta e un gimbal più veloce danno la
   stessa sovrapposizione: si separano solo con un righello esterno, che è la gravità.
4. **Il gimbal non è lineare.** I rapporti chiesto-contro-fatto ballano di 0,180 lungo la
   corsa: chi misura poco misura un tratto, non la corsa.

---

*© Persoft di Patassini Alessandro — licenza MIT*
