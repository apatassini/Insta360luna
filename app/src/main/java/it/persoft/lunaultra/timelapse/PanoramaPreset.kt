package it.persoft.lunaultra.timelapse

import kotlin.math.abs

/**
 * Lato lungo delle coperture in proporzione: sta dentro la corsa con margine.
 *
 * Sta fuori dall'enum e non nel suo companion perché le voci di un enum vengono costruite
 * prima del companion: leggerlo da lì sarebbe leggere un valore non ancora inizializzato, e
 * Kotlin lo rifiuta a ragione.
 */
private const val LONG_SIDE_DEG = 120f

/** Quanto i gradi possono discostarsi e continuare a essere "quella" copertura. */
private const val TOLERANCE_DEG = 1f

/**
 * Coperture panoramiche pronte, nominate per il risultato invece che per i numeri.
 *
 * Chi inquadra pensa «voglio una striscia larga» o «voglio prendere anche il cielo e il
 * pavimento», non «voglio 202,5 gradi per 67,5». I gradi restano scritti sotto ogni voce —
 * servono a capire cosa sta per succedere — ma la scelta si fa sul risultato.
 *
 * Le due voci 16:9 derivano i gradi dal rapporto stesso: 120° sul lato lungo e 120×9/16 = 67,5°
 * sul corto. Non è una regola universale, è il modo di ottenere una panoramica che abbia
 * davvero quella forma invece di un numero tondo che poi va ritagliato.
 */
enum class PanoramaPreset(
    val label: String,
    val horizontalDegrees: Float,
    val verticalDegrees: Float,
    val detail: String,
) {
    WIDE_16_9(
        label = "16:9 orizzontale",
        horizontalDegrees = LONG_SIDE_DEG,
        verticalDegrees = LONG_SIDE_DEG * 9f / 16f,
        detail = "Striscia larga con la forma di uno schermo: 120° × 67°.",
    ),
    TALL_16_9(
        label = "16:9 verticale",
        horizontalDegrees = LONG_SIDE_DEG * 9f / 16f,
        verticalDegrees = LONG_SIDE_DEG,
        detail = "La stessa forma in piedi: 67° × 120°. Per gole, facciate, alberi.",
    ),
    HALF_TURN(
        label = "Mezzo giro",
        horizontalDegrees = 180f,
        verticalDegrees = 60f,
        detail = "180° davanti a te, una fascia di cielo e terra: 180° × 60°.",
    ),
    WIDEST(
        label = "Massima",
        horizontalDegrees = 200f,
        verticalDegrees = 60f,
        detail = "Più di mezzo giro, quanto la corsa consente: 200° × 60°.",
    ),
    FULL(
        label = "Alta e larga",
        horizontalDegrees = 180f,
        verticalDegrees = 130f,
        detail = "Mezzo giro prendendo anche sopra e sotto: 180° × 130°. Tanti scatti.",
    ),
    ;

    /** Vero quando i gradi impostati sono quelli di questa voce, a meno di un grado. */
    fun matches(horizontal: Float, vertical: Float): Boolean =
        abs(horizontal - horizontalDegrees) < TOLERANCE_DEG &&
            abs(vertical - verticalDegrees) < TOLERANCE_DEG

    companion object {
        /** La voce corrispondente ai gradi correnti, oppure null: quello è il personalizzato. */
        fun matching(horizontal: Float, vertical: Float): PanoramaPreset? =
            entries.firstOrNull { it.matches(horizontal, vertical) }
    }
}
