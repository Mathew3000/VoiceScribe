package app.voicescribe

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Entry(val id: Long, val text: String, val seconds: Int, val model: String)

/** Keeps the last few transcripts in a private JSON file inside the app's storage. */
class HistoryStore(context: Context, private val max: Int = 10) {
    private val file = File(context.filesDir, "history.json")

    fun load(): List<Entry> = try {
        val a = JSONArray(file.readText())
        (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Entry(o.getLong("id"), o.getString("text"), o.getInt("seconds"), o.getString("model"))
        }
    } catch (e: Exception) { emptyList() }

    private fun save(list: List<Entry>) {
        val a = JSONArray()
        list.forEach {
            a.put(JSONObject().put("id", it.id).put("text", it.text)
                .put("seconds", it.seconds).put("model", it.model))
        }
        val tmp = File(file.path + ".tmp")
        tmp.writeText(a.toString())
        tmp.renameTo(file)
    }

    fun add(e: Entry): List<Entry> = (listOf(e) + load()).take(max).also { save(it) }
    fun delete(id: Long): List<Entry> = load().filter { it.id != id }.also { save(it) }
    fun clear() { file.delete() }
}
