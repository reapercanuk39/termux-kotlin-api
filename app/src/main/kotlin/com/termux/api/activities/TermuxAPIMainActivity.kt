package com.termux.api.activities

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Button
import android.widget.TextView

import androidx.appcompat.app.AppCompatActivity

import com.termux.api.R
import com.termux.api.TermuxAPIApplication
import com.termux.api.settings.activities.TermuxAPISettingsActivity
import com.termux.api.util.ViewUtils
import com.termux.shared.activity.ActivityUtils
import com.termux.shared.activity.media.AppCompatActivityUtils
import com.termux.shared.android.PackageUtils
import com.termux.shared.android.PermissionUtils
import com.termux.shared.data.IntentUtils
import com.termux.shared.logger.Logger
import com.termux.shared.markdown.MarkdownUtils
import com.termux.shared.termux.TermuxConstants
import com.termux.shared.termux.theme.TermuxThemeUtils
import com.termux.shared.theme.NightMode

class TermuxAPIMainActivity : AppCompatActivity() {

    private var mBatteryOptimizationNotDisabledWarning: TextView? = null
    private var mDisplayOverOtherAppsPermissionNotGrantedWarning: TextView? = null

    private var mDisableBatteryOptimization: Button? = null
    private var mGrantDisplayOverOtherAppsPermission: Button? = null

    companion object {
        const val LOG_TAG = "TermuxAPIMainActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Logger.logDebug(LOG_TAG, "onCreate")

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_termux_api_main)

        // Set NightMode.APP_NIGHT_MODE
        TermuxThemeUtils.setAppNightMode(this)
        AppCompatActivityUtils.setNightMode(this, NightMode.getAppNightMode().name, true)

        AppCompatActivityUtils.setToolbar(this, com.termux.shared.R.id.toolbar)
        AppCompatActivityUtils.setToolbarTitle(this, com.termux.shared.R.id.toolbar, TermuxConstants.TERMUX_API_APP_NAME, 0)

        val pluginInfo = findViewById<TextView>(R.id.textview_plugin_info)
        pluginInfo.text = getString(
            R.string.plugin_info,
            TermuxConstants.TERMUX_GITHUB_REPO_URL,
            TermuxConstants.TERMUX_API_GITHUB_REPO_URL,
            TermuxConstants.TERMUX_API_APT_PACKAGE_NAME,
            TermuxConstants.TERMUX_API_APT_GITHUB_REPO_URL
        )

        mBatteryOptimizationNotDisabledWarning = findViewById(R.id.textview_battery_optimization_not_disabled_warning)
        mDisableBatteryOptimization = findViewById(R.id.btn_disable_battery_optimizations)
        mDisableBatteryOptimization?.setOnClickListener { requestDisableBatteryOptimizations() }

