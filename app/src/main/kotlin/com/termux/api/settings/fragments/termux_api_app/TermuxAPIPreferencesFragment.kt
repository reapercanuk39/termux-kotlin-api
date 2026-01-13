package com.termux.api.settings.fragments.termux_api_app

import android.content.Context
import android.os.Bundle
import androidx.annotation.Keep
import androidx.preference.PreferenceDataStore
import androidx.preference.PreferenceFragmentCompat
import com.termux.api.R
import com.termux.shared.termux.settings.preferences.TermuxAPIAppSharedPreferences

@Keep
class TermuxAPIPreferencesFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val context = context ?: return

        preferenceManager.preferenceDataStore = TermuxAPIPreferencesDataStore.getInstance(context)

        setPreferencesFromResource(R.xml.prefs__termux_api_app___prefs__app, rootKey)
    }
}

class TermuxAPIPreferencesDataStore private constructor(context: Context) : PreferenceDataStore() {

    @Suppress("unused")
    private val context: Context = context
    @Suppress("unused")
    private val preferences: TermuxAPIAppSharedPreferences? = TermuxAPIAppSharedPreferences.build(context, true)

    companion object {
        @Volatile
        private var instance: TermuxAPIPreferencesDataStore? = null

        @JvmStatic
        @Synchronized
        fun getInstance(context: Context): TermuxAPIPreferencesDataStore {
            return instance ?: TermuxAPIPreferencesDataStore(context).also { instance = it }
        }
    }
}
