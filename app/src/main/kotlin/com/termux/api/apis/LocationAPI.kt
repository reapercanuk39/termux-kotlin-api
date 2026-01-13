package com.termux.api.apis

import android.Manifest
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.util.JsonWriter
import androidx.annotation.RequiresPermission
import com.termux.api.TermuxApiReceiver
import com.termux.api.util.ResultReturner
import com.termux.shared.logger.Logger
import java.io.IOException

object LocationAPI {

    private const val LOG_TAG = "LocationAPI"

    private const val REQUEST_LAST_KNOWN = "last"
    private const val REQUEST_ONCE = "once"
    private const val REQUEST_UPDATES = "updates"

    @JvmStatic
    fun onReceive(apiReceiver: TermuxApiReceiver, context: Context, intent: Intent) {
        Logger.logDebug(LOG_TAG, "onReceive")

        ResultReturner.returnData(apiReceiver, intent, object : ResultReturner.ResultJsonWriter() {
            @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
            override fun writeJson(out: JsonWriter) {
                val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

                var provider = intent.getStringExtra("provider") ?: LocationManager.GPS_PROVIDER
                if (provider != LocationManager.GPS_PROVIDER && 
                    provider != LocationManager.NETWORK_PROVIDER && 
                    provider != LocationManager.PASSIVE_PROVIDER) {
                    out.beginObject()
                        .name("API_ERROR")
                        .value("Unsupported provider '$provider' - only '${LocationManager.GPS_PROVIDER}', " +
                               "'${LocationManager.NETWORK_PROVIDER}' and '${LocationManager.PASSIVE_PROVIDER}' supported")
                        .endObject()
                    return
                }

                val request = intent.getStringExtra("request") ?: REQUEST_ONCE
                when (request) {
                    REQUEST_LAST_KNOWN -> {
                        val lastKnownLocation = manager.getLastKnownLocation(provider)
                        locationToJson(lastKnownLocation, out)
                    }
                    REQUEST_ONCE -> {
                        Looper.prepare()
                        manager.requestSingleUpdate(provider, object : LocationListener {
                            override fun onStatusChanged(changedProvider: String?, status: Int, extras: Bundle?) {}
                            override fun onProviderEnabled(changedProvider: String) {}
                            override fun onProviderDisabled(changedProvider: String) {}
                            override fun onLocationChanged(location: Location) {
                                try {
                                    locationToJson(location, out)
                                } catch (e: IOException) {
                                    Logger.logStackTraceWithMessage(LOG_TAG, "Writing json", e)
                                } finally {
                                    Looper.myLooper()?.quit()
                                }
                            }
                        }, null)
                        Looper.loop()
                    }
                    REQUEST_UPDATES -> {
                        Looper.prepare()
                        manager.requestLocationUpdates(provider, 5000L, 50f, object : LocationListener {
                            override fun onStatusChanged(changedProvider: String?, status: Int, extras: Bundle?) {}
                            override fun onProviderEnabled(changedProvider: String) {}
                            override fun onProviderDisabled(changedProvider: String) {}
                            override fun onLocationChanged(location: Location) {
                                try {
                                    locationToJson(location, out)
                                    out.flush()
                                } catch (e: IOException) {
                                    Logger.logStackTraceWithMessage(LOG_TAG, "Writing json", e)
                                }
                            }
                        }, null)
                        val looper = Looper.myLooper()
                        Thread {
                            try {
                                Thread.sleep(30 * 1000)
                            } catch (e: InterruptedException) {
                                Logger.logStackTraceWithMessage(LOG_TAG, "INTER", e)
                            }
                            looper?.quit()
                        }.start()
                        Looper.loop()
                    }
                    else -> {
                        out.beginObject()
                            .name("API_ERROR")
                            .value("Unsupported request '$request' - only '$REQUEST_LAST_KNOWN', '$REQUEST_ONCE' and '$REQUEST_UPDATES' supported")
                            .endObject()
                    }
                }
            }
        })
    }

    @JvmStatic
    @Throws(IOException::class)
    fun locationToJson(lastKnownLocation: Location?, out: JsonWriter) {
        if (lastKnownLocation == null) {
            out.beginObject().name("API_ERROR").value("Failed to get location").endObject()
            return
        }
        out.beginObject()
        out.name("latitude").value(lastKnownLocation.latitude)
        out.name("longitude").value(lastKnownLocation.longitude)
        out.name("altitude").value(lastKnownLocation.altitude)
        out.name("accuracy").value(lastKnownLocation.accuracy.toDouble())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            out.name("vertical_accuracy").value(lastKnownLocation.verticalAccuracyMeters.toDouble())
        }
        out.name("bearing").value(lastKnownLocation.bearing.toDouble())
        out.name("speed").value(lastKnownLocation.speed.toDouble())
        val elapsedMs = (SystemClock.elapsedRealtimeNanos() - lastKnownLocation.elapsedRealtimeNanos) / 1000000
        out.name("elapsedMs").value(elapsedMs)
        out.name("provider").value(lastKnownLocation.provider)
        out.endObject()
    }
}
