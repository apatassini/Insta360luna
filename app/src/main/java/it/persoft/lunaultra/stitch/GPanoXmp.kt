package it.persoft.lunaultra.stitch

import java.io.InputStream
import java.io.OutputStream
import kotlin.math.roundToInt

/**
 * Il cartello che dice ai visori «questa è una sferica».
 *
 * Un'equirettangolare non si riconosce guardandola: è un JPEG rettangolare come tutti gli altri,
 * e Google Foto, il visore di sistema o un qualunque programma da scrivania la mostrano piatta,
 * stirata ai poli, senza offrire di girarci dentro. Quello che manca non è nei pixel: è un
 * pacchetto **XMP** nel file, con lo spazio dei nomi `GPano` che Google ha pubblicato per questo.
 *
 * I campi che contano sono sei, e servono tutti insieme perché una panoramica quasi mai copre la
 * sfera intera. `FullPano*` dice quanto sarebbe la sfera completa, `CroppedArea*` dice quale
 * pezzo di quella sfera è questa immagine e dove sta dentro di essa. Senza `CroppedAreaTopPixels`
 * corretto il visore mette l'orizzonte dove capita, e una panoramica che copre da +60° a −89°
 * finisce rovesciata di trenta gradi.
 *
 * Vale solo per l'equirettangolare. La cilindrica e la Mercatore sono altre mappe: dichiararle
 * sferiche vorrebbe dire far vedere una cosa deformata giurando che è giusta.
 */
object GPanoXmp {

    /**
     * L'intestazione che identifica il pacchetto XMP dentro un segmento APP1.
     *
     * Finisce con uno zero, non con uno spazio: è quel byte a chiudere la stringa, ed è l'unica
     * cosa che distingue questo APP1 da quello di Exif, che al suo posto ha "Exif" e due zeri.
     */
    private val XMP_HEADER = "http://ns.adobe.com/xap/1.0/" + Char(0)

    private const val FULL_TURN = 360f
    private const val HALF_TURN = 180f

    /** Come si collocano i pixel di questa immagine dentro la sfera intera. */
    data class Placement(
        val fullWidth: Int,
        val fullHeight: Int,
        val croppedWidth: Int,
        val croppedHeight: Int,
        val left: Int,
        val top: Int,
    )

    /**
     * Dove sta questa tela dentro la sfera intera.
     *
     * La sfera completa alla stessa scala è 360° × 180°; l'immagine ne occupa la parte che il
     * gimbal ha davvero raggiunto. In verticale la posizione la decide il bordo alto della tela
     * rispetto allo zenit; in orizzontale la si mette in mezzo, perché una panoramica non
     * dichiara dove sia il nord e fingere di saperlo sarebbe peggio che non dirlo.
     */
    fun placement(
        imageWidth: Int,
        imageHeight: Int,
        centerTiltDegrees: Float,
        verticalDegrees: Float,
        pixelsPerDegree: Float,
    ): Placement? {
        if (imageWidth <= 0 || imageHeight <= 0 || pixelsPerDegree <= 0f) return null
        val fullWidth = (FULL_TURN * pixelsPerDegree).roundToInt().coerceAtLeast(imageWidth)
        val fullHeight = (HALF_TURN * pixelsPerDegree).roundToInt().coerceAtLeast(imageHeight)
        val topLatitude = centerTiltDegrees + verticalDegrees / 2f
        val top = ((90f - topLatitude) * pixelsPerDegree).roundToInt()
            .coerceIn(0, fullHeight - imageHeight)
        val left = ((fullWidth - imageWidth) / 2f).roundToInt().coerceIn(0, fullWidth - imageWidth)
        return Placement(
            fullWidth = fullWidth,
            fullHeight = fullHeight,
            croppedWidth = imageWidth,
            croppedHeight = imageHeight,
            left = left,
            top = top,
        )
    }

    /**
     * Il pacchetto XMP per questa panoramica, o `null` se non c'è niente di vero da dichiarare.
     *
     * @param report il referto dell'unione, che porta proiezione, scala e centro verticale.
     */
    fun build(report: StitchReport, imageWidth: Int, imageHeight: Int): String? {
        if (report.projection != StitchProjection.EQUIRECTANGULAR) return null
        val placement = placement(
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            centerTiltDegrees = report.centerTiltDegrees,
            verticalDegrees = report.coverageVerticalDegrees,
            pixelsPerDegree = report.pixelsPerDegree,
        ) ?: return null
        return packet(placement)
    }

