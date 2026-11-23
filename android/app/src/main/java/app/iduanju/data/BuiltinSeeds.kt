package app.iduanju.data

import android.content.Context
import app.iduanju.tvbox.TVBoxStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray

/**
 * TV fork builtin defaults (tv.2): first launch ships with working sources so
 * the novel tab and the TVBox tab are not empty out of the box.
 *
 * - Novel: a curated Legado source bundle (assets/builtin_novel_sources.json,
 *   merged from the maintained Pobing + XIU2 collections) is written into the
 *   same "novel" prefs the novel screen reads, only when the user has no
 *   sources of their own yet.
 * - TVBox: the builtin config URL is imported once in the background through
 *   the store's own import path, which live-validates every CMS site and
 *   keeps only the ones that actually answer — dead sites never land.
 * Both are one-shot (flag-guarded) and never overwrite user data.
 */
object BuiltinSeeds {
    /** Lightconer tvbox-ysc-config single-repo aggregate; auto-updated twice daily upstream. */
    const val TVBOX_BUILTIN_URL =
        "https://raw.githubusercontent.com/Lightconer/tvbox-ysc-config/main/output/%E5%8D%95%E4%BB%93%E8%81%9A%E5%90%88.json"

    private const val NOVEL_PREFS = "novel"
    private const val NOVEL_SEED_FLAG = "builtinSeededTv2"
    private const val IDJ_PREFS = "idj"
    private const val TVBOX_SEED_FLAG = "tvboxBuiltinSeededTv2"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun seedNovel(context: Context) {
        runCatching {
            val prefs = context.getSharedPreferences(NOVEL_PREFS, Context.MODE_PRIVATE)
            if (prefs.getBoolean(NOVEL_SEED_FLAG, false)) return
            val current = prefs.getString("sources", null)?.trim().orEmpty()
            if (current.isEmpty() || current == "[]") {
                val bundle = context.assets.open("builtin_novel_sources.json")
                    .bufferedReader(Charsets.UTF_8).use { it.readText() }
                val firstId = JSONArray(bundle).optJSONObject(0)?.optString("bookSourceUrl").orEmpty()
                prefs.edit()
                    .putString("sources", bundle)
                    .apply {
                        if (firstId.isNotBlank() && prefs.getString("rankSource", null).isNullOrBlank()) {
                            putString("rankSource", firstId)
                        }
                    }
                    .putBoolean(NOVEL_SEED_FLAG, true)
                    .apply()
            } else {
                prefs.edit().putBoolean(NOVEL_SEED_FLAG, true).apply()
            }
        }
    }

    fun seedTvBox(context: Context, store: TVBoxStore) {
        val prefs = context.getSharedPreferences(IDJ_PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(TVBOX_SEED_FLAG, false)) return
        if (store.repos.isNotEmpty()) {
            prefs.edit().putBoolean(TVBOX_SEED_FLAG, true).apply()
            return
        }
        scope.launch {
            runCatching { store.importFromUrl(TVBOX_BUILTIN_URL) }
                .onSuccess { prefs.edit().putBoolean(TVBOX_SEED_FLAG, true).apply() }
            // On failure leave the flag unset: retried on next launch.
        }
    }
}
