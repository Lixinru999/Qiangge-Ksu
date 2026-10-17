package com.sevenk.core.ui.security

import android.app.Activity
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.sevenk.core.R
import com.sevenk.core.data.repository.SettingsRepositoryImpl
import com.sevenk.core.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 「彻底隐藏」的后台守卫。
 *
 * 检测"用户关闭管理器"的两条独立路径:
 *   1. [onTaskRemoved] —— 系统回调,最快,但部分 ROM 上不可靠(从不回调或回调一次后失效)。
 *   2. Activity 生命周期监听 —— 注册 [Application.ActivityLifecycleCallbacks],
 *      当**所有 Activity 都被销毁**(onDestroy)时,延迟 2 秒确认后执行 `pm hide`。
 *      - 按 Home / 切到别的 App:Activity 只 stop,不 destroy → 不隐藏。
 *      - 划掉最近任务:Activity 被 destroy → 隐藏。
 *      - 旋转屏幕:Activity destroy 后立刻 recreate,2 秒内 live 计数回正 → 不误隐藏。
 *
 * ⚠️ 为什么不用 `ActivityManager.appTasks` 轮询(v2.30-qiangge.26):在本设备上,
 *    `stopWithTask=false` 会让任务在划掉后**仍留在 appTasks 里**,轮询永远判不到"消失",
 *    实测 diag 里只有"守卫服务已启动"、没有任何"触发=轮询"。改用生命周期回调后
 *    不依赖任务列表,Activity 一销毁就知道。
 *
 * ⚠️ **必须是前台服务 + stopWithTask=false**:否则划掉任务时进程被杀,
 *    onDestroy 回调和后续 `pm hide` 都来不及执行。
 */
class StealthHideService : Service() {

    private var hideTimer: Job? = null
    private var liveActivities = 0

    private val lifecycleCallbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            liveActivities++
            // 有新 Activity 创建 → 取消待执行的隐藏(旋转屏幕等场景)
            hideTimer?.cancel()
            hideTimer = null
            Log.d(TAG, "生命周期:onActivityCreated live=$liveActivities")
        }

        override fun onActivityStarted(activity: Activity) {}
        override fun onActivityResumed(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivityStopped(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

        override fun onActivityDestroyed(activity: Activity) {
            liveActivities = (liveActivities - 1).coerceAtLeast(0)
            Log.d(TAG, "生命周期:onActivityDestroyed live=$liveActivities finishing=${activity.isFinishing}")
            if (liveActivities <= 0) {
                Stealth.logHideDiag(
                    this@StealthHideService,
                    "生命周期:所有 Activity 已销毁(live=0),2 秒后尝试隐藏"
                )
                scheduleHide()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        runCatching { goForeground() }
            .onFailure { Log.w(TAG, "彻底隐藏:转前台服务失败(忽略): ", it) }
        runCatching {
            (applicationContext as? Application)
                ?.registerActivityLifecycleCallbacks(lifecycleCallbacks)
        }.onFailure { Log.w(TAG, "彻底隐藏:注册生命周期回调失败(忽略): ", it) }
        Stealth.logHideDiag(this, "守卫服务已启动(前台+生命周期监听)")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        runCatching { goForeground() }
            .onFailure { Log.w(TAG, "彻底隐藏:转前台服务失败(忽略): ", it) }
        return START_STICKY
    }

    override fun onDestroy() {
        hideTimer?.cancel()
        hideTimer = null
        runCatching {
            (applicationContext as? Application)
                ?.unregisterActivityLifecycleCallbacks(lifecycleCallbacks)
        }
        super.onDestroy()
    }

    /**
     * 所有 Activity 销毁后延迟 2 秒再隐藏:
     * 旋转屏幕时 Activity 会 destroy→recreate,2 秒内 live 计数回正就取消隐藏。
     */
    private fun scheduleHide() {
        hideTimer?.cancel()
        hideTimer = CoroutineScope(Dispatchers.Default).launch {
            delay(2000)
            if (liveActivities > 0) return@launch  // 期间有新 Activity 起来了(旋转等),不隐藏
            checkAndHide()
        }
    }

    private fun checkAndHide() {
        val enabled = SettingsRepositoryImpl().deepHideEnabled
        var phoneRoot = Stealth.isPhoneRootGranted(this)
        if (enabled && !phoneRoot) {
            phoneRoot = runCatching { Stealth.grantPhoneRoot(this) }
                .getOrDefault(false) && Stealth.isPhoneRootGranted(this)
        }
        Stealth.logHideDiag(this, "触发=生命周期 开关=$enabled 电话root=$phoneRoot")
        if (!enabled) return
        if (!phoneRoot) {
            Log.w(TAG, "彻底隐藏:已开启,但「电话」没有永久 root,跳过隐藏(防止锁死)")
            return
        }
        Log.i(TAG, "彻底隐藏:所有 Activity 已销毁,执行 pm hide")
        Stealth.hidePackage(this)
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW,
        )
        channel.setShowBadge(false)
        channel.lockscreenVisibility = Notification.VISIBILITY_SECRET
        nm.createNotificationChannel(channel)

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("后台保护运行中")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setContentIntent(contentIntent)
            .build()

        startForeground(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    /** 快速通道:系统回调 onTaskRemoved 时立刻隐藏。 */
    override fun onTaskRemoved(rootIntent: Intent?) {
        runCatching {
            val enabled = SettingsRepositoryImpl().deepHideEnabled
            var phoneRoot = Stealth.isPhoneRootGranted(this)
            if (enabled && !phoneRoot) {
                phoneRoot = runCatching { Stealth.grantPhoneRoot(this) }
                    .getOrDefault(false) && Stealth.isPhoneRootGranted(this)
            }
            Stealth.logHideDiag(this, "触发=划掉后台 开关=$enabled 电话root=$phoneRoot")
            if (!enabled) return@runCatching
            if (!phoneRoot) return@runCatching
            Stealth.hidePackage(this)
        }.onFailure { Log.w(TAG, "彻底隐藏:onTaskRemoved 失败(忽略): ", it) }
        super.onTaskRemoved(rootIntent)
    }

    private companion object {
        const val TAG = "7kkernel-stealth"
        const val CHANNEL_ID = "deep_hide_guard"
        const val CHANNEL_NAME = "后台保护"
        const val NOTIFICATION_ID = 70011
    }
}
