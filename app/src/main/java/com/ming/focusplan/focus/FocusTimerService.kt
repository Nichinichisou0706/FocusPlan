package com.ming.focusplan.focus

import android.app.*
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.ming.focusplan.MainActivity
import kotlin.math.PI
import kotlin.math.sin

class FocusTimerService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var endAt = 0L
    private var segmentDurations = intArrayOf(25 * 60)
    private var segmentBreaks = booleanArrayOf(false)
    private var segmentIndex = 0
    private var taskTitle = "自由专注"
    private var strictRequested = false
    private var pausedRemainingMillis = 0L
    private var paused = false

    private val updateNotification = object : Runnable {
        override fun run() {
            val remaining = ((endAt - System.currentTimeMillis()).coerceAtLeast(0L) / 1_000).toInt()
            if (remaining <= 0) {
                advanceSegment()
                return
            }
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, timerNotification(remaining))
            handler.postDelayed(this, 1_000)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            clearSession()
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_PAUSE) {
            pauseTimer()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_RESUME) {
            resumeTimer()
            return START_NOT_STICKY
        }

        createChannels()
        segmentDurations = intent?.getIntArrayExtra(EXTRA_SEGMENT_DURATIONS)
            ?.filter { it > 0 }?.toIntArray()?.takeIf { it.isNotEmpty() }
            ?: intArrayOf((intent?.getIntExtra(EXTRA_DURATION_SECONDS, 25 * 60) ?: 25 * 60).coerceAtLeast(1))
        segmentBreaks = intent?.getBooleanArrayExtra(EXTRA_SEGMENT_BREAKS)
            ?.takeIf { it.size == segmentDurations.size }
            ?: BooleanArray(segmentDurations.size)
        taskTitle = intent?.getStringExtra(EXTRA_TASK_TITLE)?.takeIf { it.isNotBlank() } ?: "自由专注"
        strictRequested = intent?.getBooleanExtra(EXTRA_STRICT, false) == true
        paused = false
        pausedRemainingMillis = 0L
        segmentIndex = 0
        beginCurrentSegment()
        startForeground(NOTIFICATION_ID, timerNotification(segmentDurations.first()))
        handler.removeCallbacks(updateNotification)
        handler.post(updateNotification)
        return START_NOT_STICKY
    }

    private fun beginCurrentSegment() {
        endAt = System.currentTimeMillis() + segmentDurations[segmentIndex] * 1_000L
        val isBreak = segmentBreaks[segmentIndex]
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putBoolean(KEY_RUNNING, true)
            .putBoolean(KEY_PAUSED, false)
            .putLong(KEY_END_AT, endAt)
            .putInt(KEY_INDEX, segmentIndex)
            .putInt(KEY_TOTAL_SEGMENTS, segmentDurations.size)
            .putInt(KEY_SEGMENT_DURATION_SECONDS, segmentDurations[segmentIndex])
            .putBoolean(KEY_IS_BREAK, isBreak)
            .putString(KEY_TASK_TITLE, taskTitle)
            .putBoolean(KEY_STRICT_ACTIVE, strictRequested && !isBreak)
            .apply()
    }

    private fun pauseTimer() {
        if (paused || !getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_RUNNING, false)) return
        pausedRemainingMillis = (endAt - System.currentTimeMillis()).coerceAtLeast(1_000L)
        paused = true
        handler.removeCallbacks(updateNotification)
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putBoolean(KEY_PAUSED, true)
            .putLong(KEY_PAUSED_REMAINING_MILLIS, pausedRemainingMillis)
            .putBoolean(KEY_STRICT_ACTIVE, false)
            .apply()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, pausedNotification())
    }

    private fun resumeTimer() {
        if (!paused) {
            val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
            if (!prefs.getBoolean(KEY_PAUSED, false)) return
            pausedRemainingMillis = prefs.getLong(KEY_PAUSED_REMAINING_MILLIS, 1_000L).coerceAtLeast(1_000L)
        }
        paused = false
        endAt = System.currentTimeMillis() + pausedRemainingMillis
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putBoolean(KEY_PAUSED, false)
            .putLong(KEY_END_AT, endAt)
            .putBoolean(KEY_STRICT_ACTIVE, strictRequested && !segmentBreaks[segmentIndex])
            .remove(KEY_PAUSED_REMAINING_MILLIS)
            .apply()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, timerNotification((pausedRemainingMillis / 1_000L).toInt()))
        handler.removeCallbacks(updateNotification)
        handler.post(updateNotification)
    }

    private fun advanceSegment() {
        playTransitionMelody()
        if (segmentIndex < segmentDurations.lastIndex) {
            segmentIndex++
            beginCurrentSegment()
            showEventNotification(if (segmentBreaks[segmentIndex]) "休息开始" else "下一轮专注开始", currentPhaseText())
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, timerNotification(segmentDurations[segmentIndex]))
            handler.postDelayed(updateNotification, 1_000)
        } else {
            showEventNotification("番茄计划完成", "$taskTitle · 所有专注段已完成")
            clearSession()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun currentPhaseText(): String {
        val duration = segmentDurations[segmentIndex] / 60
        return if (segmentBreaks[segmentIndex]) "$duration 分钟休息" else "$taskTitle · $duration 分钟"
    }

    private fun createChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(TIMER_CHANNEL, "专注计时", NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel(EVENT_CHANNEL, "专注阶段提醒", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null)
            enableVibration(true)
        })
    }

    private fun timerNotification(seconds: Int): Notification {
        val stopIntent = PendingIntent.getService(
            this, 2, Intent(this, FocusTimerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val openIntent = PendingIntent.getActivity(
            this, 3, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, TIMER_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentIntent(openIntent)
            .setContentTitle(if (segmentBreaks.getOrElse(segmentIndex) { false }) "休息进行中" else "专注进行中")
            .setContentText("%02d:%02d · %s".format(seconds / 60, seconds % 60, currentPhaseText()))
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "结束", stopIntent)
            .build()
    }

    private fun pausedNotification(): Notification {
        val resumeIntent = PendingIntent.getService(
            this, 4, Intent(this, FocusTimerService::class.java).setAction(ACTION_RESUME),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, TIMER_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_pause)
            .setContentTitle("专注计时已暂停")
            .setContentText("${taskTitle} · 返回专注后继续")
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_play, "继续", resumeIntent)
            .build()
    }

    private fun showEventNotification(title: String, text: String) {
        getSystemService(NotificationManager::class.java).notify(
            EVENT_NOTIFICATION_ID,
            NotificationCompat.Builder(this, EVENT_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .build()
        )
    }

    private fun playTransitionMelody() {
        Thread {
            val sampleRate = 44_100
            val noteSeconds = 0.18
            val gapSeconds = 0.04
            val frequencies = doubleArrayOf(523.25, 659.25, 783.99)
            val noteSamples = (sampleRate * noteSeconds).toInt()
            val gapSamples = (sampleRate * gapSeconds).toInt()
            val samples = ShortArray((noteSamples + gapSamples) * frequencies.size)
            frequencies.forEachIndexed { noteIndex, frequency ->
                val offset = noteIndex * (noteSamples + gapSamples)
                for (index in 0 until noteSamples) {
                    val edge = minOf(index / 700.0, (noteSamples - index) / 700.0, 1.0)
                    samples[offset + index] = (sin(2.0 * PI * frequency * index / sampleRate) * Short.MAX_VALUE * 0.22 * edge).toInt().toShort()
                }
            }
            runCatching {
                val track = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(samples.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                track.write(samples, 0, samples.size)
                track.play()
                Thread.sleep(((samples.size * 1_000L) / sampleRate) + 80L)
                track.release()
            }
        }.start()
    }

    private fun clearSession() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putBoolean(KEY_RUNNING, false)
            .putBoolean(KEY_PAUSED, false)
            .putBoolean(KEY_STRICT_ACTIVE, false)
            .remove(KEY_END_AT)
            .apply()
    }

    override fun onDestroy() {
        handler.removeCallbacks(updateNotification)
        clearSession()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val EXTRA_DURATION_SECONDS = "duration_seconds"
        const val EXTRA_SEGMENT_DURATIONS = "segment_durations"
        const val EXTRA_SEGMENT_BREAKS = "segment_breaks"
        const val EXTRA_TASK_TITLE = "task_title"
        const val EXTRA_STRICT = "strict"
        const val PREFS = "focus_timer_state"
        const val KEY_END_AT = "end_at"
        const val KEY_RUNNING = "running"
        const val KEY_PAUSED = "paused"
        const val KEY_PAUSED_REMAINING_MILLIS = "paused_remaining_millis"
        const val KEY_INDEX = "segment_index"
        const val KEY_TOTAL_SEGMENTS = "total_segments"
        const val KEY_SEGMENT_DURATION_SECONDS = "segment_duration_seconds"
        const val KEY_IS_BREAK = "is_break"
        const val KEY_TASK_TITLE = "task_title"
        const val KEY_STRICT_ACTIVE = "strict_active"
        const val ACTION_STOP = "com.ming.focusplan.STOP_TIMER"
        const val ACTION_PAUSE = "com.ming.focusplan.PAUSE_TIMER"
        const val ACTION_RESUME = "com.ming.focusplan.RESUME_TIMER"
        private const val TIMER_CHANNEL = "focus_timer"
        private const val EVENT_CHANNEL = "focus_events"
        private const val NOTIFICATION_ID = 1001
        private const val EVENT_NOTIFICATION_ID = 1002
    }
}