        mDisplayOverOtherAppsPermissionNotGrantedWarning = findViewById(R.id.textview_display_over_other_apps_not_granted_warning)
        mGrantDisplayOverOtherAppsPermission = findViewById(R.id.button_grant_display_over_other_apps_permission)
        mGrantDisplayOverOtherAppsPermission?.setOnClickListener { requestDisplayOverOtherAppsPermission() }
    }

    override fun onResume() {
        super.onResume()

        // Set log level for the app
        TermuxAPIApplication.setLogConfig(this, false)

        Logger.logVerbose(LOG_TAG, "onResume")

        checkIfBatteryOptimizationNotDisabled()
        checkIfDisplayOverOtherAppsPermissionNotGranted()
        setChangeLauncherActivityStateViews()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        super.onCreateOptionsMenu(menu)
        menuInflater.inflate(R.menu.activity_termux_api_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_settings -> {
                openSettings()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun checkIfBatteryOptimizationNotDisabled() {
        val warningView = mBatteryOptimizationNotDisabledWarning ?: return
        val button = mDisableBatteryOptimization ?: return

        // If battery optimizations not disabled
        if (!PermissionUtils.checkIfBatteryOptimizationsDisabled(this)) {
            ViewUtils.setWarningTextViewAndButtonState(
                this, warningView, button, true,
                getString(R.string.action_disable_battery_optimizations)
            )
        } else {
            ViewUtils.setWarningTextViewAndButtonState(
                this, warningView, button, false,
                getString(R.string.action_already_disabled)
            )
        }
    }

    private fun requestDisableBatteryOptimizations() {
        Logger.logDebug(LOG_TAG, "Requesting to disable battery optimizations")
        PermissionUtils.requestDisableBatteryOptimizations(this, PermissionUtils.REQUEST_DISABLE_BATTERY_OPTIMIZATIONS)
    }

    private fun checkIfDisplayOverOtherAppsPermissionNotGranted() {
        val warningView = mDisplayOverOtherAppsPermissionNotGrantedWarning ?: return
        val button = mGrantDisplayOverOtherAppsPermission ?: return

        // If display over other apps permission not granted
        if (!PermissionUtils.checkDisplayOverOtherAppsPermission(this)) {
            ViewUtils.setWarningTextViewAndButtonState(
                this, warningView, button, true,
                getString(R.string.action_grant_display_over_other_apps_permission)
            )
        } else {
            ViewUtils.setWarningTextViewAndButtonState(
                this, warningView, button, false,
                getString(R.string.action_already_granted)
            )
        }
    }

    private fun requestDisplayOverOtherAppsPermission() {
        Logger.logDebug(LOG_TAG, "Requesting to grant display over other apps permission")
        PermissionUtils.requestDisplayOverOtherAppsPermission(this, PermissionUtils.REQUEST_GRANT_DISPLAY_OVER_OTHER_APPS_PERMISSION)
    }

    private fun setChangeLauncherActivityStateViews() {
        val packageName = TermuxConstants.TERMUX_API_PACKAGE_NAME
        val className = TermuxConstants.TERMUX_API_APP.TERMUX_API_LAUNCHER_ACTIVITY_NAME

        val changeLauncherActivityStateTextView = findViewById<TextView>(R.id.textview_change_launcher_activity_state_details)
        changeLauncherActivityStateTextView.text = MarkdownUtils.getSpannedMarkdownText(
            this,
            getString(R.string.msg_change_launcher_activity_state_info, packageName, javaClass.name)
        )

        val changeLauncherActivityStateButton = findViewById<Button>(R.id.button_change_launcher_activity_state)

        val currentlyDisabled = PackageUtils.isComponentDisabled(this, packageName, className, false)
        if (currentlyDisabled == null) {
            Logger.logError(LOG_TAG, "Failed to check if \"$packageName/$className\" launcher activity is disabled")
            changeLauncherActivityStateButton.isEnabled = false
            changeLauncherActivityStateButton.alpha = .5f
            changeLauncherActivityStateButton.setText(com.termux.shared.R.string.action_disable_launcher_icon)
            changeLauncherActivityStateButton.setOnClickListener(null)
            return
        }

        changeLauncherActivityStateButton.isEnabled = true
        changeLauncherActivityStateButton.alpha = 1f

        val stateChangeMessage: String
        val newState: Boolean
        if (currentlyDisabled) {
            changeLauncherActivityStateButton.setText(com.termux.shared.R.string.action_enable_launcher_icon)
            stateChangeMessage = getString(com.termux.shared.R.string.msg_enabling_launcher_icon, TermuxConstants.TERMUX_API_APP_NAME)
            newState = true
        } else {
            changeLauncherActivityStateButton.setText(com.termux.shared.R.string.action_disable_launcher_icon)
            stateChangeMessage = getString(com.termux.shared.R.string.msg_disabling_launcher_icon, TermuxConstants.TERMUX_API_APP_NAME)
            newState = false
        }

        changeLauncherActivityStateButton.setOnClickListener {
            Logger.logInfo(LOG_TAG, stateChangeMessage)
            val errmsg = PackageUtils.setComponentState(
                this, packageName, className, newState, stateChangeMessage, true
            )
            if (errmsg == null) {
                setChangeLauncherActivityStateViews()
            } else {
                Logger.logError(LOG_TAG, errmsg)
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        Logger.logVerbose(LOG_TAG, "onActivityResult: requestCode: $requestCode, resultCode: $resultCode, data: ${IntentUtils.getIntentString(data)}")

        when (requestCode) {
            PermissionUtils.REQUEST_DISABLE_BATTERY_OPTIMIZATIONS -> {
                if (PermissionUtils.checkIfBatteryOptimizationsDisabled(this)) {
                    Logger.logDebug(LOG_TAG, "Battery optimizations disabled by user on request.")
                } else {
                    Logger.logDebug(LOG_TAG, "Battery optimizations not disabled by user on request.")
                }
            }
            PermissionUtils.REQUEST_GRANT_DISPLAY_OVER_OTHER_APPS_PERMISSION -> {
                if (PermissionUtils.checkDisplayOverOtherAppsPermission(this)) {
                    Logger.logDebug(LOG_TAG, "Display over other apps granted by user on request.")
                } else {
                    Logger.logDebug(LOG_TAG, "Display over other apps denied by user on request.")
                }
            }
            else -> Logger.logError(LOG_TAG, "Unknown request code \"$requestCode\" passed to onRequestPermissionsResult")
        }
    }

    private fun openSettings() {
        ActivityUtils.startActivity(this, Intent().setClass(this, TermuxAPISettingsActivity::class.java))
    }
}