    /** Lo stesso pacchetto a partire dalla collocazione già calcolata. */
    fun packet(placement: Placement): String = buildString {
        append("<?xpacket begin=\"﻿\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>")
        append("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">")
        append("<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">")
        append("<rdf:Description rdf:about=\"\" ")
        append("xmlns:GPano=\"http://ns.google.com/photos/1.0/panorama/\" ")
        // Questo è il campo che accende il visore sferico: senza, gli altri restano lettera morta.
        append("GPano:UsePanoramaViewer=\"True\" ")
        append("GPano:ProjectionType=\"equirectangular\" ")
        append("GPano:CroppedAreaImageWidthPixels=\"${placement.croppedWidth}\" ")
        append("GPano:CroppedAreaImageHeightPixels=\"${placement.croppedHeight}\" ")
        append("GPano:FullPanoWidthPixels=\"${placement.fullWidth}\" ")
        append("GPano:FullPanoHeightPixels=\"${placement.fullHeight}\" ")
        append("GPano:CroppedAreaLeftPixels=\"${placement.left}\" ")
        append("GPano:CroppedAreaTopPixels=\"${placement.top}\" ")
        append("GPano:StitchingSoftware=\"Luna Timelapse\" ")
        append("GPano:CaptureSoftware=\"Luna Timelapse\"/>")
        append("</rdf:RDF></x:xmpmeta>")
        append("<?xpacket end=\"w\"?>")
    }

    /**
     * Copia un JPEG inserendo il pacchetto XMP come segmento APP1.
     *
     * L'inserimento va **dopo** gli APP0/APP1 che già ci sono, non subito dopo il SOI: l'APP1 di
     * Exif per convenzione viene per primo, e i lettori più rigidi lo cercano lì. Un eventuale
     * XMP già presente viene sostituito invece che affiancato, perché due pacchetti sono peggio
     * di nessuno — il lettore ne prende uno a caso.
     *
     * @return quanti byte di XMP sono stati scritti, zero se il flusso non era un JPEG.
     */
    fun copyInserting(source: InputStream, destination: OutputStream, xmp: String): Int {
        val soi = ByteArray(2)
        if (source.read(soi) != 2 || soi[0] != MARKER_START || soi[1] != SOI) {
            // Non è un JPEG: si copia com'è, senza inventare niente.
            destination.write(soi, 0, maxOf(0, soi.size))
            source.copyTo(destination)
            return 0
        }
        destination.write(soi)

        while (true) {
            val first = source.read()
            if (first < 0) break
            if (first != 0xFF) {
                // Fuori sincrono: da qui in poi è corpo, si copia e basta.
                destination.write(first)
                source.copyTo(destination)
                return 0
            }
            var marker = source.read()
            while (marker == 0xFF) marker = source.read()   // byte di riempimento
            if (marker < 0) break

            if (marker != APP0 && marker != APP1) {
                // Finiti i segmenti di intestazione: l'XMP va qui, prima di tutto il resto.
                val written = writeXmp(destination, xmp)
                destination.write(0xFF)
                destination.write(marker)
                source.copyTo(destination)
                return written
            }

            val high = source.read()
            val low = source.read()
            if (high < 0 || low < 0) break
            val length = (high shl 8) or low
            val body = ByteArray((length - 2).coerceAtLeast(0))
            readFully(source, body)
            if (marker == APP1 && body.size >= XMP_HEADER.length &&
                String(body, 0, XMP_HEADER.length, Charsets.ISO_8859_1) == XMP_HEADER
            ) {
                continue    // vecchio XMP: si butta, il nuovo lo rimpiazza
            }
            destination.write(0xFF)
            destination.write(marker)
            destination.write(high)
            destination.write(low)
            destination.write(body)
        }
        // Il file finiva fra le intestazioni: l'XMP si scrive lo stesso, è tutto quel che resta.
        return writeXmp(destination, xmp)
    }

    private fun writeXmp(destination: OutputStream, xmp: String): Int {
        val payload = XMP_HEADER.toByteArray(Charsets.ISO_8859_1) + xmp.toByteArray(Charsets.UTF_8)
        val length = payload.size + 2
        require(length <= 0xFFFF) { "Il pacchetto XMP non entra in un segmento APP1" }
        destination.write(0xFF)
        destination.write(APP1)
        destination.write((length shr 8) and 0xFF)
        destination.write(length and 0xFF)
        destination.write(payload)
        return payload.size
    }

    private fun readFully(source: InputStream, buffer: ByteArray) {
        var read = 0
        while (read < buffer.size) {
            val n = source.read(buffer, read, buffer.size - read)
            if (n < 0) break
            read += n
        }
    }

    private const val MARKER_START = 0xFF.toByte()
    private const val SOI = 0xD8.toByte()
    private const val APP0 = 0xE0
    private const val APP1 = 0xE1
}
