package com.termux.api.apis

import android.content.Context
import android.content.Intent
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.os.Looper
import android.view.Surface
import android.view.WindowManager

import com.termux.api.TermuxApiReceiver
import com.termux.api.util.ResultReturner
import com.termux.shared.errors.Error
import com.termux.shared.file.FileUtils
import com.termux.shared.logger.Logger
import com.termux.shared.termux.file.TermuxFileUtils

import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter

object CameraPhotoAPI {

    private const val LOG_TAG = "CameraPhotoAPI"

    @JvmStatic
    fun onReceive(apiReceiver: TermuxApiReceiver, context: Context, intent: Intent) {
        Logger.logDebug(LOG_TAG, "onReceive")

        val filePath = intent.getStringExtra("file")
        val cameraId = intent.getStringExtra("camera") ?: "0"

        ResultReturner.returnData(apiReceiver, intent, ResultReturner.ResultWriter { stdout ->
            if (filePath.isNullOrEmpty()) {
                stdout.println("ERROR: File path not passed")
            } else {
                val photoFilePath = TermuxFileUtils.getCanonicalPath(filePath, null, true)
                val photoDirPath = FileUtils.getFileDirname(photoFilePath)
                Logger.logVerbose(LOG_TAG, "photoFilePath=\"$photoFilePath\", photoDirPath=\"$photoDirPath\"")

                val error: Error? = TermuxFileUtils.validateDirectoryFileExistenceAndPermissions(
                    "photo directory", photoDirPath,
                    true, true, true,
                    false, true
                )
                if (error != null) {
                    stdout.println("ERROR: ${error.errorLogString}")
                } else {
                    takePicture(stdout, context, File(photoFilePath), cameraId)
                }
            }
        })
    }

    private fun takePicture(stdout: PrintWriter, context: Context, outputFile: File, cameraId: String) {
        try {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

            Looper.prepare()
            val looper = Looper.myLooper()

            @Suppress("MissingPermission")
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    try {
                        proceedWithOpenedCamera(context, manager, camera, outputFile, looper, stdout)
                    } catch (e: Exception) {
                        Logger.logStackTraceWithMessage(LOG_TAG, "Exception in onOpened()", e)
                        closeCamera(camera, looper)
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    Logger.logInfo(LOG_TAG, "onDisconnected() from camera")
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Logger.logError(LOG_TAG, "Failed opening camera: $error")
                    closeCamera(camera, looper)
                }
            }, null)

