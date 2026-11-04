package com.rbmr.taigasleep.analysis

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * How the app asks for analyses. Automatic analysis requests the first compatible algorithm in
 * [preferenceOrder] after an upload. Manual requests always ignore [auto].
 */
data class AnalysisSettings(
    val auto: Boolean = true,
    val preferenceOrder: List<String> = StagerPreference,
)

/** A stored order is a comma-separated list of algorithm ids; an empty list means the default. */
fun parsePreferenceOrder(raw: String?): List<String> =
    raw
        ?.split(',')
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?.distinct()
        ?.takeIf { it.isNotEmpty() }
        ?: StagerPreference

/**
 * The order shown and used with the installed algorithms: the saved order first, then any
 * installed algorithm the saved order does not mention yet.
 */
fun effectiveOrder(stored: List<String>, installed: Collection<String>): List<String> =
    stored.filter { it in installed } + installed.filter { it !in stored }

/** Move one algorithm one step up or down, keeping the list stable at the ends. */
fun movePreference(order: List<String>, id: String, delta: Int): List<String> {
  val index = order.indexOf(id)
  if (index < 0) return order
  val target = (index + delta).coerceIn(0, order.lastIndex)
  if (target == index) return order
  return order.toMutableList().also { it.add(target, it.removeAt(index)) }
}

class AnalysisSettingsStore private constructor(context: Context) {
  private val prefs =
      context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

  private val _settings = MutableStateFlow(load())
  val settings: StateFlow<AnalysisSettings> = _settings.asStateFlow()

  fun setAuto(enabled: Boolean) {
    prefs.edit().putBoolean(KEY_AUTO, enabled).apply()
    _settings.value = _settings.value.copy(auto = enabled)
  }

  fun setPreferenceOrder(ids: List<String>) {
    val clean = ids.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    val stored = clean.ifEmpty { StagerPreference }
    prefs.edit().putString(KEY_ORDER, stored.joinToString(",")).apply()
    _settings.value = _settings.value.copy(preferenceOrder = stored)
  }

  private fun load(): AnalysisSettings =
      AnalysisSettings(
          auto = prefs.getBoolean(KEY_AUTO, true),
          preferenceOrder = parsePreferenceOrder(prefs.getString(KEY_ORDER, null)),
      )

  companion object {
    private const val KEY_AUTO = "analysis_auto"
    private const val KEY_ORDER = "analysis_preference_order"

    @Volatile private var instance: AnalysisSettingsStore? = null

    fun of(context: Context): AnalysisSettingsStore =
        instance
            ?: synchronized(this) {
              instance ?: AnalysisSettingsStore(context).also { instance = it }
            }
  }
}
