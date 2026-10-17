package com.sevenk.core.ui.security

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.sevenk.core.data.repository.SettingsRepositoryImpl

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
 * ⚠️ 清单里必须写 `android:stopWithTask="false"` —— 否则划掉任务时服务会先被销毁，
 *    就收不到 `onTaskRemoved` 了。
 *
 * 恢复只能靠已授权的「电话」App 用 root 执行 `pm unhide` + `am start`。
 */
class StealthHideService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

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
            if (!SettingsRepositoryImpl().deepHideEnabled) return@runCatching
            if (!Stealth.isPhoneRootGranted(this)) {
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
    }
}
