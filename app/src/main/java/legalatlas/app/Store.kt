package legalatlas.app

import android.util.Log
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import legalatlas.core.AppState
import legalatlas.core.AtlasJson

/** Всё состояние живёт в одном JSON-файле: для одного пользователя этого достаточно. */
class Store(dir: File) {
    private val file = File(dir, "state.json")
    private val tmp = File(dir, "state.json.tmp")
    private val lock = Mutex()

    fun load(): AppState = try {
        if (file.exists()) AtlasJson.decodeFromString(AppState.serializer(), file.readText()) else AppState()
    } catch (e: Exception) {
        Log.e("Store", "Не удалось прочитать состояние, начинаем с пустого", e)
        file.copyTo(File(file.parentFile, "state.broken.json"), overwrite = true)
        AppState()
    }

    suspend fun save(state: AppState) = withContext(Dispatchers.IO) {
        lock.withLock {
            tmp.writeText(AtlasJson.encodeToString(AppState.serializer(), state))
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        }
    }
}
