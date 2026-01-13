package com.termux.api.apis

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.telephony.CellInfo
import android.telephony.CellInfoCdma
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.CellIdentityNr
import android.telephony.CellSignalStrengthNr
import android.telephony.TelephonyManager
import android.util.JsonWriter
import androidx.annotation.RequiresPermission
import com.termux.api.TermuxApiReceiver
import com.termux.api.util.ResultReturner
import com.termux.shared.logger.Logger
import java.io.IOException

/**
 * Exposing [android.telephony.TelephonyManager].
 */
object TelephonyAPI {

    private const val LOG_TAG = "TelephonyAPI"

    @Throws(IOException::class)
    private fun writeIfKnown(out: JsonWriter, name: String, value: Int) {
        if (value != Int.MAX_VALUE) out.name(name).value(value)
    }

    @Throws(IOException::class)
    private fun writeIfKnown(out: JsonWriter, name: String, value: Long) {
        if (value != Long.MAX_VALUE) out.name(name).value(value)
    }

    @Throws(IOException::class)
    private fun writeIfKnown(out: JsonWriter, name: String, value: IntArray?) {
        if (value != null) {
            out.name(name)
            out.beginArray()
            for (v in value) out.value(v)
            out.endArray()
        }
    }

    @JvmStatic
    fun onReceiveTelephonyCellInfo(apiReceiver: TermuxApiReceiver, context: Context, intent: Intent) {
        Logger.logDebug(LOG_TAG, "onReceiveTelephonyCellInfo")

        ResultReturner.returnData(apiReceiver, intent, object : ResultReturner.ResultJsonWriter() {
            @Throws(Exception::class)
            override fun writeJson(out: JsonWriter) {
                val manager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                out.beginArray()

                var cellInfoData: List<CellInfo>? = null

                try {
                    cellInfoData = manager.allCellInfo
                } catch (e: SecurityException) {
                    // Direct call of getAllCellInfo() doesn't work on Android 10 (Q).
                }

                cellInfoData?.forEach { cellInfo ->
                    out.beginObject()
                    when (cellInfo) {
                        is CellInfoGsm -> {
                            out.name("type").value("gsm")
                            out.name("registered").value(cellInfo.isRegistered)
                            out.name("asu").value(cellInfo.cellSignalStrength.asuLevel)
                            writeIfKnown(out, "dbm", cellInfo.cellSignalStrength.dbm)
                            out.name("level").value(cellInfo.cellSignalStrength.level)
                            writeIfKnown(out, "cid", cellInfo.cellIdentity.cid)
                            writeIfKnown(out, "lac", cellInfo.cellIdentity.lac)
                            writeIfKnown(out, "mcc", cellInfo.cellIdentity.mcc)
                            writeIfKnown(out, "mnc", cellInfo.cellIdentity.mnc)
                        }
                        is CellInfoLte -> {
                            out.name("type").value("lte")
                            out.name("registered").value(cellInfo.isRegistered)
                            out.name("asu").value(cellInfo.cellSignalStrength.asuLevel)
                            out.name("dbm").value(cellInfo.cellSignalStrength.dbm)
                            writeIfKnown(out, "level", cellInfo.cellSignalStrength.level)
                            writeIfKnown(out, "timing_advance", cellInfo.cellSignalStrength.timingAdvance)
                            writeIfKnown(out, "ci", cellInfo.cellIdentity.ci)
                            writeIfKnown(out, "pci", cellInfo.cellIdentity.pci)
                            writeIfKnown(out, "tac", cellInfo.cellIdentity.tac)
                            writeIfKnown(out, "mcc", cellInfo.cellIdentity.mcc)
                            writeIfKnown(out, "mnc", cellInfo.cellIdentity.mnc)

                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                writeIfKnown(out, "rsrp", cellInfo.cellSignalStrength.rsrp)
                                writeIfKnown(out, "rsrq", cellInfo.cellSignalStrength.rsrq)
                            }

                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                writeIfKnown(out, "rssi", cellInfo.cellSignalStrength.rssi)
                            }

                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                writeIfKnown(out, "bands", cellInfo.cellIdentity.bands)
                            }
                        }
                        is CellInfoNr -> {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                val nrCellIdent = cellInfo.cellIdentity as CellIdentityNr
                                val ssInfo = cellInfo.cellSignalStrength
                                out.name("type").value("nr")
                                out.name("registered").value(cellInfo.isRegistered)
                                out.name("asu").value(ssInfo.asuLevel)
                                out.name("dbm").value(ssInfo.dbm)
                                writeIfKnown(out, "level", ssInfo.level)
                                writeIfKnown(out, "nci", nrCellIdent.nci)
                                writeIfKnown(out, "pci", nrCellIdent.pci)
                                writeIfKnown(out, "tac", nrCellIdent.tac)
                                out.name("mcc").value(nrCellIdent.mccString)
                                out.name("mnc").value(nrCellIdent.mncString)

                                if (ssInfo is CellSignalStrengthNr) {
                                    writeIfKnown(out, "csi_rsrp", ssInfo.csiRsrp)
                                    writeIfKnown(out, "csi_rsrq", ssInfo.csiRsrq)
                                    writeIfKnown(out, "csi_sinr", ssInfo.csiSinr)
                                    writeIfKnown(out, "ss_rsrp", ssInfo.ssRsrp)
                                    writeIfKnown(out, "ss_rsrq", ssInfo.ssRsrq)
                                    writeIfKnown(out, "ss_sinr", ssInfo.ssSinr)
                                }

                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                    writeIfKnown(out, "bands", nrCellIdent.bands)
                                }
                            }
                        }
                        is CellInfoCdma -> {
                            out.name("type").value("cdma")
                            out.name("registered").value(cellInfo.isRegistered)
                            out.name("asu").value(cellInfo.cellSignalStrength.asuLevel)
                            out.name("dbm").value(cellInfo.cellSignalStrength.dbm)
                            out.name("level").value(cellInfo.cellSignalStrength.level)
                            out.name("cdma_dbm").value(cellInfo.cellSignalStrength.cdmaDbm)
                            out.name("cdma_ecio").value(cellInfo.cellSignalStrength.cdmaEcio)
                            out.name("cdma_level").value(cellInfo.cellSignalStrength.cdmaLevel)
                            out.name("evdo_dbm").value(cellInfo.cellSignalStrength.evdoDbm)
                            out.name("evdo_ecio").value(cellInfo.cellSignalStrength.evdoEcio)
                            out.name("evdo_level").value(cellInfo.cellSignalStrength.evdoLevel)
                            out.name("evdo_snr").value(cellInfo.cellSignalStrength.evdoSnr)
                            out.name("basestation").value(cellInfo.cellIdentity.basestationId)
                            out.name("latitude").value(cellInfo.cellIdentity.latitude)
                            out.name("longitude").value(cellInfo.cellIdentity.longitude)
                            out.name("network").value(cellInfo.cellIdentity.networkId)
                            out.name("system").value(cellInfo.cellIdentity.systemId)
                        }
                        is CellInfoWcdma -> {
                            out.name("type").value("wcdma")
                            out.name("registered").value(cellInfo.isRegistered)
                            out.name("asu").value(cellInfo.cellSignalStrength.asuLevel)
                            writeIfKnown(out, "dbm", cellInfo.cellSignalStrength.dbm)
                            out.name("level").value(cellInfo.cellSignalStrength.level)
                            writeIfKnown(out, "cid", cellInfo.cellIdentity.cid)
                            writeIfKnown(out, "lac", cellInfo.cellIdentity.lac)
                            writeIfKnown(out, "mcc", cellInfo.cellIdentity.mcc)
                            writeIfKnown(out, "mnc", cellInfo.cellIdentity.mnc)
                            writeIfKnown(out, "psc", cellInfo.cellIdentity.psc)
                        }
                    }
                    out.endObject()
                }

                out.endArray()
            }
        })
    }

    @JvmStatic
    fun onReceiveTelephonyDeviceInfo(apiReceiver: TermuxApiReceiver, context: Context, intent: Intent) {
        Logger.logDebug(LOG_TAG, "onReceiveTelephonyDeviceInfo")

        ResultReturner.returnData(apiReceiver, intent, object : ResultReturner.ResultJsonWriter() {
            @RequiresPermission(Manifest.permission.READ_PHONE_STATE)
            @SuppressLint("HardwareIds")
            @Throws(Exception::class)
            override fun writeJson(out: JsonWriter) {
                val manager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                out.beginObject()

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    out.name("data_enabled").value(manager.isDataEnabled.toString())
                }

                val dataActivityString = when (manager.dataActivity) {
                    TelephonyManager.DATA_ACTIVITY_NONE -> "none"
                    TelephonyManager.DATA_ACTIVITY_IN -> "in"
                    TelephonyManager.DATA_ACTIVITY_OUT -> "out"
                    TelephonyManager.DATA_ACTIVITY_INOUT -> "inout"
                    TelephonyManager.DATA_ACTIVITY_DORMANT -> "dormant"
                    else -> manager.dataActivity.toString()
                }
                out.name("data_activity").value(dataActivityString)

                val dataStateString = when (manager.dataState) {
                    TelephonyManager.DATA_DISCONNECTED -> "disconnected"
                    TelephonyManager.DATA_CONNECTING -> "connecting"
                    TelephonyManager.DATA_CONNECTED -> "connected"
                    TelephonyManager.DATA_SUSPENDED -> "suspended"
                    else -> manager.dataState.toString()
                }
                out.name("data_state").value(dataStateString)

                val phoneType = manager.phoneType

                var deviceId: String? = null
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        deviceId = if (phoneType == TelephonyManager.PHONE_TYPE_GSM) manager.imei else manager.meid
                    }
                } catch (e: SecurityException) {
                    // Failed to obtain device id.
                }

                out.name("device_id").value(deviceId)
                out.name("device_software_version").value(manager.deviceSoftwareVersion)
                out.name("phone_count").value(manager.phoneCount)

                val phoneTypeString = when (phoneType) {
                    TelephonyManager.PHONE_TYPE_CDMA -> "cdma"
                    TelephonyManager.PHONE_TYPE_GSM -> "gsm"
                    TelephonyManager.PHONE_TYPE_NONE -> "none"
                    TelephonyManager.PHONE_TYPE_SIP -> "sip"
                    else -> phoneType.toString()
                }
                out.name("phone_type").value(phoneTypeString)

                out.name("network_operator").value(manager.networkOperator)
                out.name("network_operator_name").value(manager.networkOperatorName)
                out.name("network_country_iso").value(manager.networkCountryIso)

                val networkType = manager.networkType
                val networkTypeName = when (networkType) {
                    TelephonyManager.NETWORK_TYPE_1xRTT -> "1xrtt"
                    TelephonyManager.NETWORK_TYPE_CDMA -> "cdma"
                    TelephonyManager.NETWORK_TYPE_EDGE -> "edge"
                    TelephonyManager.NETWORK_TYPE_EHRPD -> "ehrpd"
                    TelephonyManager.NETWORK_TYPE_EVDO_0 -> "evdo_0"
                    TelephonyManager.NETWORK_TYPE_EVDO_A -> "evdo_a"
                    TelephonyManager.NETWORK_TYPE_EVDO_B -> "evdo_b"
                    TelephonyManager.NETWORK_TYPE_GPRS -> "gprs"
                    TelephonyManager.NETWORK_TYPE_HSDPA -> "hdspa"
                    TelephonyManager.NETWORK_TYPE_HSPA -> "hspa"
                    TelephonyManager.NETWORK_TYPE_HSPAP -> "hspap"
                    TelephonyManager.NETWORK_TYPE_HSUPA -> "hsupa"
                    TelephonyManager.NETWORK_TYPE_IDEN -> "iden"
                    TelephonyManager.NETWORK_TYPE_LTE -> "lte"
                    TelephonyManager.NETWORK_TYPE_UMTS -> "umts"
                    TelephonyManager.NETWORK_TYPE_UNKNOWN -> "unknown"
                    else -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && networkType == TelephonyManager.NETWORK_TYPE_NR) {
                            "nr"
                        } else {
                            networkType.toString()
                        }
                    }
                }
                out.name("network_type").value(networkTypeName)
                out.name("network_roaming").value(manager.isNetworkRoaming)
                out.name("sim_country_iso").value(manager.simCountryIso)
                out.name("sim_operator").value(manager.simOperator)
                out.name("sim_operator_name").value(manager.simOperatorName)

                var simSerial: String? = null
                var subscriberId: String? = null
                try {
                    simSerial = manager.simSerialNumber
                    subscriberId = manager.subscriberId
                } catch (e: SecurityException) {
                    // Failed to obtain device id.
                }
                out.name("sim_serial_number").value(simSerial)
                out.name("sim_subscriber_id").value(subscriberId)

                val simStateString = when (manager.simState) {
                    TelephonyManager.SIM_STATE_ABSENT -> "absent"
                    TelephonyManager.SIM_STATE_NETWORK_LOCKED -> "network_locked"
                    TelephonyManager.SIM_STATE_PIN_REQUIRED -> "pin_required"
                    TelephonyManager.SIM_STATE_PUK_REQUIRED -> "puk_required"
                    TelephonyManager.SIM_STATE_READY -> "ready"
                    TelephonyManager.SIM_STATE_UNKNOWN -> "unknown"
                    else -> manager.simState.toString()
                }
                out.name("sim_state").value(simStateString)

                out.endObject()
            }
        })
    }

    @JvmStatic
    fun onReceiveTelephonyCall(apiReceiver: TermuxApiReceiver, context: Context, intent: Intent) {
        Logger.logDebug(LOG_TAG, "onReceiveTelephonyCall")

        var numberExtra = intent.getStringExtra("number")
        if (numberExtra == null) {
            Logger.logError(LOG_TAG, "No 'number' extra")
            ResultReturner.noteDone(apiReceiver, intent)
            return
        }

        if (numberExtra.contains("#")) {
            numberExtra = numberExtra.replace("#", "%23")
        }

        val data = Uri.parse("tel:$numberExtra")

        val callIntent = Intent(Intent.ACTION_CALL).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            setData(data)
        }

        try {
            context.startActivity(callIntent)
        } catch (e: SecurityException) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Exception in phone call", e)
        }

        ResultReturner.noteDone(apiReceiver, intent)
    }
}
