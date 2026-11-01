package io.csync.app

import android.content.Context
import android.content.SharedPreferences
import android.icu.text.PluralRules
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.util.Locale

/** Messages cross worker/Binder boundaries as keys and arguments, never translated state. */
fun msg(key: String, vararg args: Pair<String, Any?>): String =
    JSONObject().put("csyncKey", key).put("args", JSONObject(args.toMap())).toString()

fun tr(key: String, vararg args: Pair<String, Any?>): String = I18n.render(msg(key, *args))

fun localizedFailure(error: Throwable, fallback: String): String {
    // TLS may wrap a localized certificate/pairing exception in several platform exceptions.
    for (cause in generateSequence(error) { it.cause }.take(16)) {
        val value = cause.message ?: continue
        if (runCatching { JSONObject(value).optString("csyncKey").isNotEmpty() }.getOrDefault(false)) return value
    }
    return msg("error.details", "summary" to msg(fallback), "error" to (error.message ?: error.javaClass.simpleName))
}

class TranslationCatalog(private val languages: JSONObject) {
    val options: List<Pair<String, String>> = languages.keys().asSequence().sorted().map {
        it to languages.getJSONObject(it).getJSONObject("_meta").getString("nativeName")
    }.toList()

    fun resolve(selected: String, system: Locale): String {
        if (selected != "system") return selected
        val tag = system.toLanguageTag()
        if (languages.has(tag)) return tag
        val script = system.script.ifEmpty {
            if (system.language == "zh") (if (system.country in listOf("TW", "HK", "MO")) "Hant" else "Hans") else ""
        }
        val candidates = listOf("${system.language}-$script", system.language)
        return candidates.firstOrNull { languages.has(it) }
            ?: if (system.language == "en" && languages.has("en-US")) "en-US" else "zh-Hans"
    }

    fun direction(locale: String): String = languages.optJSONObject(locale)?.optJSONObject("_meta")?.optString("direction", "ltr") ?: "ltr"

    fun render(value: String, locale: String, depth: Int = 0): String {
        if (depth > 8) return value
        val message = runCatching { JSONObject(value) }.getOrNull() ?: return value
        val key = message.optString("csyncKey")
        if (key.isEmpty()) return value
        val args = message.optJSONObject("args") ?: JSONObject()
        val entry = languages.optJSONObject(locale)?.optJSONObject("messages")?.opt(key)
        val template = when (entry) {
            is String -> entry.takeIf { it.isNotEmpty() }
            is JSONObject -> {
                val count = args.optDouble("count", Double.NaN)
                val category = if (count.isNaN()) "other" else PluralRules.forLocale(Locale.forLanguageTag(locale)).select(count)
                entry.optString(category).ifEmpty { entry.optString("other") }.takeIf { it.isNotEmpty() }
            }
            else -> null
        } ?: return key
        // A single replacement pass: argument text may itself contain braces or dollar signs.
        return PARAMETER.replace(template) { match ->
            val name = match.groupValues[1]
            if (args.has(name)) render(args.get(name).toString(), locale, depth + 1) else match.value
        }
    }

    private companion object {
        // Android's ICU regex parser requires both literal braces to be escaped.
        val PARAMETER = Regex("\\{([A-Za-z][A-Za-z0-9_]*)\\}")
    }
}

object I18n {
    // Every composable that translates text observes this state, including skippable dialogs.
    private var generation by mutableIntStateOf(0)
    fun invalidate() { generation++ }
    private var context: Context? = null
    private var prefs: SharedPreferences? = null
    private var catalog = TranslationCatalog(JSONObject())
    @Synchronized fun initialize(value: Context) {
        if (context != null) return
        val application = value.applicationContext
        catalog = TranslationCatalog(JSONObject(application.assets.open("csync-locales.json").bufferedReader().use { it.readText() }))
        prefs = application.getSharedPreferences("settings", Context.MODE_PRIVATE)
        context = application
    }
    val options get() = catalog.options
    var selected: String
        get() {
            @Suppress("UNUSED_VARIABLE") val current = generation
            return prefs?.getString("language", "system") ?: "system"
        }
        set(value) {
            require(value == "system" || options.any { it.first == value })
            check(prefs?.edit()?.putString("language", value)?.commit() == true)
        }
    private val locale get() = catalog.resolve(selected, context?.resources?.configuration?.locales?.get(0) ?: Locale.getDefault())
    val isRtl get() = catalog.direction(locale) == "rtl"
    fun render(value: String): String = catalog.render(value, locale)
}

/** Transient transport state is independent of the status sentence and survives no process death. */
object SyncRuntime {
    // A repeated connection can leave every preference unchanged. Compose must
    // observe the live handshake state itself, including transitions from IO.
    var connected by mutableStateOf(false)
}
