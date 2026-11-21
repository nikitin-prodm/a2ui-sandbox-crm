package dev.a2uicrm.app

import android.content.Context
import android.content.SharedPreferences

/**
 * 5.4i: user/agent-renamed SECTION TITLES — pure DATA (SharedPreferences), consumed by the
 * static UI (bottom nav labels, native screen titles, the A2UI screen header). The agent
 * renames via the validated `{"shell":{"tabTitles":{...}}}` line; nothing executable is
 * involved — the sandbox "validated data, not code" principle is preserved.
 *
 * Reset demo data (Settings) clears the names back to the defaults.
 */
internal class TabShellPrefs(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("a2ui_shell", Context.MODE_PRIVATE)

    /** The effective title: the custom one, or the Tab default. */
    fun title(tab: Tab): String =
        prefs.getString(KEY_PREFIX + tab.name, null)?.takeIf { it.isNotBlank() } ?: tab.title

    fun loadAll(): Map<Tab, String> = Tab.entries.filter { hasCustom(it) }.associate { it to title(it) }

    fun hasCustom(tab: Tab): Boolean = !prefs.getString(KEY_PREFIX + tab.name, null).isNullOrBlank()

    /** Validated write: blank resets to the default; non-printable/too-long values are rejected. */
    fun setTitle(tab: Tab, value: String?): Boolean {
        val v = value?.trim().orEmpty()
        if (v.isNotEmpty() && (v.length > MAX_LEN || v.any { it.code < 32 })) return false
        prefs.edit().putString(KEY_PREFIX + tab.name, v.ifEmpty { null }).apply()
        return true
    }

    // ---------- 5.5: agent-editable tab ICONS (validated against the icon vocabulary) ----------

    /** The ONLY tokens the agent may use — the SAME list the A2UI renderer resolves
     *  (single source of truth: A2uiRendererRegistry.ICONS). */
    val iconVocabulary: Set<String> = dev.a2uicrm.agentic.render.A2uiRendererRegistry.ICONS.keys

    /** The custom icon token for the tab, or null (caller falls back to the default icon). */
    fun iconOf(tab: Tab): String? =
        prefs.getString(ICON_KEY_PREFIX + tab.name, null)?.takeIf { it in iconVocabulary }

    fun loadAllIcons(): Map<Tab, String> = Tab.entries.mapNotNull { t -> iconOf(t)?.let { t to it } }.toMap()

    /** Validated write: the token MUST be in the vocabulary (unknown tokens are rejected). */
    fun setIcon(tab: Tab, token: String): Boolean {
        val v = token.trim()
        if (v !in iconVocabulary) return false
        prefs.edit().putString(ICON_KEY_PREFIX + tab.name, v).apply()
        return true
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY_PREFIX = "title_"
        const val ICON_KEY_PREFIX = "icon_"
        const val MAX_LEN = 24
    }
}
