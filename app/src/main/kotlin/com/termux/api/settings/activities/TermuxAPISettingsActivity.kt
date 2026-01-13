package com.termux.api.settings.activities

import android.content.Context
import android.os.Bundle
import android.os.Environment
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.termux.api.R
import com.termux.shared.activities.ReportActivity
import com.termux.shared.activity.media.AppCompatActivityUtils
import com.termux.shared.android.AndroidUtils
import com.termux.shared.android.PackageUtils
import com.termux.shared.file.FileUtils
import com.termux.shared.interact.ShareUtils
import com.termux.shared.models.ReportInfo
import com.termux.shared.termux.TermuxConstants
import com.termux.shared.termux.TermuxUtils
import com.termux.shared.termux.settings.preferences.TermuxAPIAppSharedPreferences
import com.termux.shared.theme.NightMode

class TermuxAPISettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        AppCompatActivityUtils.setNightMode(this, NightMode.getAppNightMode().name, true)

        setContentView(R.layout.activity_termux_api_settings)
        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings, RootPreferencesFragment())
                .commit()
        }

        AppCompatActivityUtils.setToolbar(this, com.termux.shared.R.id.toolbar)
        AppCompatActivityUtils.setShowBackButtonInActionBar(this, true)
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }

    class RootPreferencesFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            val context = context ?: return

            setPreferencesFromResource(R.xml.sets__termux, rootKey)

            Thread {
                configureTermuxAPIPreference(context)
                configureAboutPreference(context)
                configureDonatePreference(context)
            }.start()
        }

        private fun configureTermuxAPIPreference(context: Context) {
            val termuxAPIPreference = findPreference<Preference>("sets__termux_api_app")
            if (termuxAPIPreference != null) {
                val preferences = TermuxAPIAppSharedPreferences.build(context, false)
                // If failed to get app preferences, then likely app is not installed, so do not show its preference
                termuxAPIPreference.isVisible = preferences != null
            }
        }

        private fun configureAboutPreference(context: Context) {
            val aboutPreference = findPreference<Preference>("link__termux_about")
            aboutPreference?.setOnPreferenceClickListener {
                Thread {
                    val title = "About"

                    val aboutString = StringBuilder().apply {
                        append(TermuxUtils.getAppInfoMarkdownString(context, TermuxUtils.AppInfoMode.TERMUX_AND_PLUGIN_PACKAGE))
                        append("\n\n").append(AndroidUtils.getDeviceInfoMarkdownString(context, true))
                        append("\n\n").append(TermuxUtils.getImportantLinksMarkdownString(context))
                    }

                    val reportInfo = ReportInfo(
                        title,
                        TermuxConstants.TERMUX_API_APP.TERMUX_API_MAIN_ACTIVITY_NAME,
                        title
                    ).apply {
                        setReportString(aboutString.toString())
                        setReportSaveFileLabelAndPath(
                            title,
                            Environment.getExternalStorageDirectory().toString() + "/" +
                                FileUtils.sanitizeFileName(
                                    TermuxConstants.TERMUX_API_APP_NAME.replace(":", "") +
                                        "-" + title + ".log", true, true
                                )
                        )
                    }

                    ReportActivity.startReportActivity(context, reportInfo)
                }.start()

                true
            }
        }

        private fun configureDonatePreference(context: Context) {
            val donatePreference = findPreference<Preference>("link__termux_donate") ?: return

            val signingCertificateSHA256Digest = PackageUtils.getSigningCertificateSHA256DigestForPackage(context)
            if (signingCertificateSHA256Digest != null) {
                // If APK is a Google Playstore release, then do not show the donation link
                // since Termux isn't exempted from the playstore policy donation links restriction
                // Check Fund solicitations: https://pay.google.com/intl/en_in/about/policy/
                val apkRelease = TermuxUtils.getAPKRelease(signingCertificateSHA256Digest)
                if (apkRelease == null || apkRelease == TermuxConstants.APK_RELEASE_GOOGLE_PLAYSTORE_SIGNING_CERTIFICATE_SHA256_DIGEST) {
                    donatePreference.isVisible = false
                    return
                } else {
                    donatePreference.isVisible = true
                }
            }

            donatePreference.setOnPreferenceClickListener {
                ShareUtils.openUrl(context, TermuxConstants.TERMUX_DONATE_URL)
                true
            }
        }
    }
}
