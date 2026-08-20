package it.persoft.lunaultra.camera

/**
 * Comandi del protocollo telefono → camera.
 *
 * I nomi seguono la nomenclatura `PHONE_COMMAND_*` / `CAMERA_NOTIFICATION_*` osservata nel
 * firmware. I valori numerici NON sono pubblici: vanno ricavati da una cattura del traffico
 * dell'app ufficiale (o trovati con lo scanner della schermata Diagnostica) e inseriti nel
 * registro comandi. Finché un comando non ha un id configurato l'app rifiuta di inviarlo,
 * invece di sparare byte a caso alla camera.
 */
enum class LunaCommand(
    val key: String,
    val label: String,
    val description: String,
) {
    CONNECT("PHONE_COMMAND_CONNECT", "Handshake", "Apre la sessione dopo la connessione TCP"),
    DISCONNECT("PHONE_COMMAND_DISCONNECT", "Chiusura sessione", "Chiude la sessione in modo pulito"),
    KEEP_ALIVE("PHONE_COMMAND_KEEP_ALIVE", "Keep-alive", "Heartbeat periodico per non far cadere la sessione"),
    GET_CAMERA_INFO("PHONE_COMMAND_GET_CAMERA_INFO", "Info camera", "Modello, firmware, seriale"),
    GET_CAMERA_STATE("PHONE_COMMAND_GET_CAMERA_STATE", "Stato camera", "Batteria, modalità, registrazione"),
    SET_CAPTURE_MODE("PHONE_COMMAND_SET_CAPTURE_MODE", "Imposta modalità", "Seleziona la modalità (es. Timelapse)"),
    START_CAPTURE("PHONE_COMMAND_START_CAPTURE", "Avvia registrazione", "Start registrazione/scatto"),
    STOP_CAPTURE("PHONE_COMMAND_STOP_CAPTURE", "Ferma registrazione", "Stop registrazione/scatto"),
    GIMBAL_CONTROL("PHONE_COMMAND_GIMBAL_CONTROL", "Controllo gimbal", "Movimento pan/tilt (velocità o posizione)"),
    GET_PTZ_OPTION("PHONE_COMMAND_GET_PTZ_OPTION", "Leggi PTZ", "Legge posizione/parametri PTZ"),
    SET_PTZ_OPTION("PHONE_COMMAND_SET_PTZ_OPTION", "Scrivi PTZ", "Imposta posizione/parametri PTZ"),
    ;

    companion object {
        fun fromKey(key: String): LunaCommand? = entries.firstOrNull { it.key == key }
    }
}

/** Notifiche camera → telefono che l'app riconosce (correlate per id, se configurato). */
enum class LunaNotification(val key: String, val label: String) {
    PTZ_STATE("CAMERA_NOTIFICATION_PTZ_STATE", "Stato PTZ"),
    CAMERA_STATE("CAMERA_NOTIFICATION_CAMERA_STATE", "Stato camera"),
    STORAGE_STATE("CAMERA_NOTIFICATION_STORAGE_STATE", "Stato storage"),
    ;
}
