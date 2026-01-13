package com.termux.api.settings.fragments.termux_api_app.debugging

import android.content.Context
import android.os.Bundle
import androidx.annotation.Keep
import androidx.preference.ListPreference
import androidx.preference.PreferenceDataStore
import androidx.preference.PreferenceFragmentCompat
import com.termux.api.R
import com.termux.shared.logger.Logger
import com.termux.shared.termux.settings.preferences.TermuxAPIAppSharedPreferences

@Keep
class DebuggingPreferencesFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val context = context ?: return

        preferenceManager.preferenceDataStore = DebuggingPreferencesDataStore.getInstance(context)

        setPreferencesFromResource(R.xml.prefs__termux_api_app___prefs__app___prefs__debugging, rootKey)

        configureLoggingPreferences(context)
    }

    private fun configureLoggingPreferences(context: Context) {
        val loggingCategory = findPreference<androidx.preference.PreferenceCategory>("logging") ?: return

        val logLevelListPreference = findPreference<ListPreference>("log_level")
        if (logLevelListPreference != null) {
            val preferences = TermuxAPIAppSharedPreferences.build(context, true) ?: return

            setLogLevelListPreferenceData(logLevelListPreference, context, preferences.getLogLevel(true))
            loggingCategory.addPreference(logLevelListPreference)
        }
    }

    companion object {
        @JvmStatic
        fun setLogLevelListPreferenceData(
            logLevelListPreference: ListPreference?,
            context: Context,
            logLevel: Int
        ): ListPreference {
            val preference = logLevelListPreference ?: ListPreference(context)

            val logLevels = Logger.getLogLevelsArray()
            val logLevelLabels = Logger.getLogLevelLabelsArray(context, logLevels, true)

            preference.entryValues = logLevels
            preference.entries = logLevelLabels

            preference.value = logLevel.toString()
            preference.setDefaultValue(Logger.DEFAULT_LOG_LEVEL)

            return preference
        }
    }
}

class DebuggingPreferencesDataStore private constructor(private val context: Context) : PreferenceDataStore() {

    private val preferences: TermuxAPIAppSharedPreferences? = TermuxAPIAppSharedPreferences.build(context, true)

    companion object {
        @Volatile
        private var instance: DebuggingPreferencesDataStore? = null

        @JvmStatic
        @Synchronized
        fun getInstance(context: Context): DebuggingPreferencesDataStore {
            return instance ?: DebuggingPreferencesDataStore(context).also { instance = it }
        }
    }

    override fun getString(key: String?, defValue: String?): String? {
        if (preferences == null || key == null) return null

        return when (key) {
            "log_level" -> preferences.getLogLevel(true).toString()
            else -> null
        }
    }

    override fun putString(key: String?, value: String?) {
        if (preferences == null || key == null) return

        when (key) {
            "log_level" -> {
                value?.let {
                    preferences.setLogLevel(context, it.toInt(), true)
                }
            }
        }
    }
}
