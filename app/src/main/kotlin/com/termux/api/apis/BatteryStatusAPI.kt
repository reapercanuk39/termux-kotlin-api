package com.termux.api.apis

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.util.JsonWriter

import com.termux.api.TermuxApiReceiver
import com.termux.api.util.JsonUtils.putBooleanValueIfSet
import com.termux.api.util.JsonUtils.putDoubleIfSet
import com.termux.api.util.JsonUtils.putIntegerIfSet
import com.termux.api.util.JsonUtils.putLongIfSet
import com.termux.api.util.JsonUtils.putStringIfSet
import com.termux.api.util.ResultReturner
import com.termux.shared.logger.Logger

import kotlin.math.abs
import kotlin.math.round

object BatteryStatusAPI {

    private const val LOG_TAG = "BatteryStatusAPI"

    private var targetSdkVersion: Int = 0

    @JvmStatic
    fun onReceive(apiReceiver: TermuxApiReceiver, context: Context, intent: Intent) {
        Logger.logDebug(LOG_TAG, "onReceive")

        targetSdkVersion = context.applicationContext.applicationInfo.targetSdkVersion

        ResultReturner.returnData(apiReceiver, intent, object : ResultReturner.ResultJsonWriter() {
            @SuppressLint("DefaultLocale")
            override fun writeJson(out: JsonWriter) {
                val batteryStatus = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                    ?: Intent()

                val batteryLevel = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val batteryScale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1)

                val health = batteryStatus.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)
                val batteryHealth = when (health) {
                    BatteryManager.BATTERY_HEALTH_COLD -> "COLD"
                    BatteryManager.BATTERY_HEALTH_DEAD -> "DEAD"
                    BatteryManager.BATTERY_HEALTH_GOOD -> "GOOD"
                    BatteryManager.BATTERY_HEALTH_OVERHEAT -> "OVERHEAT"
                    BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "OVER_VOLTAGE"
                    BatteryManager.BATTERY_HEALTH_UNKNOWN -> "UNKNOWN"
                    BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "UNSPECIFIED_FAILURE"
                    else -> health.toString()
                }

                val pluggedInt = batteryStatus.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
                val batteryPlugged = when (pluggedInt) {
                    0 -> "UNPLUGGED"
                    BatteryManager.BATTERY_PLUGGED_AC -> "PLUGGED_AC"
                    BatteryManager.BATTERY_PLUGGED_DOCK -> "PLUGGED_DOCK"
                    BatteryManager.BATTERY_PLUGGED_USB -> "PLUGGED_USB"
                    BatteryManager.BATTERY_PLUGGED_WIRELESS -> "PLUGGED_WIRELESS"
                    else -> "PLUGGED_$pluggedInt"
                }

                var batteryTemperature = batteryStatus.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE).toDouble() / 10.0
                batteryTemperature = round(batteryTemperature * 10.0) / 10.0

                val status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val batteryStatusString = when (status) {
                    BatteryManager.BATTERY_STATUS_CHARGING -> "CHARGING"
                    BatteryManager.BATTERY_STATUS_DISCHARGING -> "DISCHARGING"
                    BatteryManager.BATTERY_STATUS_FULL -> "FULL"
                    BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "NOT_CHARGING"
                    BatteryManager.BATTERY_STATUS_UNKNOWN -> "UNKNOWN"
                    else -> {
                        Logger.logError(LOG_TAG, "Invalid BatteryManager.EXTRA_STATUS value: $status")
                        "UNKNOWN"
                    }
                }

                var batteryVoltage = batteryStatus.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
                if (batteryVoltage < 100) {
                    Logger.logVerbose(LOG_TAG, "Fixing voltage from $batteryVoltage to ${batteryVoltage * 1000}")
                    batteryVoltage *= 1000
                }

                val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager

                var batteryCurrentNow = getIntProperty(batteryManager, BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
                if (batteryCurrentNow != null && abs(batteryCurrentNow / 1000) < 1.0) {
                    Logger.logVerbose(LOG_TAG, "Fixing current_now from $batteryCurrentNow to ${batteryCurrentNow * 1000}")
                    batteryCurrentNow *= 1000
                }

                out.beginObject()
                putBooleanValueIfSet(out, "present", batteryStatus.getBooleanExtra(BatteryManager.EXTRA_PRESENT, false))
                putStringIfSet(out, "technology", batteryStatus.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY))
                putStringIfSet(out, "health", batteryHealth)
                putStringIfSet(out, "plugged", batteryPlugged)
                putStringIfSet(out, "status", batteryStatusString)
                putDoubleIfSet(out, "temperature", batteryTemperature)
                putIntegerIfSet(out, "voltage", batteryVoltage)
                putIntegerIfSet(out, "current", batteryCurrentNow)
                putIntegerIfSet(out, "current_average", getIntProperty(batteryManager, BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE))
                putIntegerIfSet(out, "percentage", getIntProperty(batteryManager, BatteryManager.BATTERY_PROPERTY_CAPACITY))
                putIntegerIfSet(out, "level", batteryLevel)
                putIntegerIfSet(out, "scale", batteryScale)
                putIntegerIfSet(out, "charge_counter", getIntProperty(batteryManager, BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER))
                putLongIfSet(out, "energy", getLongProperty(batteryManager, BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    val batteryCycle = batteryStatus.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, -1)
                    putIntegerIfSet(out, "cycle", if (batteryCycle != -1) batteryCycle else null)
                }
                out.endObject()
            }
        })
    }

    private fun getIntProperty(batteryManager: BatteryManager?, id: Int): Int? {
        if (batteryManager == null) return null
        val value = batteryManager.getIntProperty(id)
        return if (targetSdkVersion < Build.VERSION_CODES.P) {
            if (value != 0) value else null
        } else {
            if (value != Int.MIN_VALUE) value else null
        }
    }

    private fun getLongProperty(batteryManager: BatteryManager?, id: Int): Long? {
        if (batteryManager == null) return null
        val value = batteryManager.getLongProperty(id)
        return if (value != Long.MIN_VALUE) value else null
    }
}
