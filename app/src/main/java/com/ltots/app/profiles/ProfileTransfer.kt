package com.ltots.app.profiles

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.ltots.app.audio.AudioEngine
import com.ltots.app.audio.EqDefaults
import com.ltots.app.audio.EqState
import com.ltots.app.data.EqProfiles
import com.ltots.app.data.IconFiles
import com.ltots.app.data.Json
import com.ltots.app.data.Store
import com.ltots.app.data.ThemeConfig
import com.ltots.app.data.Users
import com.ltots.app.ui.theme.ThemeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Switching user: everything that belongs to a person (look, EQ, settings, songs' own sound) is
 * saved, and the new user's is loaded in its place.
 */
object UserSwitch {
    fun switchTo(id: String) {
        if (id == Users.active.value || Users.users.value.none { it.id == id }) return
        Store.useUser(id)
        Users.setActive(id)
        ThemeState.reloadForUser()
        EqProfiles.load()
        AudioEngine.reloadForUser()
    }

    /** Deletes user [id] and everything they kept; the user in use moves to another first. */
    fun delete(id: String): Boolean {
        val users = Users.users.value
        if (users.size <= 1 || users.none { it.id == id }) return false
        if (id == Users.active.value) switchTo(users.first { it.id != id }.id)
        return Users.remove(id)
    }
}

/**
 * Exported profiles: small JSON files (look, EQ, or a whole user) that can be kept, shared and
 * imported again, on this phone or another. A look that uses an icon picture carries the picture.
 */
object ProfileTransfer {
    private const val KIND = "ltots"
    private const val VERSION = 1

    sealed interface Imported {
        data class Look(val name: String) : Imported
        data class Eq(val name: String) : Imported
        data class User(val name: String) : Imported
        /** Not added; [why] says what to do. */
        data class Refused(val why: String) : Imported
    }

    // ---- files -----------------------------------------------------------------------------

    fun fileName(kind: String, name: String) = "LToTS $kind - ${name.replace(Regex("[\\\\/:*?\"<>|]"), "_")}.json"

    suspend fun write(context: Context, uri: Uri, text: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray()) }
        }.isSuccess
    }

    suspend fun read(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(uri)!!.use { input ->
                // Even a user with a big library is a few MB at most; refuse anything absurd.
                val bytes = input.readBytes()
                if (bytes.size > 64 * 1024 * 1024) null else String(bytes)
            }
        }.getOrNull()
    }

    // ---- export ------------------------------------------------------------------------------

    fun lookJson(context: Context, name: String, look: ThemeConfig): String {
        val o = header("look", name).put("look", Json.theme(look))
        iconJson(context, look.iconFile)?.let { o.put("icon", it) }
        return o.toString(2)
    }

    fun eqJson(name: String, eq: EqState): String = header("eq", name).put("eq", Json.eq(eq)).toString(2)

    fun userJson(context: Context, id: String): String {
        val user = Users.users.value.first { it.id == id }
        val files = Users.filesOf(id)
        val o = header("user", user.name).put("files", JSONObject().apply { files.forEach { (k, v) -> put(k, v) } })
        // The icon pictures this user's looks use, so the looks are complete on another phone.
        val icons = JSONObject()
        for (n in iconNames(files)) iconJson(context, n)?.let { icons.put(n, it) }
        if (icons.length() > 0) o.put("icons", icons)
        return o.toString()
    }

    private fun header(type: String, name: String) = JSONObject().put(KIND, type).put("v", VERSION).put("name", name)

    private fun iconJson(context: Context, name: String?): JSONObject? {
        val f = name?.let { IconFiles.file(context, it) } ?: return null
        if (f.length() > 8 * 1024 * 1024) return null
        return JSONObject().put("name", f.name).put("data", Base64.encodeToString(f.readBytes(), Base64.NO_WRAP))
    }

    private fun iconNames(files: Map<String, String>): Set<String> {
        val out = HashSet<String>()
        fun scan(o: JSONObject?) { o?.optString("iconFile", "")?.takeIf { it.isNotEmpty() }?.let { out.add(it) } }
        files["theme.json"]?.let { runCatching { scan(JSONObject(it)) } }
        files["look_profiles.json"]?.let { t ->
            runCatching {
                val o = JSONObject(t)
                o.optJSONArray("items")?.let { a -> for (i in 0 until a.length()) scan(a.optJSONObject(i)?.optJSONObject("look")) }
                o.optJSONArray("slots")?.let { a -> for (i in 0 until a.length()) scan(a.optJSONObject(i)) }
            }
        }
        return out
    }

    // ---- import ------------------------------------------------------------------------------

    /**
     * Adds what [text] holds: a look profile, an EQ profile or a whole user, whichever it is
     * (so any of the Import buttons takes any of them).
     */
    fun import(context: Context, text: String): Imported {
        val o = runCatching { JSONObject(text) }.getOrNull()
            ?: return Imported.Refused("That file isn't an LToTS profile.")
        val name = o.optString("name", "").trim()
        return when (o.optString(KIND)) {
            "look" -> {
                val look = o.optJSONObject("look")?.let { Json.theme(it) } ?: return Imported.Refused("That look file is damaged.")
                if (ThemeState.profiles.value.size >= ThemeState.MAX_PROFILES)
                    return Imported.Refused("You already have ${ThemeState.MAX_PROFILES} look profiles. Delete one first.")
                val icon = o.optJSONObject("icon")?.let { restoreIcon(context, it) }
                val withIcon = if (look.iconFile != null) look.copy(iconFile = icon ?: look.iconFile.takeIf { IconFiles.file(context, it) != null }) else look
                ThemeState.importProfile(name, withIcon)
                Imported.Look(ThemeState.profiles.value.last().name)
            }
            "eq" -> {
                val eq = o.optJSONObject("eq")?.let { Json.eq(it) } ?: return Imported.Refused("That EQ file is damaged.")
                if (EqProfiles.items.value.size >= EqProfiles.MAX)
                    return Imported.Refused("You already have ${EqProfiles.MAX} EQ profiles. Delete one first.")
                EqProfiles.import(name, eq.copy(bands = eq.bands.map { it.copy(id = EqDefaults.newId()) }))
                Imported.Eq(EqProfiles.items.value.last().name)
            }
            "user" -> {
                if (Users.users.value.size >= Users.MAX)
                    return Imported.Refused("There are already ${Users.MAX} users on this phone. Delete one first.")
                val filesObj = o.optJSONObject("files") ?: return Imported.Refused("That user file is damaged.")
                val files = LinkedHashMap<String, String>()
                for (k in filesObj.keys()) files[k] = filesObj.optString(k)
                // Icon pictures come back under new names; the looks are pointed at them.
                val renamed = HashMap<String, String>()
                o.optJSONObject("icons")?.let { icons ->
                    for (k in icons.keys()) restoreIcon(context, icons.getJSONObject(k))?.let { renamed[k] = it }
                }
                if (renamed.isNotEmpty()) {
                    for (k in listOf("theme.json", "look_profiles.json")) {
                        var t = files[k] ?: continue
                        for ((old, new) in renamed) t = t.replace("\"iconFile\":\"$old\"", "\"iconFile\":\"$new\"")
                        files[k] = t
                    }
                }
                val u = Users.addFrom(name, files) ?: return Imported.Refused("There are already ${Users.MAX} users on this phone.")
                Imported.User(u.name)
            }
            else -> Imported.Refused("That file isn't an LToTS profile.")
        }
    }

    /** Saves an icon picture carried in an exported file; returns its new file name. */
    private fun restoreIcon(context: Context, o: JSONObject): String? = runCatching {
        val bytes = Base64.decode(o.getString("data"), Base64.DEFAULT)
        val ext = o.optString("name").substringAfterLast('.', "img").take(5).filter { it.isLetterOrDigit() }.ifEmpty { "img" }
        val dir = File(context.filesDir, "ltots/icons").apply { mkdirs() }
        val name = "icon-${System.currentTimeMillis()}-${(0..9999).random()}.$ext"
        val f = File(dir, name)
        f.writeBytes(bytes)
        if (IconFiles.decode(f) == null) { f.delete(); null } else name
    }.getOrNull()

    /** A short message for the user about what an import did. */
    fun message(r: Imported): String = when (r) {
        is Imported.Look -> "Look profile \"${r.name}\" added."
        is Imported.Eq -> "EQ profile \"${r.name}\" added."
        is Imported.User -> "User \"${r.name}\" added. Tap them to switch."
        is Imported.Refused -> r.why
    }
}
