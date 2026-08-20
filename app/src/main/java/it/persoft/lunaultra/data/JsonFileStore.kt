package it.persoft.lunaultra.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Persistenza locale minimale: un file JSON per tipo di dato, esposto come StateFlow.
 * Sufficiente per preset e sequenze, senza dipendenze da database.
 */
open class JsonFileStore<T>(
    private val file: File,
    private val serializer: KSerializer<T>,
    private val default: T,
    private val scope: CoroutineScope,
) {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val _state = MutableStateFlow(default)
    val state: StateFlow<T> = _state

    suspend fun load() = withContext(Dispatchers.IO) {
        _state.value = runCatching {
            if (!file.exists()) default else json.decodeFromString(serializer, file.readText())
        }.getOrDefault(default)
    }

    fun update(transform: (T) -> T) {
        val next = transform(_state.value)
        _state.value = next
        scope.launch(Dispatchers.IO) { persist(next) }
    }

    fun exportJson(): String = json.encodeToString(serializer, _state.value)

    fun importJson(text: String): Result<Unit> = runCatching {
        val parsed = json.decodeFromString(serializer, text)
        _state.value = parsed
        scope.launch(Dispatchers.IO) { persist(parsed) }
    }

    private fun persist(data: T) {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(json.encodeToString(serializer, data))
            tmp.renameTo(file)
        }
    }
}
