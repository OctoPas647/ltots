package com.ltots.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One person using the app: their own looks, EQs, settings and songs' sound. */
data class User(val id: String, val name: String)

/**
 * The people who use LToTS on this phone (up to [MAX]). Each has a folder of their own (see
 * [Store]); switching user loads theirs. The switch itself (reloading the look and the sound) is
 * done by [com.ltots.app.profiles.UserSwitch], which knows the parts of the app involved.
 */
object Users {
    const val MAX = 3
    const val FIRST_ID = "u1"

    private val _users = MutableStateFlow(listOf(User(FIRST_ID, "User 1")))
    val users: StateFlow<List<User>> = _users
    private val _active = MutableStateFlow(FIRST_ID)
    val active: StateFlow<String> = _active

    val activeUser: User get() = _users.value.firstOrNull { it.id == _active.value } ?: _users.value.first()

    private fun file(root: File) = File(root, "users.json")

    /** The user in use according to the saved list (the first user if there is none yet). */
    fun loadActiveId(root: File): String {
        val (list, active) = read(root)
        _users.value = list
        _active.value = active
        if (!file(root).exists()) save(root)
        return active
    }

    private fun read(root: File): Pair<List<User>, String> = runCatching {
        val o = JSONObject(file(root).readText())
        val arr = o.getJSONArray("users")
        val list = (0 until arr.length()).map { i ->
            val u = arr.getJSONObject(i)
            User(u.getString("id"), u.optString("name", "User ${i + 1}"))
        }.filter { it.id.matches(ID_PATTERN) }.distinctBy { it.id }.take(MAX)
        val active = o.optString("active").takeIf { a -> list.any { it.id == a } } ?: list.first().id
        list to active
    }.getOrElse { listOf(User(FIRST_ID, "User 1")) to FIRST_ID }

    private fun save(root: File = Store.rootDir) {
        val arr = JSONArray().apply { _users.value.forEach { put(JSONObject().put("id", it.id).put("name", it.name)) } }
        Store.writeFileNow(file(root), JSONObject().put("active", _active.value).put("users", arr).toString())
    }

    private val ID_PATTERN = Regex("u[0-9]+")

    /** A name not used by another user yet, from [wanted] ("User 2" when blank). */
    fun freeName(wanted: String, except: String? = null): String {
        val taken = _users.value.filter { it.id != except }.map { it.name.lowercase() }.toSet()
        val base = wanted.trim().take(30).ifBlank {
            (1..MAX + 1).map { "User $it" }.first { it.lowercase() !in taken }
        }
        if (base.lowercase() !in taken) return base
        var n = 2
        while ("$base ($n)".lowercase() in taken) n++
        return "$base ($n)"
    }

    private fun newId(): String {
        val used = _users.value.map { it.id }.toSet()
        var n = 1
        while ("u$n" in used || Store.dirOf("u$n").exists()) n++
        return "u$n"
    }

    /** Adds a user who starts with everything at its default; null when there are already [MAX]. */
    fun add(name: String = ""): User? {
        if (_users.value.size >= MAX) return null
        val u = User(newId(), freeName(name))
        Store.dirOf(u.id).mkdirs()
        _users.value = _users.value + u
        save()
        return u
    }

    fun rename(id: String, name: String) {
        if (_users.value.none { it.id == id }) return
        val n = freeName(name, except = id)
        _users.value = _users.value.map { if (it.id == id) it.copy(name = n) else it }
        save()
    }

    /** Marks [id] as the user in use (the folder switch and reloading is [com.ltots.app.profiles.UserSwitch]'s). */
    internal fun setActive(id: String) {
        if (_users.value.none { it.id == id }) return
        _active.value = id
        save()
    }

    /** Removes user [id] and everything they kept. Never the user in use, nor the only one. */
    internal fun remove(id: String): Boolean {
        if (id == _active.value || _users.value.size <= 1 || _users.value.none { it.id == id }) return false
        _users.value = _users.value.filterNot { it.id == id }
        save()
        Store.dirOf(id).deleteRecursively()
        return true
    }

    /**
     * Adds a user from an exported file's contents ([files]: file name inside the user's folder to
     * its text). Null when there are already [MAX] users.
     */
    internal fun addFrom(name: String, files: Map<String, String>): User? {
        val u = add(name) ?: return null
        val dir = Store.dirOf(u.id)
        for ((rel, text) in files) {
            val safe = rel.replace('\\', '/').trimStart('/')
            if (safe.contains("..")) continue
            val ok = safe in Store.userFiles || (safe.startsWith("profiles/") && safe.count { it == '/' } == 1 && safe.endsWith(".json"))
            if (ok) Store.writeFileNow(File(dir, safe), text)
        }
        return u
    }

    /** Every file of user [id] (name inside their folder to text), for exporting. */
    internal fun filesOf(id: String): Map<String, String> {
        Store.flush()
        val dir = Store.dirOf(id)
        val out = LinkedHashMap<String, String>()
        for (n in Store.userFiles) File(dir, n).takeIf { it.isFile }?.let { out[n] = it.readText() }
        File(dir, "profiles").listFiles()?.filter { it.isFile && it.name.endsWith(".json") }?.sortedBy { it.name }?.forEach {
            out["profiles/${it.name}"] = it.readText()
        }
        return out
    }

    /** Icon pictures any user's looks still point at (so none is cleaned up while still in use). */
    fun iconsInUse(): Set<String> {
        Store.flush()
        val out = HashSet<String>()
        for (u in _users.value) {
            val dir = Store.dirOf(u.id)
            fun scan(o: JSONObject?) { o?.optString("iconFile", "")?.takeIf { it.isNotEmpty() }?.let { out.add(it) } }
            runCatching { scan(JSONObject(File(dir, "theme.json").readText())) }
            runCatching {
                val o = JSONObject(File(dir, "look_profiles.json").readText())
                o.optJSONArray("items")?.let { a -> for (i in 0 until a.length()) scan(a.optJSONObject(i)?.optJSONObject("look")) }
                o.optJSONArray("slots")?.let { a -> for (i in 0 until a.length()) scan(a.optJSONObject(i)) }
            }
        }
        return out
    }
}
