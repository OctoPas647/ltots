package com.ltots.app.data

import com.ltots.app.audio.EqState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** One saved EQ and its name. */
data class EqProfile(val name: String, val eq: EqState)

/**
 * The EQ profiles of the user in use (up to [MAX]): saved EQs you can load into the EQ you're
 * editing. Shared by the EQ tab's menu and the Profile tab.
 */
object EqProfiles {
    const val MAX = 10

    private val _items = MutableStateFlow<List<EqProfile>>(emptyList())
    val items: StateFlow<List<EqProfile>> = _items
    /** The profile picked or saved last (null = none), so only that one can show as in use. */
    private val _active = MutableStateFlow<Int?>(null)
    val active: StateFlow<Int?> = _active

    /** Loads the profiles of the user in use (at start, and after switching user). */
    fun load() {
        val (active, list) = Store.loadEqProfiles()
        _items.value = list.take(MAX).map { EqProfile(it.first, it.second) }
        _active.value = active?.takeIf { it in _items.value.indices }
    }

    private fun save() = Store.saveEqProfiles(_items.value.map { it.name to it.eq }, _active.value)

    fun nextName(): String =
        (1..MAX + 1).map { "Custom $it" }.first { n -> _items.value.none { it.name.equals(n, ignoreCase = true) } }

    private fun freeName(wanted: String, except: Int = -1): String {
        val taken = _items.value.filterIndexed { i, _ -> i != except }.map { it.name.lowercase() }.toSet()
        val base = wanted.trim().take(30).ifBlank { nextName() }
        if (base.lowercase() !in taken) return base
        var n = 2
        while ("$base ($n)".lowercase() in taken) n++
        return "$base ($n)"
    }

    /** Saves [eq] as a new profile (it becomes the one in use); null when there are already [MAX]. */
    fun addCurrent(eq: EqState): Int? {
        if (_items.value.size >= MAX) return null
        _items.value = _items.value + EqProfile(nextName(), eq)
        _active.value = _items.value.size - 1
        save()
        return _active.value
    }

    /** Keeps [eq] in profile [i] (replacing what it held). */
    fun overwrite(i: Int, eq: EqState) {
        if (i !in _items.value.indices) return
        _items.value = _items.value.toMutableList().also { it[i] = it[i].copy(eq = eq) }
        _active.value = i
        save()
    }

    /** Marks [i] as the one in use and returns its EQ, to be loaded into the EQ being edited. */
    fun pick(i: Int): EqState? {
        val p = _items.value.getOrNull(i) ?: return null
        _active.value = i
        save()
        return p.eq
    }

    fun rename(i: Int, name: String) {
        if (i !in _items.value.indices) return
        val n = freeName(name, except = i)
        _items.value = _items.value.toMutableList().also { it[i] = it[i].copy(name = n) }
        save()
    }

    fun delete(i: Int) {
        if (i !in _items.value.indices) return
        _items.value = _items.value.toMutableList().also { it.removeAt(i) }
        _active.value = _active.value?.let { a -> when { a == i -> null; a > i -> a - 1; else -> a } }
        save()
    }

    /** Adds [eq] from an exported file; false when there are already [MAX]. */
    fun import(name: String, eq: EqState): Boolean {
        if (_items.value.size >= MAX) return false
        _items.value = _items.value + EqProfile(freeName(name), eq)
        save()
        return true
    }
}
