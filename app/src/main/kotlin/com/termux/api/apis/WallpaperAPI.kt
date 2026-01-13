package com.termux.api.apis

import android.app.Service
import android.app.WallpaperManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.IBinder

import com.termux.api.util.ResultReturner
import com.termux.shared.logger.Logger

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

object WallpaperAPI {

    private const val LOG_TAG = "WallpaperAPI"

    @JvmStatic
    fun onReceive(context: Context, intent: Intent) {
        Logger.logDebug(LOG_TAG, "onReceive")

        val wallpaperService = Intent(context, WallpaperService::class.java).apply {
            intent.extras?.let { putExtras(it) }
        }
        context.startService(wallpaperService)
    }

    class WallpaperService : Service() {

        companion object {
            private const val DOWNLOAD_TIMEOUT = 30
            private const val LOG_TAG = "WallpaperService"
        }

        override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
            Logger.logDebug(LOG_TAG, "onStartCommand")

            when {
                intent.hasExtra("file") -> getWallpaperFromFile(intent)
                intent.hasExtra("url") -> getWallpaperFromUrl(intent)
                else -> {
                    val result = WallpaperResult().apply {
                        error = "No args supplied for WallpaperAPI!"
                    }
                    postWallpaperResult(applicationContext, intent, result)
                }
            }

            return START_NOT_STICKY
        }

        private fun getWallpaperFromFile(intent: Intent) {
            val wallpaperResult = WallpaperResult()
            val file = intent.getStringExtra("file")
            wallpaperResult.wallpaper = BitmapFactory.decodeFile(file)
            if (wallpaperResult.wallpaper == null) {
                wallpaperResult.error = "Error: Invalid image file!"
            }
            onWallpaperResult(intent, wallpaperResult)
        }

        private fun getWallpaperFromUrl(intent: Intent) {
            val url = intent.getStringExtra("url")
            val wallpaperDownload = getWallpaperDownloader(url)

            var result = WallpaperResult()

            try {
                result = wallpaperDownload.get(DOWNLOAD_TIMEOUT.toLong(), TimeUnit.SECONDS)
            } catch (e: InterruptedException) {
                Logger.logInfo(LOG_TAG, "Wallpaper download interrupted")
            } catch (e: ExecutionException) {
                result.error = "Unknown host!"
            } catch (e: TimeoutException) {
                result.error = "Connection timed out!"
            } finally {
                onWallpaperResult(intent, result)
            }
        }

        private fun getWallpaperDownloader(url: String?): Future<WallpaperResult> {
            return Executors.newSingleThreadExecutor().submit<WallpaperResult> {
                val wallpaperResult = WallpaperResult()
                var contentUrl = url ?: ""

                if (!contentUrl.startsWith("http://") && !contentUrl.startsWith("https://")) {
                    contentUrl = "http://$url"
                }
                val connection = URL(contentUrl).openConnection() as HttpURLConnection
                connection.connect()

                val contentType = "${connection.getHeaderField("Content-Type")}"

                // prevent downloading invalid resource
                if (!contentType.startsWith("image/")) {
                    wallpaperResult.error = "Invalid mime type! Must be an image resource!"
                } else {
                    val inputStream = connection.inputStream
                    wallpaperResult.wallpaper = BitmapFactory.decodeStream(inputStream)
                    inputStream?.close()
                }
                wallpaperResult
            }
        }

        private fun onWallpaperResult(intent: Intent, result: WallpaperResult) {
            val context = applicationContext
            val wallpaperManager = WallpaperManager.getInstance(context)

            if (result.wallpaper != null) {
                try {
                    val flag = if (intent.hasExtra("lockscreen")) {
                        WallpaperManager.FLAG_LOCK
                    } else {
                        WallpaperManager.FLAG_SYSTEM
                    }
                    wallpaperManager.setBitmap(result.wallpaper, null, true, flag)
                    result.message = "Wallpaper set successfully!"
                } catch (e: IOException) {
                    result.error = "Error setting wallpaper: ${e.message}"
                }
            }
            postWallpaperResult(context, intent, result)
        }

        private fun postWallpaperResult(context: Context, intent: Intent, result: WallpaperResult) {
            ResultReturner.returnData(context, intent) { out ->
                out.append(result.message).append("\n")
                result.error?.let { out.append(it).append("\n") }
                out.flush()
                out.close()
            }
        }

        override fun onBind(intent: Intent): IBinder? = null
    }

    internal class WallpaperResult {
        var message: String = ""
        var error: String? = null
        var wallpaper: Bitmap? = null
    }
}
