package com.sevenk.core.ui.security

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.sevenk.core.data.repository.SettingsRepositoryImpl

/**
 * 「彻底隐藏」的守卫服务（v2.30-qiangge.28 起不再是前台服务）。
 *
 * ⚠️ 为什么去掉前台服务：在本设备(ZUI)上，前台服务保活后，划掉最近任务既不回调
 *    onTaskRemoved，也不销毁 Activity，appTasks 里任务也不消失 —— 等于系统不发任何
 *    "划掉"信号。根因就是进程不死，ROM 就不触发任何回调。
 *
 *    去掉前台服务后，划掉最近任务时进程会正常死亡。root 看门狗（[Stealth.startDeepHideWatcher]）
 *    用 `pidof` 检测到进程消失，就执行 `pm hide`。这条完全不依赖 Android 生命周期。
 *
 * 本服务现在只保留 onTaskRemoved 作为"快速通道"（万一某个 ROM 上回调正常，能立刻隐藏），
 * 真正可靠的是 root 看门狗。服务本身不做前台保活。
 */
class StealthHideService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    /**
     * 快速通道：若系统回调了 onTaskRemoved，立刻隐藏。
     * 大部分情况下靠 root 看门狗兜底。
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
            if (!phoneRoot) return@runCatching
            Stealth.hidePackage(this)
        }.onFailure { Log.w(TAG, "彻底隐藏:onTaskRemoved 失败(忽略): ", it) }
        super.onTaskRemoved(rootIntent)
    }

    private companion object {
        const val TAG = "7kkernel-stealth"
    }
}
