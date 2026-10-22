package com.liuying.app.data.account

import android.content.SharedPreferences

/** SharedPreferences-backed [SessionStore]. The cookie is an opaque random token; it is only sent to the account host. */
class PrefsSessionStore(private val prefs: SharedPreferences) : SessionStore {
    override var sid: String?
        get() = prefs.getString("sid", null)
        set(v) { prefs.edit().apply { if (v == null) remove("sid") else putString("sid", v) }.apply() }
    override var username: String?
        get() = prefs.getString("username", null)
        set(v) { prefs.edit().apply { if (v == null) remove("username") else putString("username", v) }.apply() }

    override fun base(kind: String): Set<String> = prefs.getStringSet("base_$kind", emptySet())?.toSet().orEmpty()
    override fun setBase(kind: String, values: Set<String>) { prefs.edit().putStringSet("base_$kind", values).apply() }
    override fun clear() { prefs.edit().clear().apply() }
}
