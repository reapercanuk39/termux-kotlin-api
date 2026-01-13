package com.termux.api.apis

import android.content.Context
import android.content.Intent
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.util.JsonWriter

import com.termux.api.TermuxApiReceiver
import com.termux.api.util.ResultReturner
import com.termux.shared.logger.Logger

object CameraInfoAPI {

    private const val LOG_TAG = "CameraInfoAPI"

    @JvmStatic
    fun onReceive(apiReceiver: TermuxApiReceiver, context: Context, intent: Intent) {
        Logger.logDebug(LOG_TAG, "onReceive")

        ResultReturner.returnData(apiReceiver, intent, object : ResultReturner.ResultJsonWriter() {
            override fun writeJson(out: JsonWriter) {
                val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

                out.beginArray()
                for (cameraId in manager.cameraIdList) {
                    out.beginObject()
                    out.name("id").value(cameraId)

                    val camera = manager.getCameraCharacteristics(cameraId)

                    out.name("facing")
                    val lensFacing = camera.get(CameraCharacteristics.LENS_FACING)
                    when (lensFacing) {
                        CameraMetadata.LENS_FACING_FRONT -> out.value("front")
                        CameraMetadata.LENS_FACING_BACK -> out.value("back")
                        else -> out.value(lensFacing)
                    }

                    val map = camera.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    out.name("jpeg_output_sizes").beginArray()
                    map?.getOutputSizes(ImageFormat.JPEG)?.forEach { size ->
                        out.beginObject()
                            .name("width").value(size.width)
                            .name("height").value(size.height)
                            .endObject()
                    }
                    out.endArray()

                    out.name("focal_lengths").beginArray()
                    camera.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.forEach { f ->
                        out.value(f)
                    }
                    out.endArray()

                    out.name("auto_exposure_modes").beginArray()
                    camera.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES)?.forEach { flashMode ->
                        when (flashMode) {
                            CameraMetadata.CONTROL_AE_MODE_OFF -> out.value("CONTROL_AE_MODE_OFF")
                            CameraMetadata.CONTROL_AE_MODE_ON -> out.value("CONTROL_AE_MODE_ON")
                            CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH -> out.value("CONTROL_AE_MODE_ON_ALWAYS_FLASH")
                            CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH -> out.value("CONTROL_AE_MODE_ON_AUTO_FLASH")
                            CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH_REDEYE -> out.value("CONTROL_AE_MODE_ON_AUTO_FLASH_REDEYE")
                            CameraMetadata.CONTROL_AE_MODE_ON_EXTERNAL_FLASH -> out.value("CONTROL_AE_MODE_ON_EXTERNAL_FLASH")
                            else -> out.value(flashMode)
                        }
                    }
                    out.endArray()

                    val physicalSize = camera.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                    out.name("physical_size").beginObject()
                        .name("width").value(physicalSize?.width ?: 0f)
                        .name("height").value(physicalSize?.height ?: 0f)
                        .endObject()

                    out.name("capabilities").beginArray()
                    camera.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.forEach { capability ->
                        when (capability) {
                            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE -> out.value("backward_compatible")
                            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE -> out.value("burst_capture")
                            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO -> out.value("constrained_high_speed_video")
                            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT -> out.value("depth_output")
                            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA -> out.value("logical_multi_camera")
                            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING -> out.value("manual_post_processing")
                            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR -> out.value("manual_sensor")
                            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MONOCHROME -> out.value("monochrome")
                            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MOTION_TRACKING -> out.value("motion_tracking")
                            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_PRIVATE_REPROCESSING -> out.value("private_reprocessing")
                            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW -> out.value("raw")
                            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_READ_SENSOR_SETTINGS -> out.value("read_sensor_settings")
                            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_YUV_REPROCESSING -> out.value("yuv_reprocessing")
                            else -> out.value(capability)
                        }
                    }
                    out.endArray()

                    out.endObject()
                }
                out.endArray()
            }
        })
    }
}
