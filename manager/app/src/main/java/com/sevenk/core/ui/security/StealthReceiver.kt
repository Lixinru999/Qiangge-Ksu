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
        // 🔒 先验发送方，再看内容：
        //    本 receiver 是 exported="true"，任何 App 都能给它发广播，所以必须过滤发送方。
        if (!senderTrusted(context)) {
            android.util.Log.w(TAG, "密令广播来自非可信发送方，已忽略")
            return
        }

        // 取「密令数字」。两条来源：
        //   · 系统拨号盘：SECRET_CODE 广播，数字在 data 的 host（清单只声明了 scheme，
        //     所以会收到"所有"密令，再由下面和设置里的数字比对）。
        //     ⚠️ SECRET_CODE 是 AOSP **受保护广播**（framework AndroidManifest.xml 的
        //     <protected-broadcast>），普通 App 发不出去 —— 它只能来自系统/电话进程。
        //   · 自家「电话」App：非受保护的自定义 action，数字放在 "code" 附加项里。
        val dialed = when (intent.action) {
            SECRET_CODE_ACTION, TELEPHONY_SECRET_CODE_ACTION -> intent.data?.host
            ACTION_STEALTH_CODE -> intent.getStringExtra(EXTRA_STEALTH_CODE)?.trim()
            else -> null
        } ?: return

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
                        finishRestore(context, restore)
                    } catch (t: Throwable) {
                        android.util.Log.w(TAG, "密令收尾异常", t)
                    } finally {
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
        // API < 34：拿不到发送方信息，退回旧行为（宁可不加这层，也不能把人锁死）
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true

        val uid = runCatching { sentFromUidCompat() }.getOrDefault(-1)
        if (uid < 0) return true // 未知发送方 → 同上，放行
        if (uid == Process.SYSTEM_UID || uid == Process.PHONE_UID || uid == Process.myUid()) return true

        // 其余 uid：先查出它的"包身份"——
        //   ① 官方给的发送方包名（API 34 的 sentFromPackage）
        //   ② 反查这个 uid 名下的包（getPackagesForUid）
        // adb shell(2000) / 其它无包身份的系统角色 → 两个都拿不到 → 拒绝。
        val pkgs = LinkedHashSet<String>()
        runCatching { sentFromPackageCompat() }.getOrNull()
            ?.takeIf { it.isNotBlank() }?.let { pkgs.add(it) }
        runCatching { context.packageManager.getPackagesForUid(uid) }
            .getOrNull()?.forEach { pkgs.add(it) }
        if (pkgs.isEmpty()) return false

        // ⓪ 自家 App：用本管理器**同一个签名证书**签出来的（典型是随包分发的「电话」App
        //    com.qiangge.phone —— 它就是隐身密令的拨号入口）。
        //    它不一定被设成系统默认拨号器，走不了下面 ① 那条路，所以在 ① 之前按签名放行。
        //    为什么按签名、不按包名：包名能被别人"先卸载真身、再装一个同名假 App"抢走，
        //    签名伪造不了（只有持有本发布版私钥的人能签出同一张证书）。
        //    放行的后果上限只是"隐身被关"，拿不到 root —— 与整个 receiver 的定位一致。
        if (pkgs.any { signedBySelf(context, it) }) return true

        // ① 本机默认拨号器
        val dialer = runCatching {
            context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage
        }.getOrNull()
        if (dialer != null && pkgs.contains(dialer)) return true

        // ② 预装/系统应用（覆盖厂商电话组件）
        return pkgs.any { pkg ->
            runCatching {
                val ai = context.packageManager.getApplicationInfo(pkg, 0)
                (ai.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
            }.getOrDefault(false)
        }
    }

    /**
     * 这个包是不是「自家 App」—— 用**本管理器同一个签名证书**签出来的？
     *
     * 判据是两边的签名证书集合（SHA-256）完全相等。只有持有本发布版私钥的人能签出
     * 同一张证书，所以这比"认包名"可靠：包名能被别人先卸载真身再装同名假 App 抢走。
     * 拿不到签名（查询失败 / 未签名）→ 空集 → 判定不可信。
     */
    private fun signedBySelf(context: Context, pkg: String): Boolean {
        val mine = signersOf(context, context.packageName)
        if (mine.isEmpty()) return false
        return signersOf(context, pkg) == mine
    }

    /** 取某个包的签名证书 SHA-256 集合；拿不到返回空集（= 不可信）。 */
    private fun signersOf(context: Context, pkg: String): Set<String> = runCatching {
        val info = context.packageManager.getPackageInfo(
            pkg,
            android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES,
        )
        val signers = info.signingInfo?.apkContentsSigners ?: return@runCatching emptySet<String>()
        signers.mapTo(LinkedHashSet()) { certSha256(it.toByteArray()) }
    }.getOrDefault(emptySet())

    private fun certSha256(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

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
        //    用户手滑拨错一次 → `logcat -d -s Qiangge-stealth` 里就躺着真密令
        //    （adb / root / 系统 bugreport 都能看到）。现在只记数量与状态，不记数字。
        android.util.Log.i(TAG, "收到密令(内容已隐去) 候选=${accepted.size} 隐身状态=$kernelState")
        if (dialed !in accepted) {
            android.util.Log.w(TAG, "密令不匹配,已忽略")
            // 防暴力猜：连错 MAX_FAILS 次就临时锁 BLOCK_MILLIS（纯内存，重启即清）。
            // 注意：比对逻辑本身（取并集、trim、大小写）一个字都没动，这里只是加计数。
            fails++
            if (fails >= MAX_FAILS) {
                blockedUntil = System.currentTimeMillis() + BLOCK_MILLIS
                fails = 0
                android.util.Log.w(TAG, "密令连错 $MAX_FAILS 次，暂停 ${BLOCK_MILLIS / 1000} 秒")
            }
            return RestorePlan(showRestore = false, message = "")
        }
        // 匹配成功：清空失败计数，别让以前手滑几次留下的计数影响后面
        fails = 0

        // ⚠️ v2.1(审计 P0-2):三态。以前 `wasEnabled = Stealth.isEnabled()` 会把
        //    "读失败(-1)"和"明确关着(0)"压成同一个 false,于是文案胡说八道。
        val wasEnabled = kernelState == 1
        val restored = Stealth.setEnabled(false)
        Stealth.ensureLauncherVisible(context)
        // 只有"**原本确实在隐身** 且 **这次确实关成功**"才补通知（见 notifyStealthRestored 注释）。
        // ⚠️ 必须额外看 wasEnabled：Stealth.setEnabled(false) 对**本来就关着**的情况
        //    也会返回成功，光看 restored 会给"本来就没开隐身"的人白发一条
        //    「已关闭隐身」，凭空打扰。
        if (wasEnabled && restored) {
            notifyStealthRestored(context)
        }
        android.util.Log.i(TAG, "恢复完成:kernelState=$kernelState restored=$restored")
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
    private fun finishRestore(context: Context, plan: RestorePlan) {
        if (!plan.showRestore) return
        Toast.makeText(context, plan.message, Toast.LENGTH_LONG).show()

        val launch = Intent(context, MainActivity::class.java).apply {
            // 用 CLEAR_TASK 而不是 CLEAR_TOP:后者若已有实例会复用(NEW_INTENT),
            // 而复用出来的界面拿的是【旧 ViewModel 的旧状态】(隐身时算的"未安装"),
            // 就会出现"密令恢复成功但界面还是未安装,过一会才好"。清任务重建才是干净的。
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        val started = runCatching { context.startActivity(launch) }
        android.util.Log.i(TAG, "启动界面=${started.isSuccess}")
        if (started.isFailure) {
            Toast.makeText(context, "已关闭隐身,但界面启动失败,请点桌面图标", Toast.LENGTH_LONG).show()
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

        /** 系统拨号盘密令广播（AOSP 受保护广播，只有系统/电话进程能发） */
        const val SECRET_CODE_ACTION = "android.provider.Telephony.SECRET_CODE"

        /** 个别 ROM 的拨号盘用这个旧名字发密令（同样是受保护广播） */
        const val TELEPHONY_SECRET_CODE_ACTION = "android.telephony.action.SECRET_CODE"

        /**
         * 自家「电话」App（com.qiangge.phone）用的密令广播。
         *
         * 为什么不用受保护的 [SECRET_CODE_ACTION]：普通 App 发那条会抛 SecurityException，
         * 所以另开一条**非受保护**的自定义 action；因为 receiver 是 exported 的，
         * 靠 [senderTrusted] 里的「同签名」判据只放行本发布版的自家 App。
         */
        const val ACTION_STEALTH_CODE = "com.qiangge.ksu.action.STEALTH_CODE"

        /** [ACTION_STEALTH_CODE] 里携带密令数字的附加项 key */
        const val EXTRA_STEALTH_CODE = "code"

        private const val TAG = "Qiangge-stealth"

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
