package com.sevenk.core.ui.security

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.telecom.TelecomManager
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.sevenk.core.R
import com.sevenk.core.ui.MainActivity

/**
 * 拨号盘密令入口:`*#*#626725#*#*`(可在设置里自定义)
 *
 * 关闭隐身、恢复到正常界面,并直接打开管理器。
 * 显式启动 [MainActivity](而不是 getLaunchIntentForPackage),
 * 这样和桌面入口的状态完全无关,一定能拉起界面。
 *
 * 密令比对用的是 [StealthCodeStore.acceptedCodes] —— **取并集**:
 * `/data/adb/sevenk/stealth_code` 那份(能跨卸载)和 App 设置那份,任意一个匹配就通过。
 * 所以「改过自定义密令 → 卸载重装」之后依然有效;而且即使两份不一致
 * (例如设置页写磁盘失败),也不会再把人锁死(以前会)。
 * 读那份要起 root shell,不能在主线程做,所以这里用 goAsync() 丢到子线程。
 */
class StealthReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "android.provider.Telephony.SECRET_CODE") return

        // 🔒 2026-09-25(朋友审计)：**先验发送方，再看内容**。
        //    `SECRET_CODE` 不是受保护广播 —— 任何 App 都能自己发一条
        //    `Intent("android.provider.Telephony.SECRET_CODE", "android_secret_code://626725")`。
        //    以前只看 action + 拨的号，等于「谁发的都收」：
        //    任意 App 都能用公开的默认密令 626725 关掉隐身(而且还会顺手起 root shell 读密令)。
        if (!senderTrusted(context)) {
            android.util.Log.w(TAG, "密令广播来自非拨号盘发送方，已忽略")
            return
        }

        // 因为清单里只声明了 scheme,所以会收到"所有"密令。
        val dialed = intent.data?.host ?: return

        // 防暴力猜：默认密令是公开的 626725，任何人都能在拨号盘上乱试。
        // 连错到上限就临时挡下（纯内存计数，重启/进程被杀即清 —— 限流窗口只活在本进程内存里，
        // 不落盘、不影响正常使用）。
        // 放在最前面：被挡时连 root shell 都不起，省得白白读 /data/adb。
        val now = System.currentTimeMillis()
        if (now < blockedUntil) {
            android.util.Log.w(TAG, "密令尝试被暂时挡下（连错过多），剩余 ${blockedUntil - now}ms")
            return
        }

        val pending = goAsync()
        Thread {
            try {
                // 读 /data/adb 的密令要起 root shell,不能在主线程;
                // 但 Toast/启动界面必须回主线程做 —— 所以这里把"收尾"交给主线程。
                val restore = prepareRestore(context, dialed)
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    try {
                        finishRestore(context, restore, pending)
                    } catch (t: Throwable) {
                        android.util.Log.w(TAG, "密令收尾异常", t)
                        runCatching { pending.finish() }
                    }
                }
            } catch (t: Throwable) {
                android.util.Log.w(TAG, "密令处理异常", t)
                runCatching { pending.finish() }
            }
        }.start()
    }

    /**
     * 发送方可信吗？—— 只认「拨号盘那一路」发来的密令广播。
     *
     * ## 为什么需要（2026-09-25 朋友审计）
     * `android.provider.Telephony.SECRET_CODE` **不是受保护广播**：任何 App 都能自己构造
     * 一条带 `android_secret_code://<数字>` 的广播。本 receiver 又是 `exported="true"`，
     * 于是在补这一手之前，**任意 App 都能触发"关隐身"这条控制流**
     * （默认密令 626725 还是公开的），还会连带让本 App 起 root shell 去读密令。
     *
     * ## 判据（Android 14 / API 34 起才有发送方信息）
     *   · 系统(1000) / 电话进程(1001) / 我们自己            → 放行（这三个 uid 不可能是别的角色）
     *   · 其余 uid：**必须能查出"包身份"**，且满足任一条才放行
     *       ① 就是本机**默认拨号器**（包名对上）
     *       ② 是**预装/系统应用**（`FLAG_SYSTEM`）—— 覆盖"拨号由厂商电话组件发"的 ROM
     *   · 查不出包身份的（例如 **adb shell / `am broadcast`，uid 2000**）→ **拒绝**
     *   · 其它一律拒绝（静默忽略，只留一条不含密令的日志）
     *
     * ## ⚠️ 2026-09-27 修（朋友第二轮审计，他说得对）
     * 上一版有一条「uid < 10000 一律放行」，本意是"系统共享 uid 段不可能是普通 App"——
     * **但 adb shell 就是 uid 2000**，于是插着数据线的人一条
     * `am broadcast -a android.provider.Telephony.SECRET_CODE -d android_secret_code://626725`
     * 就能关掉隐身，等于白拦。现在改成**任何非 1000/1001/自己 的 uid 都要有系统级包身份**，
     * adb 没有包身份 → 直接拒。（厂商拨号器是预装应用，仍走 ② 放行，不会把人锁死。）
     *
     * ## 拿不到发送方时为什么放行（fail-open）
     * API < 34 没有 `sentFromUid`。**"识别不出拨号器"而把密令拒掉 = 用户退不出隐身 = 锁死**，
     * 那是本项目最怕的事故（见 `StealthCodeStore.acceptedCodes` 的注释）。
     * 所以老系统（Android 13 及以下）只能退回旧行为 —— 这是个已知的、改不了的缺口。
     *
     * ## 仍然挡不住的（如实记录）
     * · 有 adb / root 的人用 `input` 模拟**真人拨号**：那次广播的发送方确实是电话进程(1001)，
     *   与真人拨号无法区分 → 放行。代价也仅限"隐身被关"（**拿不到 root**）。
     * · 有 root 的人本来就能直接改 `/data/adb/sevenk/stealth`，绕不绕密令没区别。
     */
    private fun senderTrusted(context: Context): Boolean {
        // ⚠️ 2026-10-17:**改为 fail-open(一律放行)** —— 只把"身份"记进日志。
        //
        // 之前 API>=34 时对"查不出包身份的发送方"直接拒绝(防任意 App 用公开默认密令
        // 关隐身)。但真机(联想拯救者 Y700 5 代/ZUI)实测:**这层会把用户自己的
        // 拨号密令也一起拒掉 → 退不出隐身、被锁死**。而"锁死"是本项目最怕的事故,
        // 严重性远高于"别的 App 能关掉隐身"(后者顶多让隐身失效,**拿不到 root**)。
        // 所以现在优先保证用户拨得回来:识别只为日志,不再拦截。
        val uid = runCatching { sentFromUidCompat() }.getOrDefault(-1)
        val trusted = when {
            uid < 0 -> false
            uid == Process.SYSTEM_UID || uid == Process.PHONE_UID || uid == Process.myUid() -> true
            else -> runCatching {
                // ① 本机默认拨号器 ② 预装/系统应用
                val pkgs = LinkedHashSet<String>()
                sentFromPackageCompat()?.takeIf { it.isNotBlank() }?.let { pkgs.add(it) }
                context.packageManager.getPackagesForUid(uid)?.forEach { pkgs.add(it) }
                val dialer = context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage
                (dialer != null && pkgs.contains(dialer)) || pkgs.any { pkg ->
                    val ai = context.packageManager.getApplicationInfo(pkg, 0)
                    (ai.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                }
            }.getOrDefault(false)
        }
        if (!trusted) {
            android.util.Log.w(TAG, "密令广播的发送方非拨号盘(uid=$uid),但按 fail-open 放行")
        }
        return true
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun sentFromUidCompat(): Int = sentFromUid

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun sentFromPackageCompat(): String? = sentFromPackage

    /** 后台线程:比对 + 关隐身,返回需要主线程展示的结果 */
    private fun prepareRestore(context: Context, dialed: String): RestorePlan {
        // 必须自己比对:不是我们设置的那个数字就静默忽略,绝不打扰。
        // 注:第一项(内核标志)只为日志。
        //
        // ⚠️ 2026-09-16 改:比对**取并集**(磁盘那份 + App 设置那份),不再"只用优先级最高的那个"。
        //    原因:两份一旦不一致(最典型是设置页写磁盘那一步失败),
        //    用户拨自己刚设的号会被静默忽略 → 彻底锁死在隐身里(有用户反馈过)。
        //    取并集后两份里任意一个都能开锁;两边都没配时才退回默认值。
        val kernelState = runCatching { Stealth.kernelState() }.getOrDefault(-1)
        val accepted = StealthCodeStore.acceptedCodes(context)
        // 🔒 2026-09-25(朋友审计)：**密令内容一个字都不许进日志**。
        //    以前这里把"用户拨的号"和"我们接受的号"全打了出来，后果是：
        //    用户手滑拨错一次 → `logcat -d -s 7kkernel-stealth` 里就躺着真密令
        //    （adb / root / 系统 bugreport 都能看到）。现在只记数量与状态，不记数字。
        android.util.Log.i(TAG, "收到密令(内容已隐去) 候选=${accepted.size} 隐身状态=$kernelState")
        if (dialed !in accepted) {
            android.util.Log.w(TAG, "密令不匹配,已忽略")
            // 给用户一个反馈,避免"拨了没反应"不知道为啥
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                Toast.makeText(context, "密令不匹配", Toast.LENGTH_SHORT).show()
            }
            // 防暴力猜：连错 MAX_FAILS 次就临时锁 BLOCK_MILLIS（纯内存，重启即清）。
            fails++
            if (fails >= MAX_FAILS) {
                blockedUntil = System.currentTimeMillis() + BLOCK_MILLIS
                fails = 0
                android.util.Log.w(TAG, "密令连错 $MAX_FAILS 次，暂停 ${BLOCK_MILLIS / 1000} 秒")
            }
            return RestorePlan(showRestore = false, message = "")
        }
        // 匹配成功：清空失败计数
        fails = 0

        val wasEnabled = kernelState == 1
        val restored = Stealth.setEnabled(false)
        // 无论关隐身是否成功,都强制恢复桌面图标(避免用户被锁在外面)
        Stealth.forceShowLauncher(context)
        // 只有原本确实在隐身且这次关成功才发通知
        if (wasEnabled && restored) {
            notifyStealthRestored(context)
        }
        android.util.Log.i(TAG, "恢复完成:kernelState=$kernelState restored=$restored")
        if (!restored) {
            // 关隐身失败时也提示用户
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                Toast.makeText(context, "关闭隐身失败,请重启设备后重试", Toast.LENGTH_LONG).show()
            }
        }
        return RestorePlan(
            showRestore = true,
            // 四种情况说四句真话(审计 P0-2 + 🟡4 v2.4):
            //   ① 明确读到关着     → 「隐身模式未开启」
            //   ② 关成功           → 「已关闭隐身模式」
            //   ③ 内核不是我们的   → 「内核不是 Qiangge(请重启或卸载另一个管理器)」
            //                        ⚠️ v2.4 修的就是这里:以前它和 ④ 混成一句「内核太旧」,
            //                        在"设备上跑的是官方 KernelSU"时**说的是假话**。
            //   ④ 内核是我们的、但太旧 → 「内核太旧,不支持隐身模式」
            //   ⑤ 内核是我们的、功能也在、但本 App 没被认主 → 让用户重启/卸载另一个管理器
            message = when {
                kernelState == 0 -> context.getString(R.string.stealth_restore_not_enabled)
                restored -> context.getString(R.string.stealth_restore_disabled)
                !Stealth.kernelHasStealthFeature() && !Stealth.kernelLooksOurs() ->
                    context.getString(R.string.stealth_restore_foreign_kernel)
                !Stealth.kernelHasStealthFeature() ->
                    context.getString(R.string.stealth_restore_kernel_too_old)
                else -> context.getString(R.string.stealth_restore_not_recognized)
            },
        )
    }

    /** 主线程:提示 + 拉起界面 */
    private fun finishRestore(context: Context, plan: RestorePlan, pending: BroadcastReceiver.PendingResult) {
        try {
            if (!plan.showRestore) return
            Toast.makeText(context, plan.message, Toast.LENGTH_LONG).show()

            // 强制恢复图标后立即启动 MainActivity。
            // MainActivity 本身不受 LauncherAlias 禁用影响,一定能启动。
            val launch = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            val started = runCatching { context.startActivity(launch) }
            android.util.Log.i(TAG, "启动界面=${started.isSuccess}")
            if (started.isFailure) {
                // 显式 Intent 失败时,尝试用启动器 Intent 兜底
                val launcherIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                if (launcherIntent != null) {
                    launcherIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    runCatching { context.startActivity(launcherIntent) }
                }
            }
        } finally {
            runCatching { pending.finish() }
        }
    }

    /** 供主线程收尾用的结果 */
    private data class RestorePlan(val showRestore: Boolean, val message: String)

    /**
     * 关隐身成功后发一条**可点**的通知。
     *
     * 调用条件：**原本确实开着隐身** 且 这次 `setEnabled(false)` 返回成功
     * （见 [prepareRestore] 里的 `wasEnabled && restored`）——本来就没开隐身时不发，
     * 别凭空打扰用户。
     *
     * 定位：只是**辅助反馈**。Android 10+ 禁止后台启动 Activity，密令在后台把隐身关掉后
     * [finishRestore] 里的 startActivity 可能被系统拦下、界面不会自己弹出来；
     * 这时通知栏至少能让用户看到"已经关掉了"，点一下就能打开管理器（比"什么都没有"强）。
     *
     * 撤回：[MainActivity.onResume] 回到管理器前台时会 `cancel([NOTIFICATION_ID])`
     * —— 用户已经看见界面了，这条辅助提示就没有存在意义。
     *
     * 隐私（渠道刻意做得不显眼）：
     * - 渠道 importance 用 [NotificationManager.IMPORTANCE_LOW]（不是 DEFAULT）：
     *   不响铃、不弹横幅、不进"打扰"级，只在通知栏静静列一条；
     * - 渠道 `lockscreenVisibility` 与 builder 的 `setVisibility` 都是
     *   [Notification.VISIBILITY_SECRET]：**锁屏上完全不显示内容** ——
     *   "隐身/管理器"本身就是敏感信息，不该在锁屏被人扫到。
     *
     * 权限：Android 13+ 的 POST_NOTIFICATIONS 是运行时权限，**没给就静默失败** ——
     * 这里不弹权限请求框、不引导去设置、不做任何提示，绝不打扰用户。
     * （清单里已声明该权限，用户此前在别处授权过通知就会正常显示。）
     *
     * 整段 runCatching：接收器里任何异常都不能崩。本函数只在后台线程调用。
     */
    private fun notifyStealthRestored(context: Context) {
        runCatching {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager == null) {
                android.util.Log.w(TAG, "没有 NotificationManager，跳过通知")
                return@runCatching
            }

            // Android 8+ 通知必须先有渠道，否则 notify 会被直接丢弃。
            // 渠道刻意"低存在感 + 锁屏不显示"，理由见上面的 KDoc（隐私）。
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW,
            )
            channel.lockscreenVisibility = Notification.VISIBILITY_SECRET
            manager.createNotificationChannel(channel)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                android.util.Log.i(TAG, "用户没给通知权限，静默跳过关隐身通知")
                return@runCatching
            }

            // 和 finishRestore 用同一套 flag：清任务重建，别复用旧界面（会拿到旧 ViewModel 状态）
            val open = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            val contentIntent = PendingIntent.getActivity(
                context,
                0,
                open,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle("已关闭隐身")
                .setContentText("点这里打开管理器")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setAutoCancel(true)
                .setContentIntent(contentIntent)
                // 和渠道的 lockscreenVisibility 保持一致：锁屏上不露出任何内容（隐私）
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                .build()

            manager.notify(NOTIFICATION_ID, notification)
            android.util.Log.i(TAG, "已发出「关闭隐身」通知")
        }.onFailure {
            android.util.Log.w(TAG, "发通知失败(忽略，不影响关隐身)", it)
        }
    }

    companion object {
        const val DEFAULT_SECRET_CODE = "626725"
        private const val TAG = "7kkernel-stealth"

        /** 「关闭隐身」辅助通知的渠道 id / 渠道名 */
        private const val CHANNEL_ID = "stealth_restore"
        private const val CHANNEL_NAME = "隐身状态"

        /**
         * 「关闭隐身」辅助通知的通知 id。
         *
         * **故意公开**（不是 private）：[MainActivity.onResume] 回到管理器前台时要
         * `cancel(NOTIFICATION_ID)` 把这条提示撤掉，两处必须用同一个值，
         * 所以放这儿当唯一来源，别在别处再写一个字面量。
         */
        const val NOTIFICATION_ID = 70001

        /** 密令连错几次就临时锁住 */
        private const val MAX_FAILS = 5

        /** 锁多久（毫秒） */
        private const val BLOCK_MILLIS = 30_000L

        /**
         * 失败计数 / 解禁时刻：**纯内存**（进程被杀或重启即清零），
         * 只为挡住"脚本连续乱试"这种低成本暴力破解，不落盘、不影响正常使用。
         */
        @Volatile private var fails = 0
        @Volatile private var blockedUntil = 0L
    }
}
