package com.termux.api.apis

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.text.TextUtils
import android.text.format.Formatter
import android.util.JsonWriter

import com.termux.api.TermuxApiReceiver
import com.termux.api.util.ResultReturner
import com.termux.shared.logger.Logger

object WifiAPI {

    private const val LOG_TAG = "WifiAPI"

    @JvmStatic
    fun onReceiveWifiConnectionInfo(apiReceiver: TermuxApiReceiver, context: Context, intent: Intent) {
        Logger.logDebug(LOG_TAG, "onReceiveWifiConnectionInfo")

        ResultReturner.returnData(apiReceiver, intent, object : ResultReturner.ResultJsonWriter() {
            @SuppressLint("HardwareIds")
            @Throws(Exception::class)
            override fun writeJson(out: JsonWriter) {
                val manager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val info = manager.connectionInfo
                out.beginObject()
                if (info == null) {
                    out.name("API_ERROR").value("No current connection")
                } else {
                    out.name("bssid").value(info.bssid)
                    out.name("frequency_mhz").value(info.frequency.toLong())
                    @Suppress("DEPRECATION")
                    out.name("ip").value(Formatter.formatIpAddress(info.ipAddress))
                    out.name("link_speed_mbps").value(info.linkSpeed.toLong())
                    out.name("mac_address").value(info.macAddress)
                    out.name("network_id").value(info.networkId.toLong())
                    out.name("rssi").value(info.rssi.toLong())
                    out.name("ssid").value(info.ssid.replace("\"", ""))
                    out.name("ssid_hidden").value(info.hiddenSSID)
                    out.name("supplicant_state").value(info.supplicantState.toString())
                }
                out.endObject()
            }
        })
    }

    private fun isLocationEnabled(context: Context): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
    }

    @JvmStatic
    fun onReceiveWifiScanInfo(apiReceiver: TermuxApiReceiver, context: Context, intent: Intent) {
        Logger.logDebug(LOG_TAG, "onReceiveWifiScanInfo")

        ResultReturner.returnData(apiReceiver, intent, object : ResultReturner.ResultJsonWriter() {
            @Throws(Exception::class)
            override fun writeJson(out: JsonWriter) {
                val manager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val scans = manager.scanResults
                when {
                    scans == null -> {
                        out.beginObject().name("API_ERROR").value("Failed getting scan results").endObject()
                    }
                    scans.isEmpty() && !isLocationEnabled(context) -> {
                        val errorMessage = "Location needs to be enabled on the device"
                        out.beginObject().name("API_ERROR").value(errorMessage).endObject()
                    }
                    else -> {
                        out.beginArray()
                        for (scan in scans) {
                            out.beginObject()
                            out.name("bssid").value(scan.BSSID)
                            out.name("frequency_mhz").value(scan.frequency.toLong())
                            out.name("rssi").value(scan.level.toLong())
                            out.name("ssid").value(scan.SSID)
                            out.name("timestamp").value(scan.timestamp)

                            val channelWidth = scan.channelWidth
                            val channelWidthMhz = when (channelWidth) {
                                ScanResult.CHANNEL_WIDTH_20MHZ -> "20"
                                ScanResult.CHANNEL_WIDTH_40MHZ -> "40"
                                ScanResult.CHANNEL_WIDTH_80MHZ -> "80"
                                ScanResult.CHANNEL_WIDTH_80MHZ_PLUS_MHZ -> "80+80"
                                ScanResult.CHANNEL_WIDTH_160MHZ -> "160"
                                else -> "???"
                            }
                            out.name("channel_bandwidth_mhz").value(channelWidthMhz)
                            if (channelWidth != ScanResult.CHANNEL_WIDTH_20MHZ) {
                                out.name("center_frequency_mhz").value(scan.centerFreq0.toLong())
                            }
                            if (!TextUtils.isEmpty(scan.capabilities)) {
                                out.name("capabilities").value(scan.capabilities)
                            }
                            if (!TextUtils.isEmpty(scan.operatorFriendlyName)) {
                                out.name("operator_name").value(scan.operatorFriendlyName.toString())
                            }
                            if (!TextUtils.isEmpty(scan.venueName)) {
                                out.name("venue_name").value(scan.venueName.toString())
                            }
                            out.endObject()
                        }
                        out.endArray()
                    }
                }
            }
        })
    }

    @JvmStatic
    @Suppress("DEPRECATION")
    fun onReceiveWifiEnable(apiReceiver: TermuxApiReceiver, context: Context, intent: Intent) {
        Logger.logDebug(LOG_TAG, "onReceiveWifiEnable")

        ResultReturner.returnData(apiReceiver, intent, object : ResultReturner.ResultJsonWriter() {
            override fun writeJson(out: JsonWriter) {
                val manager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val state = intent.getBooleanExtra("enabled", false)
                manager.isWifiEnabled = state
            }
        })
    }
}
