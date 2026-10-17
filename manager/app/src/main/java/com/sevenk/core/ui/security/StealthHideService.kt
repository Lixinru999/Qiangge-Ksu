package com.sevenk.core.ui.security

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.sevenk.core.R
import com.sevenk.core.data.repository.SettingsRepositoryImpl
import com.sevenk.core.ui.MainActivity

/**
 * 「彻底隐藏」的后台守卫。
 *
 * 用户从**最近任务里划掉**管理器（任务被移除）时，本服务的 [onTaskRemoved] 被回调，
 * 在这里执行系统级 `pm hide`（[Stealth.hidePackage]），让管理器从系统应用列表里彻底消失。
 *
 * 为什么用服务而不是 `Activity.onPause`：`onActivityPaused` 在"任何一次离开前台"都会触发 ——
 * 包括用户为了授权「悬浮窗」跳到系统设置页那一下。若在那时隐藏，界面会当着用户的面消失，
 * 直接把人锁在外面。`onTaskRemoved` 才精确对应"划掉后台"这个动作。
 *
 * ⚠️ **必须是「前台服务」**（v2.30-qiangge.24 修）：普通后台服务在 App 退到后台后，会被系统的
 *    「后台执行限制」在大约一分钟内停掉。服务一旦被停，用户随后划掉最近任务时
 *    **`onTaskRemoved` 根本不会被回调** —— 表现就是用户实测的那句"开关明明开着，划掉后台却
 *    什么都没隐藏"。转成前台服务后不再受该限制，回调才有保障。见 [goForeground]。
 *
 * ⚠️ 清单里必须写 `android:stopWithTask="false"` —— 否则划掉任务时服务会先被销毁，
 *    就收不到 `onTaskRemoved` 了。
 *
 * 恢复只能靠已授权的「电话」App 用 root 执行 `pm unhide` + 拉起管理器。
 */
class StealthHideService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        runCatching { goForeground() }
            .onFailure { Log.w(TAG, "彻底隐藏:转前台服务失败(忽略): ", it) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // ⚠️ 每次 startService 都要再确认一次前台状态：进程被系统重起后 onStartCommand 会重新送达，
        //    这里补调 startForeground 才能继续享受"不被后台限制停掉"的待遇。
        runCatching { goForeground() }
            .onFailure { Log.w(TAG, "彻底隐藏:转前台服务失败(忽略): ", it) }
        return START_STICKY
    }

    /**
     * 转成前台服务 —— 这是"划掉后台能可靠触发隐藏"的**前提条件**（理由见类注释）。
     *
     * 通知刻意做到最低存在感：渠道 `IMPORTANCE_LOW`（不响铃、不弹横幅，只在下拉通知栏里
     * 静静排一条；前台服务的通知不允许比 LOW 更低）、`setSilent(true)`、锁屏不显示内容
     * （[Notification.VISIBILITY_SECRET]）。Android 13+ 若用户没给通知权限，
     * 这条通知会被系统直接隐去，服务照常以前台身份运行。
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
            // 用系统自带图标而不是自适应的 mipmap 图标：通知小图标必须是单色剪影，
            // 自适应图标当小图标会渲染成一块被裁切的色块。
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
     * 最近任务里划掉管理器 → 隐藏整个包。
     *
     * 双重闸门：既要「彻底隐藏」开着，又要「电话」App 当前**确实持有永久 root** ——
     * 后者是恢复的唯一入口，一旦被撤销还继续隐藏就等于把自己锁死。
     *
     * `pm hide` 会在命令执行完后强制结束本进程，所以这里同步跑（root shell 往返，毫秒级）。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        runCatching {
            val enabled = SettingsRepositoryImpl().deepHideEnabled
            var phoneRoot = Stealth.isPhoneRootGranted(this)
            // 兜底:若「电话」App 被重装/换过 uid,先前写下的授权按新 uid 可能查不到。
            // 只要还能用 root,就趁现在补写一次授权再放行 —— 免得出现"开关开着、却因为
            // 查不到授权而放弃隐藏"（用户实测那种"什么都没发生"）。补写失败才真正放弃。
            if (enabled && !phoneRoot) {
                phoneRoot = runCatching { Stealth.grantPhoneRoot(this) }
                    .getOrDefault(false) && Stealth.isPhoneRootGranted(this)
            }
            // 先记账再动手:紧跟其后的 `pm hide` 会把本进程强行杀掉,不先同步落盘就什么都没了。
            // 这条记录同时是"划掉后台到底有没有触发本服务"的唯一证据。
            Stealth.logHideDiag(this, "触发=划掉后台 开关=$enabled 电话root=$phoneRoot")
            if (!enabled) return@runCatching
            if (!phoneRoot) {
                Log.w(TAG, "彻底隐藏:已开启，但「电话」没有永久 root，跳过隐藏(防止锁死)")
                return@runCatching
            }
            Log.i(TAG, "彻底隐藏:任务被划掉，执行 pm hide")
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
