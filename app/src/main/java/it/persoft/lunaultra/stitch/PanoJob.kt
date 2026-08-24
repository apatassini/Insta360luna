package it.persoft.lunaultra.stitch

import kotlinx.serialization.Serializable

/**
 * Una panoramica scattata e non ancora unita: il lavoro aspetta sul telefono.
 *
 * Unire venti scatti sono minuti di calcolo, e chi è in giro a fotografare non ha voglia di
 * stare a guardare una barra: gli scatti si scaricano subito — quello va fatto lì, finché la
 * camera è a portata di Wi-Fi — e l'unione si lancia quando si vuole, anche la sera, anche il
 * giorno dopo. Le foto aspettano in `DCIM › Luna Ultra › Panoramiche`, ognuna con il suo
 * passaporto negli EXIF, quindi il job sa rimetterle insieme senza indovinare niente.
 */
@Serializable
data class PanoJob(
    /** L'identità del panorama, la stessa scritta nei tag EXIF delle foto. */
    val id: String,
    val createdAtMs: Long,
    /** I percorsi assoluti degli scatti scaricati, in ordine. */
    val files: List<String>,
    val fovDegrees: Float,
    val spherical: Boolean = false,
)

/** L'elenco persistente dei job: sopravvive alla chiusura dell'app, che è il suo mestiere. */
@Serializable
data class PanoJobList(
    val jobs: List<PanoJob> = emptyList(),
)
