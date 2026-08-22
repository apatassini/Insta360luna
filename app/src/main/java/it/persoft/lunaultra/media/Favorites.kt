package it.persoft.lunaultra.media

import kotlinx.serialization.Serializable

/**
 * I file segnati come preferiti, per percorso sulla camera.
 *
 * Il percorso è l'unico identificatore stabile che la camera dà: sopravvive al riavvio dell'app
 * e a un nuovo elenco della libreria. Se un file viene cancellato dalla camera la voce resta
 * qui e non fa danno — semplicemente non corrisponde più a niente.
 */
@Serializable
data class Favorites(val paths: Set<String> = emptySet()) {
    operator fun contains(path: String): Boolean = path in paths

    fun toggled(path: String): Favorites =
        if (path in paths) copy(paths = paths - path) else copy(paths = paths + path)
}
