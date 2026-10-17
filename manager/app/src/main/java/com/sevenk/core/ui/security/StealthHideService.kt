package com.sevenk.core.ui.security

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
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
 * 检测"用户从最近任务里划掉管理器"的**两条独立路径**:
 *   1. [onTaskRemoved] —— 系统回调,最快(划掉立刻触发),但部分 ROM 上不可靠
 *      (尤其经过 `pm hide` 杀进程 + `pm unhide` 恢复之后,可能再也不回调)。
 *   2. [pollRecents] 轮询 —— 在前台服务里每 2 秒查一次 [ActivityManager.getAppTasks],
 *      一旦本应用的任务从最近任务里消失,就执行 `pm hide`。这条不依赖系统回调,
 *      是"划掉后台一定能隐藏"的兜底保障(v2.30-qiangge.26)。
 *
 * 为什么用服务而不是 `Activity.onPause`:`onActivityPaused` 在"任何一次离开前台"都会触发 ——
 * 包括用户为了授权「悬浮窗」跳到系统设置页那一下。若在那时隐藏,界面会当着用户的面消失,
 * 直接把人锁在外面。"任务从最近任务消失"才精确对应"划掉后台"这个动作。
 *
 * ⚠️ **必须是「前台服务」**:普通后台服务在 App 退到后台后,会被系统的
 *    「后台执行限制」在大约一分钟内停掉。服务一旦被停,用户随后划掉最近任务时
 *    **`onTaskRemoved` 根本不会被回调**,轮询也跟着停。转成前台服务后不再受该限制。
 *
 * ⚠️ 清单里必须写 `android:stopWithTask="false"` —— 否则划掉任务时服务会先被销毁,
 *    就收不到 `onTaskRemoved`、轮询也停了。
 *
 * 恢复只能靠已授权的「电话」App 用 root 执行 `pm unhide` + 拉起管理器。
 */
class StealthHideService : Service() {

    private var poller: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        runCatching { goForeground() }
            .onFailure { Log.w(TAG, "彻底隐藏:转前台服务失败(忽略): ", it) }
        Stealth.logHideDiag(this, "守卫服务已启动(前台+轮询)")
        startPolling()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // ⚠️ 每次 startService 都要再确认一次前台状态:进程被系统重起后 onStartCommand 会重新送达,
        //    这里补调 startForeground 才能继续享受"不被后台限制停掉"的待遇。
        runCatching { goForeground() }
            .onFailure { Log.w(TAG, "彻底隐藏:转前台服务失败(忽略): ", it) }
        return START_STICKY
    }

    override fun onDestroy() {
        poller?.cancel()
        poller = null
        super.onDestroy()
    }

    /**
     * 轮询最近任务:每 2 秒查一次 [ActivityManager.getAppTasks]。
     *
     * 本应用的任务还在最近任务里 → 不动;任务消失了(被划掉)→ 执行 `pm hide`。
     * 启动后先等 3 秒,让 App 完全启动、任务进入最近任务,避免一上来就误判。
     */
    private fun startPolling() {
        poller?.cancel()
        poller = CoroutineScope(Dispatchers.Default).launch {
            delay(3000)
            while (isActive) {
                runCatching { checkAndHide() }
                delay(2000)
            }
        }
    }

    private fun checkAndHide() {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
        // appTasks 返回本应用(UID)在最近任务里的任务列表。
        // 用户划掉后台 → 任务从最近任务移除 → 列表变空。
        val tasks = runCatching { am.appTasks }.getOrDefault(emptyList())
        if (tasks.isNotEmpty()) return  // 任务还在,不隐藏

        val enabled = SettingsRepositoryImpl().deepHideEnabled
        var phoneRoot = Stealth.isPhoneRootGranted(this)
        // 兜底:若「电话」App 被重装/换过 uid,先前写下的授权按新 uid 可能查不到。
        // 只要还能用 root,就趁现在补写一次授权再放行。
        if (enabled && !phoneRoot) {
            phoneRoot = runCatching { Stealth.grantPhoneRoot(this) }
                .getOrDefault(false) && Stealth.isPhoneRootGranted(this)
        }
        Stealth.logHideDiag(this, "触发=轮询 任务数=${tasks.size} 开关=$enabled 电话root=$phoneRoot")
        if (!enabled) return
        if (!phoneRoot) {
            Log.w(TAG, "彻底隐藏:已开启,但「电话」没有永久 root,跳过隐藏(防止锁死)")
            return
        }
        Log.i(TAG, "彻底隐藏:轮询检测到任务被划掉,执行 pm hide")
        Stealth.hidePackage(this)
    }

    /**
     * 转成前台服务 —— 这是"划掉后台能可靠触发隐藏"的**前提条件**。
     *
     * 通知刻意做到最低存在感:渠道 `IMPORTANCE_LOW`(不响铃、不弹横幅,只在下拉通知栏里
     * 静静排一条;前台服务的通知不允许比 LOW 更低)、`setSilent(true)`、锁屏不显示内容。
     */
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

    /**
     * 最近任务里划掉管理器 → 隐藏整个包(快速通道)。
     *
     * 双重闸门:既要「彻底隐藏」开着,又要「电话」App 当前**确实持有永久 root**。
     * `pm hide` 会在命令执行完后强制结束本进程,所以这里同步跑。
     */
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
            if (!phoneRoot) {
                Log.w(TAG, "彻底隐藏:已开启,但「电话」没有永久 root,跳过隐藏(防止锁死)")
                return@runCatching
            }
            Log.i(TAG, "彻底隐藏:任务被划掉,执行 pm hide")
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
