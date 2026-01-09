package app.aaps.plugins.aps.openAPSFCL.vnext.learning

import app.aaps.core.keys.Preferences
import app.aaps.core.keys.StringKey
import org.json.JSONObject

/**
 * Persistent storage for learning parameter adjustments.
 *
 * Versioned & backward compatible.
 */
class FCLvNextLearningStore(
    private val preferences: Preferences
) {

    companion object {
        private const val SCHEMA_VERSION = 1
    }

    private val key = StringKey.fcl_vnext_learning_parameters_json

    private val states: MutableMap<LearningParameter, LearningParameterState> =
        mutableMapOf()

    // ─────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────

    fun load() {
        val raw = preferences.get(key)
        if (raw.isNullOrBlank()) return

        runCatching {
            decode(raw)
        }.onFailure {
            // Fail-safe: corrupted JSON → ignore learning state
            states.clear()
        }
    }

    fun save() {
        val raw = encode()
        preferences.put(key, raw)
    }

    // ─────────────────────────────────────────────
    // Access
    // ─────────────────────────────────────────────

    fun get(parameter: LearningParameter): LearningParameterState? =
        states[parameter]

    fun set(
        parameter: LearningParameter,
        value: Double?,
        evidenceCount: Int,
        timestampMillis: Long
    ) {
        states[parameter] =
            LearningParameterState(
                value = value,
                evidenceCount = evidenceCount,
                lastUpdatedMillis = timestampMillis
            )
    }

    fun all(): Map<LearningParameter, LearningParameterState> =
        states.toMap()

    fun clear(parameter: LearningParameter) {
        states.remove(parameter)
    }

    // ─────────────────────────────────────────────
    // JSON (versioned)
    // ─────────────────────────────────────────────

    private fun encode(): String {
        val root = JSONObject()
        root.put("schemaVersion", SCHEMA_VERSION)

        val params = JSONObject()
        for ((param, state) in states) {
            val o = JSONObject()
            if (state.value != null) {
                o.put("value", state.value)
            } else {
                o.put("value", JSONObject.NULL)
            }
            o.put("evidenceCount", state.evidenceCount)
            o.put("lastUpdatedMillis", state.lastUpdatedMillis)

            params.put(param.name, o)
        }

        root.put("parameters", params)
        return root.toString()
    }

    private fun decode(raw: String) {
        val root = JSONObject(raw)
        val version = root.optInt("schemaVersion", 1)
        if (version != SCHEMA_VERSION) return

        val params = root.optJSONObject("parameters") ?: return

        val keys = params.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            runCatching {
                val param = LearningParameter.valueOf(k)
                val o = params.getJSONObject(k)

                val value =
                    if (o.isNull("value")) null else o.getDouble("value")

                val evidence = o.optInt("evidenceCount", 0)
                val ts = o.optLong("lastUpdatedMillis", 0L)

                states[param] =
                    LearningParameterState(
                        value = value,
                        evidenceCount = evidence,
                        lastUpdatedMillis = ts
                    )
            }
        }
    }
}
