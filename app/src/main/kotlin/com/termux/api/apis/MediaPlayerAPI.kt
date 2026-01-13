package com.termux.api.apis

import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.os.IBinder
import android.os.PowerManager
import com.termux.api.util.ResultReturner
import com.termux.shared.logger.Logger
import java.io.File
import java.io.IOException

/**
 * API that enables playback of standard audio formats such as:
 * mp3, wav, flac, etc... using Android's default MediaPlayer
 */
object MediaPlayerAPI {

    private const val LOG_TAG = "MediaPlayerAPI"

    /**
     * Starts our MediaPlayerService
     */
    @JvmStatic
    fun onReceive(context: Context, intent: Intent) {
        Logger.logDebug(LOG_TAG, "onReceive")

        // Create intent for starting our player service and make sure
        // we retain all relevant info from this intent
        val playerService = Intent(context, MediaPlayerService::class.java).apply {
            action = intent.action
            putExtras(intent.extras!!)
        }

        context.startService(playerService)
    }

    /**
     * Converts time in seconds to a formatted time string: HH:MM:SS
     * Hours will not be included if it is 0
     */
    @JvmStatic
    fun getTimeString(totalSeconds: Int): String {
        val hours = totalSeconds / 3600
        val mins = (totalSeconds % 3600) / 60
        val secs = totalSeconds % 60

        return buildString {
            // only show hours if we have them
            if (hours > 0) {
                append(String.format("%02d:", hours))
            }
            append(String.format("%02d:%02d", mins, secs))
        }
    }

    /**
     * All media functionality exists in this background service
     */
    class MediaPlayerService : Service(), MediaPlayer.OnErrorListener, MediaPlayer.OnCompletionListener {

        override fun onBind(intent: Intent): IBinder? = null

        /**
         * Returns our MediaPlayer instance and ensures it has all the necessary callbacks
         */
        private fun getMediaPlayer(): MediaPlayer {
            return mediaPlayer ?: MediaPlayer().apply {
                setOnCompletionListener(this@MediaPlayerService)
                setOnErrorListener(this@MediaPlayerService)
                setWakeMode(applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
                setVolume(1.0f, 1.0f)
                mediaPlayer = this
            }
        }

        /**
         * What we received from TermuxApiReceiver but now within this service
         */
        override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
            Logger.logDebug(SERVICE_LOG_TAG, "onStartCommand")

            val command = intent.action
            val player = getMediaPlayer()
            val context = applicationContext

            // get command handler and display result
            val handler = getMediaCommandHandler(command)
            val result = handler.handle(player, context, intent)
            postMediaCommandResult(context, intent, result)

            return START_NOT_STICKY
        }

        override fun onDestroy() {
            Logger.logDebug(SERVICE_LOG_TAG, "onDestroy")
            super.onDestroy()
            cleanUpMediaPlayer()
        }

        override fun onError(mediaPlayer: MediaPlayer, what: Int, extra: Int): Boolean {
            Logger.logVerbose(SERVICE_LOG_TAG, "onError: what: $what, extra: $extra")
            return false
        }

        override fun onCompletion(mediaPlayer: MediaPlayer) {
            hasTrack = false
            mediaPlayer.reset()
        }

        companion object {
            private const val SERVICE_LOG_TAG = "MediaPlayerService"
            
            private var mediaPlayer: MediaPlayer? = null
            
            // do we currently have a track to play?
            private var hasTrack: Boolean = false
            
            private var trackName: String = ""

            /**
             * Releases MediaPlayer resources
             */
            private fun cleanUpMediaPlayer() {
                mediaPlayer?.apply {
                    stop()
                    release()
                }
                mediaPlayer = null
            }

            private fun getMediaCommandHandler(command: String?): MediaCommandHandler {
                return when (command ?: "") {
                    "info" -> infoHandler
                    "play" -> playHandler
                    "pause" -> pauseHandler
                    "resume" -> resumeHandler
                    "stop" -> stopHandler
                    else -> MediaCommandHandler { _, _, _ ->
                        MediaCommandResult().apply {
                            error = "Unknown command: $command"
                        }
                    }
                }
            }

            /**
             * Returns result of executing a media command to termux
             */
            private fun postMediaCommandResult(context: Context, intent: Intent, result: MediaCommandResult) {
                ResultReturner.returnData(context, intent) { out ->
                    out.append(result.message).append("\n")
                    result.error?.let { out.append(it).append("\n") }
                    out.flush()
                    out.close()
                }
            }

            /**
             * Creates string showing current position in active track
             */
            private fun getPlaybackPositionString(player: MediaPlayer): String {
                val duration = player.duration / 1000
                val position = player.currentPosition / 1000
                return "${getTimeString(position)} / ${getTimeString(duration)}"
            }

            /**
             * -----
             * Media Command Handlers
             * -----
             */

            private val infoHandler = MediaCommandHandler { player, _, _ ->
                MediaCommandResult().apply {
                    message = if (hasTrack) {
                        val status = if (player.isPlaying) "Playing" else "Paused"
                        "Status: $status\nTrack: $trackName\nCurrent Position: ${getPlaybackPositionString(player)}"
                    } else {
                        "No track currently!"
                    }
                }
            }

            private val playHandler = MediaCommandHandler { player, _, intent ->
                MediaCommandResult().apply {
                    val filePath = intent.getStringExtra("file")
                    if (filePath == null) {
                        error = "No file was specified"
                        return@MediaCommandHandler this
                    }

                    val mediaFile = File(filePath)

                    if (hasTrack) {
                        player.stop()
                        player.reset()
                        hasTrack = false
                    }

                    try {
                        player.setDataSource(mediaFile.canonicalPath)
                        player.prepare()
                    } catch (e: IOException) {
                        error = e.message
                        return@MediaCommandHandler this
                    }

                    player.start()
                    hasTrack = true
                    trackName = mediaFile.name
                    message = "Now Playing: $trackName"
                }
            }

            private val pauseHandler = MediaCommandHandler { player, _, _ ->
                MediaCommandResult().apply {
                    message = when {
                        !hasTrack -> "No track to pause"
                        player.isPlaying -> {
                            player.pause()
                            "Paused playback"
                        }
                        else -> "Playback already paused"
                    }
                }
            }

            private val resumeHandler = MediaCommandHandler { player, _, _ ->
                MediaCommandResult().apply {
                    if (hasTrack) {
                        val positionString = "Track: $trackName\nCurrent Position: ${getPlaybackPositionString(player)}"
                        message = if (player.isPlaying) {
                            "Already playing track!\n$positionString"
                        } else {
                            player.start()
                            "Resumed playback\n$positionString"
                        }
                    } else {
                        message = "No previous track to resume!\nPlease supply a new media file"
                    }
                }
            }

            private val stopHandler = MediaCommandHandler { player, _, _ ->
                MediaCommandResult().apply {
                    message = if (hasTrack) {
                        player.stop()
                        player.reset()
                        hasTrack = false
                        "Stopped playback\nTrack cleared"
                    } else {
                        "No track to stop"
                    }
                }
            }
        }
    }

    /**
     * Interface for handling media commands
     */
    fun interface MediaCommandHandler {
        fun handle(player: MediaPlayer, context: Context, intent: Intent): MediaCommandResult
    }

    /**
     * Simple POJO to store the result of executing a media command
     */
    class MediaCommandResult {
        var message: String = ""
        var error: String? = null
    }
}