            Looper.loop()
        } catch (e: Exception) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Error getting camera", e)
        }
    }

    @Throws(CameraAccessException::class, IllegalArgumentException::class)
    internal fun proceedWithOpenedCamera(
        context: Context,
        manager: CameraManager,
        camera: CameraDevice,
        outputFile: File,
        looper: Looper?,
        stdout: PrintWriter
    ) {
        val outputSurfaces = mutableListOf<Surface>()

        val characteristics = manager.getCameraCharacteristics(camera.id)

        var autoExposureMode = CameraMetadata.CONTROL_AE_MODE_OFF
        characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES)?.forEach { supportedMode ->
            if (supportedMode == CameraMetadata.CONTROL_AE_MODE_ON) {
                autoExposureMode = supportedMode
            }
        }
        val autoExposureModeFinal = autoExposureMode

        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(ImageFormat.JPEG)?.toList() ?: emptyList()
        val largest = sizes.maxWithOrNull(compareBy { it.width.toLong() * it.height })
            ?: throw IllegalArgumentException("No JPEG output sizes available")

        val imageReader = ImageReader.newInstance(largest.width, largest.height, ImageFormat.JPEG, 2)
        imageReader.setOnImageAvailableListener({ reader ->
            Thread {
                try {
                    reader.acquireNextImage()?.use { image ->
                        val buffer = image.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        try {
                            FileOutputStream(outputFile).use { output ->
                                output.write(bytes)
                            }
                        } catch (e: Exception) {
                            stdout.println("Error writing image: ${e.message}")
                            Logger.logStackTraceWithMessage(LOG_TAG, "Error writing image", e)
                        }
                    }
                } finally {
                    imageReader.close()
                    releaseSurfaces(outputSurfaces)
                    closeCamera(camera, looper)
                }
            }.start()
        }, null)

        val imageReaderSurface = imageReader.surface
        outputSurfaces.add(imageReaderSurface)

        val previewTexture = SurfaceTexture(1)
        val dummySurface = Surface(previewTexture)
        outputSurfaces.add(dummySurface)

        camera.createCaptureSession(outputSurfaces, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                try {
                    val previewReq = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                    previewReq.addTarget(dummySurface)
                    previewReq.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                    previewReq.set(CaptureRequest.CONTROL_AE_MODE, autoExposureModeFinal)

                    session.setRepeatingRequest(previewReq.build(), null, null)
                    Logger.logInfo(LOG_TAG, "preview started")
                    Thread.sleep(500)
                    session.stopRepeating()
                    Logger.logInfo(LOG_TAG, "preview stopped")

                    val jpegRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                    jpegRequest.addTarget(imageReaderSurface)
                    jpegRequest.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                    jpegRequest.set(CaptureRequest.CONTROL_AE_MODE, autoExposureModeFinal)
                    jpegRequest.set(CaptureRequest.JPEG_ORIENTATION, correctOrientation(context, characteristics))

                    saveImage(camera, session, jpegRequest.build())
                } catch (e: Exception) {
                    Logger.logStackTraceWithMessage(LOG_TAG, "onConfigured() error in preview", e)
                    imageReader.close()
                    releaseSurfaces(outputSurfaces)
                    closeCamera(camera, looper)
                }
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                Logger.logError(LOG_TAG, "onConfigureFailed() error in preview")
                imageReader.close()
                releaseSurfaces(outputSurfaces)
                closeCamera(camera, looper)
            }
        }, null)
    }

    @Throws(CameraAccessException::class)
    internal fun saveImage(camera: CameraDevice, session: CameraCaptureSession, request: CaptureRequest) {
        session.capture(request, object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                completedSession: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult
            ) {
                Logger.logInfo(LOG_TAG, "onCaptureCompleted()")
            }
        }, null)
    }

    internal fun correctOrientation(context: Context, characteristics: CameraCharacteristics): Int {
        val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)
        val isFrontFacing = lensFacing != null && lensFacing == CameraCharacteristics.LENS_FACING_FRONT
        Logger.logInfo(LOG_TAG, "${if (isFrontFacing) "Using" else "Not using"} a front facing camera.")

        var sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)
        if (sensorOrientation != null) {
            Logger.logInfo(LOG_TAG, "Sensor orientation: $sensorOrientation degrees")
        } else {
            Logger.logInfo(LOG_TAG, "CameraCharacteristics didn't contain SENSOR_ORIENTATION. Assuming 0 degrees.")
            sensorOrientation = 0
        }

        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        val deviceRotation = windowManager.defaultDisplay.rotation
        val deviceOrientation = when (deviceRotation) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> {
                Logger.logInfo(LOG_TAG, "Default display has unknown rotation $deviceRotation. Assuming 0 degrees.")
                0
            }
        }
        Logger.logInfo(LOG_TAG, "Device orientation: $deviceOrientation degrees")

        var jpegOrientation = if (isFrontFacing) {
            sensorOrientation + deviceOrientation
        } else {
            sensorOrientation - deviceOrientation
        }
        jpegOrientation = (jpegOrientation + 360) % 360
        Logger.logInfo(LOG_TAG, "Returning JPEG orientation of $jpegOrientation degrees")
        return jpegOrientation
    }

    internal fun releaseSurfaces(outputSurfaces: List<Surface>) {
        outputSurfaces.forEach { it.release() }
        Logger.logInfo(LOG_TAG, "surfaces released")
    }

    internal fun closeCamera(camera: CameraDevice, looper: Looper?) {
        try {
            camera.close()
        } catch (e: RuntimeException) {
            Logger.logInfo(LOG_TAG, "Exception closing camera: ${e.message}")
        }
        looper?.quit()
    }
}
