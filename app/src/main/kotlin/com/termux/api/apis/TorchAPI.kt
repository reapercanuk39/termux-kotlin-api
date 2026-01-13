package com.termux.api.apis

import android.content.Context
import android.content.Intent
import android.hardware.Camera
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.widget.Toast
import com.termux.api.TermuxApiReceiver
import com.termux.api.util.ResultReturner
import com.termux.shared.logger.Logger

object TorchAPI {

    private var legacyCamera: Camera? = null
    private const val LOG_TAG = "TorchAPI"

    @JvmStatic
    fun onReceive(apiReceiver: TermuxApiReceiver, context: Context, intent: Intent) {
        Logger.logDebug(LOG_TAG, "onReceive")

        val enabled = intent.getBooleanExtra("enabled", false)

        toggleTorch(context, enabled)
        ResultReturner.noteDone(apiReceiver, intent)
    }

    private fun toggleTorch(context: Context, enabled: Boolean) {
        try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val torchCameraId = getTorchCameraId(cameraManager)

            if (torchCameraId != null) {
                cameraManager.setTorchMode(torchCameraId, enabled)
            } else {
                Toast.makeText(context, "Torch unavailable on your device", Toast.LENGTH_LONG).show()
            }
        } catch (e: CameraAccessException) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Error toggling torch", e)
        }
    }

    @Suppress("DEPRECATION")
    private fun legacyToggleTorch(enabled: Boolean) {
        Logger.logInfo(LOG_TAG, "Using legacy camera api to toggle torch")

        if (legacyCamera == null) {
            legacyCamera = Camera.open()
        }

        val params = legacyCamera?.parameters

        if (enabled) {
            params?.flashMode = Camera.Parameters.FLASH_MODE_TORCH
            legacyCamera?.parameters = params
            legacyCamera?.startPreview()
        } else {
            legacyCamera?.stopPreview()
            legacyCamera?.release()
            legacyCamera = null
        }
    }

    @Throws(CameraAccessException::class)
    private fun getTorchCameraId(cameraManager: CameraManager): String? {
        val cameraIdList = cameraManager.cameraIdList
        for (id in cameraIdList) {
            val flashAvailable = cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.FLASH_INFO_AVAILABLE)
            if (flashAvailable == true) {
                return id
            }
        }
        return null
    }
}
