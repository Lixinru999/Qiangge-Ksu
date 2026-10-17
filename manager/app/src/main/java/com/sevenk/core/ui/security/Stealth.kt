package com.sevenk.core.ui.security

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.compose.ui.platform.LocalContext
import com.sevenk.core.Natives
import com.sevenk.core.ui.util.getRootShell
import com.topjohnwu.superuser.ShellUtils

/**
 * 隐身模式 = **只伪装界面**,不动桌面图标。
 *
 * 开启后:内核在 GET_INFO 时不再上报 MANAGER 标志 -> [Natives.isManager] 变 false,
 * 管理器整个界面自动变成"未安装 / 点击安装",超级用户、模块、设置里的 root 项
 * 全部消失。内核真实权限不变,所以本 App 还能自己把这个开关关掉。
 *
 * 关掉之后界面立即恢复成正常的管理器。
 *
 * 关于"隐藏桌面图标"(v0.8~v0.13 的二级隐身):
 * 已废弃。部分定制桌面在入口组件被禁用后不会刷新应用列表,会留下一个
 * 点不动的死图标(点它只会跳到"应用详情"),体验很差,还有把用户锁在
 * 外面的风险。现在改为启动/开机时**主动把桌面入口恢复成启用**,
 * 顺便治好历史上被藏起来的图标。
 */
object Stealth {

    private const val LAUNCHER_ALIAS = "com.sevenk.core.ui.LauncherAlias"

    /** 主界面组件名。永远保持启用 —— 见 [ensureLauncherVisible] 的说明。 */
    private const val MAIN_ACTIVITY = "com.sevenk.core.ui.MainActivity"

    /** 拨号密令接收器组件名。保持启用,否则拨号恢复无从谈起。 */
    private const val STEALTH_RECEIVER = "com.sevenk.core.ui.security.StealthReceiver"

    /** 隐身/彻底隐藏相关日志统一用这个 tag（与 StealthReceiver 等保持一致）。 */
    private const val TAG = "7kkernel-stealth"

    /**
     * 「电话」App 的包名 —— 彻底隐藏之后**唯一**的恢复入口，
     * 也是开启「彻底隐藏」之前必须被授予永久 root 的那个 App。
     */
    const val PHONE_PACKAGE = "com.qiangge.phone"

    /**
     * 拉起本管理器时带的附加项：表示"这次是已授权的「电话」App 用 root 恢复并打开的"。
     *
     * [com.sevenk.core.ui.MainActivity] 读到它就会**关闭隐身模式**，让界面直接是正常的管理器。
     * 必须与 phone-app 发送侧写的 key **逐字符一致**。
     */
    const val EXTRA_FROM_PHONE_RESTORE = "from_phone_restore"

    /**
     * 内核 UAPI 里**隐身功能**是哪一版加进来的。
     *
     * 依据:`uapi/supercall.h` 的版本注释 `5: add stealth get/set`,
     * 同文件 `KERNEL_SU_UAPI_VERSION = 5`。
     * 用来把"内核太旧、根本没这个功能"和"内核有、但本 App 没被认主"分开
     * (审计 P0-2 要求拨号密令的三种文案说真话)。
     */
    private const val UAPI_WITH_STEALTH = 5

    /**
     * 🟢 v2.7：磁盘上那份**持久化**隐身标志（内核写，掉电/卸载重装都不丢）。
     *
     * 必须与内核侧 `kernel/manager/stealth.c` 的 `KSU_STEALTH_PATH` **逐字符一致**；
     * 内核在 POST_FS_DATA 时就是按它加载的（`ksu_stealth_load`）。
     * 用途：内核 ioctl 读不出来（[State.UNKNOWN]）时的兜底信号 —— 见
     * `ui/util/KsuCli.kt::getPersistedStealthFlag` 与 `InstallScreen.kt` 里那段
     * 「隐身时藏起直接安装」的 `when`。
     */
    const val STEALTH_FLAG_PATH = "/data/adb/sevenk/stealth"

    /** 内核里的开关:1 = 开启,0 = 关闭,其它(-1) = 内核不支持 / 读取失败 / 没被认主 */
    fun kernelState(): Int = runCatching { Natives.stealthState() }.getOrDefault(-1)

    /**
     * 隐身的**三态**(v2.1,审计 P0-2)。
     *
     * 以前只有 [isEnabled] 一个布尔(`kernelState() == 1`),把
     * 「明确读到 0(关着)」和「读失败 / 内核不支持」**压成了同一个 false**。
     * 后果很危险:一次 ioctl 读失败 → 界面以为"隐身没开" → 把
     * 「直接安装」「越狱」这些**只有 root 管理器才有的东西**露出来 → 隐身当场穿帮。
     */
    enum class State {
        /** 明确读到 1:隐身开着 */
        ON,

        /** 明确读到 0:隐身关着 */
        OFF,

        /** 读失败 / 内核没这个功能 / 本 App 没被认主 —— **不知道**,一律按"可能开着"办 */
        UNKNOWN,
    }

    /**
     * 读三态。
     *
     * ⚠️ **安全方向**(用户 2026-09-21 审计 P0-2 定的准则):
     * 拿不准时**按"可能开着"处理** —— 宁可不露馅。
     * "以为没隐身 → 露出 root 特征"比"以为隐身了 → 用户多点一下"严重得多。
     */
    fun state(): State = when (kernelState()) {
        1 -> State.ON
        0 -> State.OFF
        else -> State.UNKNOWN
    }

    /**
     * 界面上要不要**藏起 root 特征**(安装页的「直接安装」、越狱按钮、SELinux 卡…)。
     *
     * =「不是明确读到 0」。所以:开着 → 藏;读失败/不支持/没被认主 → **也藏**。
     * 只有"内核明确说隐身是关的"才敢把 root 特征露出来。
     */
    fun looksStealthy(): Boolean = state() != State.OFF

    /** ⚠️ 语义是"读不到就当没开",**不要**用它决定该不该藏 root 特征,用 [looksStealthy] */
    fun isEnabled(): Boolean = kernelState() == 1

    /**
     * 内核到底**有没有**隐身功能(和"认不认主"无关)。
     *
     * 判据是 UAPI 版本:>= [UAPI_WITH_STEALTH] 就说明内核是带隐身那一代。
     * `stealth_get()` 失败时靠它区分两种失败原因(见 [StealthReceiver.prepareRestore]):
     *   · 内核 UAPI < 5 → 「内核太旧,不支持隐身模式」;
     *   · 内核 UAPI >= 5 → 「本 App 现在没被内核认主」。
     * 读不到 UAPI(原生库没加载等)按"没有"处理 —— 给出更保守的那句文案。
     */
    fun kernelHasStealthFeature(): Boolean =
        runCatching { Natives.kernelUAPIVersion >= UAPI_WITH_STEALTH }.getOrDefault(false)

    /**
     * 内核到底**是不是 Qiangge 的**（不看功能新旧）—— 只看"内核认不认这个 App 当管理器"。
     *
     * 🟡4（v2.4）用途：拨号密令关隐身失败时，把两种失败原因分开说：
     *   · [kernelHasStealthFeature] 为 false **且**本函数为 false → 内核**不是 Qiangge 的**
     *     （官方 KernelSU / 别的 root 方案）。以前这里一律说成「内核太旧」，是**误导**；
     *   · [kernelHasStealthFeature] 为 false 但本函数为 true → 内核是我们的，只是**太旧**。
     *
     * 为什么在这条分支上 `isManager` 是可信的（不会被隐身压掉）：
     *   隐身功能本身就是 UAPI 5 才有的；能走到"没有隐身功能"这条分支，说明内核
     *   **报不出 UAPI 5**，也就不可能正开着隐身，于是内核不会去隐藏 MANAGER 标志。
     *
     * 拿不准（JNI 没加载 / ioctl 失败）一律按 false —— 宁可说"内核不是 Qiangge
     * （请重启或卸载另一个管理器）"，那也是安全且正确的下一步。
     */
    fun kernelLooksOurs(): Boolean = runCatching { Natives.isManager }.getOrDefault(false)

    /**
     * 持久化隐身标志:setEnabled 成功时写入,后台/进程被kill时不依赖 JNI 也能判断。
     *
     * 为什么需要它:onTrimMemory 触发时可能进程正被杀,Natives.stealthState() 可能
     * 读失败(-1)。有了这个标志,后台只需读 SharedPreferences(极快、不依赖内核),
     * 就能判断隐身是否开着,决定要不要隐藏桌面图标。
     */
    private const val KEY_STEALTH_ACTIVE = "stealth_active_persisted"

    /** 读持久化隐身标志(不调 JNI,后台安全)。与 SettingsRepositoryImpl 共用 "settings" */
    fun isActivePersisted(context: Context): Boolean =
        runCatching {
            context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getBoolean(KEY_STEALTH_ACTIVE, false)
        }.getOrDefault(false)

    private fun setActivePersisted(context: Context, active: Boolean) {
        runCatching {
            context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_STEALTH_ACTIVE, active).apply()
        }
    }

    fun setEnabled(enabled: Boolean): Boolean {
        val ok = runCatching { Natives.stealthSet(enabled) }.getOrDefault(false)
        if (ok) {
            // 同步持久化标志:用 Application context,后台/重启后仍能读到
            runCatching { setActivePersisted(com.sevenk.core.ksuApp, enabled) }
        }
        return ok
    }

    /**
     * 设置页用:开/关隐身并**把成败说出来**(v2.1,审计 P0-2)。
     *
     * 以前调用点直接 `Stealth.setEnabled(true)` 就接着重建界面 ——
     * 开关失败(内核太旧 / 没被认主)时**一个字都不说**,用户以为开了,
     * 其实界面根本没隐身;更糟的是界面还照常显示成"开着"的样子。
     *
     * @return null = 成功;非 null = 给用户看的原因(调用方必须弹出来)
     */
    fun setEnabledReporting(enabled: Boolean): String? {
        if (setEnabled(enabled)) return null
        val action = if (enabled) "开启" else "关闭"
        // 🟡4（v2.4）：与拨号密令那边**同一套四态判据** ——
        // 「内核不是 Qiangge」和「内核是我们的但太旧」以前会被混成同一句"内核太旧"。
        return when {
            kernelHasStealthFeature() ->
                "${action}隐身失败:本 App 现在没被内核认主。请重启设备,或卸载另一个 Qiangge 管理器后再试"
            kernelLooksOurs() -> "内核太旧,不支持隐身模式"
            else -> "内核不是 Qiangge(当前 root 由别的方案提供):请重启设备,或卸载另一个 root 管理器后再试"
        }
    }

    /**
     * 确保桌面图标在。每次启动、开机、拨号恢复都调一次。
     *
     * ⚠️ 同步执行:拨号恢复时必须在 startActivity 之前恢复图标。
     *
     * ⚠️ 彻底隐藏与隐身模式**彻底解耦**:
     *    · 本函数**只负责"把图标恢复成启用"**,不再自动隐藏 ——
     *      否则恢复图标后,MainActivity.onCreate 一进来又把它藏回去,
     *      用户会看到"恢复完图标又没了"。
     *    · "隐藏整个包"只由 [hidePackage] 在**最近任务里划掉管理器时**触发
     *      (见 [StealthHideService.onTaskRemoved]),与隐身开关无关。
     */
    fun ensureLauncherVisible(context: Context) {
        runCatching {
            val pm = context.packageManager
            // ⚠️ 除了恢复桌面入口,还要**修好可能被历史版本误禁用的组件**:
            //    · v2.15 曾把 MainActivity 也一起禁用 → 包内"没有任何启用状态的 Activity",
            //      联想 ZUI 等 ROM 会把整个包当成**已停用**,**不再投递拨号 SECRET_CODE 广播**
            //      → 拨密令毫无反应、也没有任何日志,正是"彻底锁死"的样子;
            //    · 之后的版本只把 LauncherAlias 找回来了,忘了 MainActivity,
            //      于是禁用状态一直残留。这里一并修好。
            //    ⚠️ 只**启用**,绝不在前台禁用任何组件(禁用 MainActivity 会直接结束当前界面)。
            enableComponent(pm, ComponentName(context.packageName, LAUNCHER_ALIAS))
            enableComponent(pm, ComponentName(context.packageName, MAIN_ACTIVITY))
            enableComponent(pm, ComponentName(context.packageName, STEALTH_RECEIVER))
        }
    }

    /** 把一个组件置为 ENABLED(本来就启用就跳过)。 */
    private fun enableComponent(pm: PackageManager, component: ComponentName) {
        runCatching {
            if (pm.getComponentEnabledSetting(component) !=
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            ) {
                pm.setComponentEnabledSetting(
                    component,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP
                )
            }
        }
    }

    /**
     * 强制恢复桌面图标(不检查隐身/彻底隐藏状态)。
     *
     * 拨号密令恢复时用:此刻隐身已被关掉,但如果持久化标志写入失败,
     * ensureLauncherVisible 可能误判"还在隐身"而不恢复图标,
     * 所以拨号恢复走这个强制路径,一定把图标找回来。
     */
    fun forceShowLauncher(context: Context) {
        runCatching {
            val pm = context.packageManager
            val alias = ComponentName(context.packageName, LAUNCHER_ALIAS)
            if (pm.getComponentEnabledSetting(alias) !=
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            ) {
                pm.setComponentEnabledSetting(
                    alias,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP
                )
            }
        }
    }

    /**
     * 彻底隐藏:用系统级 `pm hide` 把**整个包**从系统应用列表里抹掉。
     *
     * 和早期"只禁用桌面入口组件"的做法不同 —— `pm hide` 之后,设置里的「应用列表」、
     * `pm list packages` 都**查不到**本包,包内所有组件(含拨号密令接收器)一并失效。
     * 这正是用户要的"软件防查级别"隐藏。
     *
     * ⚠️ 只有「电话」App 用 root 执行 `pm unhide` 才能恢复 —— 所以开启前必须先给
     * 「电话」授予永久 root 权限（见 [isPhoneRootGranted] / [grantPhoneRoot]），
     * 否则隐藏了就再也找不回来。
     *
     * 触发点:最近任务里划掉管理器时（[StealthHideService.onTaskRemoved]）。
     * **故意不挂在 `onActivityPaused`** —— 那样一旦用户为了授权「悬浮窗」跳去系统设置页,
     * 界面一 pause 就会立刻把自己隐藏,把人锁在外面。
     *
     * 同步执行(root shell 往返,毫秒级),确保 `pm hide` 把本进程强制结束之前命令已经发出去。
     */
    fun hidePackage(context: Context): Boolean = runCatching {
        val pkg = context.packageName
        val shell = getRootShell()
        val out = ShellUtils.fastCmd(shell, "pm hide $pkg 2>&1").trim()
        val ok = out.contains("true", ignoreCase = true)
        Log.i(TAG, "彻底隐藏:pm hide $pkg -> ${out.ifBlank { "(无输出)" }} ok=$ok")
        // 写自查记录(同步落盘):紧接着 `pm hide` 会强杀本进程,异步写根本来不及。
        logHideDiag(context, "root=${shell.isRoot} pm hide $pkg -> ${out.ifBlank { "(无输出)" }} ok=$ok")
        ok
    }.onFailure {
        Log.w(TAG, "彻底隐藏:pm hide 失败(忽略): ", it)
        logHideDiag(context, "pm hide 异常: $it")
    }.getOrDefault(false)

    /**
     * 追加一条「彻底隐藏」自查记录到设置里（[com.sevenk.core.data.repository.SettingsRepository.deepHideDiag]）。
     *
     * 为什么需要：划掉后台时 App 会被 `pm hide` 直接杀掉，用户在 App 里看不到任何提示，
     * 我们也无从知道"是压根没触发，还是触发了但 `pm hide` 失败"。这条记录**同步落盘**，
     * 事后在设置页就能看到那一次到底发生了什么。
     */
    fun logHideDiag(context: Context, line: String) {
        runCatching {
            val repo = com.sevenk.core.data.repository.SettingsRepositoryImpl()
            val ts = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date())
            val prev = repo.deepHideDiag
            val joined = if (prev.isBlank()) "$ts $line" else "$prev\n$ts $line"
            repo.deepHideDiag = if (joined.length > 600) joined.takeLast(600) else joined
        }.onFailure { Log.w(TAG, "彻底隐藏:写自查记录失败(忽略): ", it) }
    }

    /**
     * 反向操作:把本包从系统级隐藏状态恢复（`pm unhide`）。
     *
     * 「电话」App 用 root 恢复时执行它；管理器侧在**关闭**「彻底隐藏」开关时也调一次兜底
     * （万一之前被 hide 过而开关还留着）。
     */
    fun unhidePackage(context: Context): Boolean = runCatching {
        val pkg = context.packageName
        val out = ShellUtils.fastCmd(getRootShell(), "pm unhide $pkg 2>&1").trim()
        Log.i(TAG, "彻底隐藏:pm unhide $pkg -> ${out.ifBlank { "(无输出)" }}")
        true
    }.onFailure { Log.w(TAG, "彻底隐藏:pm unhide 失败(忽略): ", it) }.getOrDefault(false)

    /**
     * 「电话」App 现在有没有**永久 root**（allowSu）。
     *
     * 这是「彻底隐藏」的**门禁**：没有它就不允许开启 —— 因为 `pm hide` 之后
     * 拨号密令接收器会随包一起失效，没有任何手段能把管理器找回来，等于自己把自己锁死。
     */
    fun isPhoneRootGranted(context: Context): Boolean = runCatching {
        val uid = context.packageManager.getApplicationInfo(PHONE_PACKAGE, 0).uid
        Natives.getAppProfile(PHONE_PACKAGE, uid)?.allowSu == true
    }.getOrDefault(false)

    /**
     * 给「电话」App 写入**永久 root 授权**（allowSu=true），一步到位，不用用户
     * 自己去超级用户页里找。成功返回 true。
     */
    fun grantPhoneRoot(context: Context): Boolean = runCatching {
        val uid = context.packageManager.getApplicationInfo(PHONE_PACKAGE, 0).uid
        val current = Natives.getAppProfile(PHONE_PACKAGE, uid) ?: Natives.Profile(PHONE_PACKAGE)
        Natives.setAppProfile(
            current.copy(name = PHONE_PACKAGE, currentUid = uid, allowSu = true)
        )
    }.onFailure { Log.w(TAG, "彻底隐藏:给「电话」授权 root 失败: ", it) }.getOrDefault(false)

    /** 启动「彻底隐藏」守卫：root 看门狗(pidof 检测)为主，[StealthHideService] 的 onTaskRemoved 为快速通道。 */
    fun startHideService(context: Context) {
        runCatching {
            context.startService(Intent(context, StealthHideService::class.java))
        }.onFailure { Log.w(TAG, "彻底隐藏:启动守卫服务失败(忽略): ", it) }
        // 主机制:root 看门狗用 pidof 检测进程是否存活,死了就 pm hide。
        // 完全不依赖 Android 生命周期回调(本设备 ZUI 上回调全部失效)。
        startDeepHideWatcher(context)
        logHideDiag(context, "看门狗已启动(pidof 模式):划掉后台 3 秒内自动隐藏")
    }

    fun stopHideService(context: Context) {
        runCatching {
            context.stopService(Intent(context, StealthHideService::class.java))
        }.onFailure { Log.w(TAG, "彻底隐藏:停止守卫服务失败(忽略): ", it) }
        stopDeepHideWatcher(context)
    }

    /**
     * root 看门狗脚本的标识串（[pkill -f] 用它来精准杀掉本脚本，不误伤别的进程）。
     *
     * 为什么用这么一串：脚本里必须包含这个串，`pkill -f "sevenk_deep_hide_watchdog"`
     * 才能匹配到；同时它不会出现在别的命令行里，避免误杀。
     */
    private const val WATCHDOG_MARKER = "sevenk_deep_hide_watchdog"

    /**
     * 启动 root 看门狗：**独立于 App 进程**运行，轮询管理器进程是否存活。
     * 当管理器进程消失（被划掉后台 / 被系统回收）时，自动 `pm hide`。
     *
     * v2.30-qiangge.28 重写：之前试过 onTaskRemoved、appTasks 轮询、Activity 生命周期
     * 三种方案，在本设备(ZUI)上全部失效 —— 前台服务保活后，划掉最近任务既不回调
     * onTaskRemoved，也不销毁 Activity，appTasks 里任务也不消失。
     *
     * 根本原因：前台服务让进程不死，ROM 就不发任何"划掉"信号。
     * 解决方案：**去掉前台服务**，让进程在划掉后台时正常死亡；root 看门狗用
     * `pidof` 检测进程是否存活，死了就 `pm hide`。这条完全不依赖 Android 生命周期，
     * 只看进程在不在。
     *
     * 用 `setsid` 脱离 root shell 会话：App 进程死后看门狗仍能跑完最后一次轮询
     * （执行 pm hide），然后自行 exit。
     */
    fun startDeepHideWatcher(context: Context) {
        runCatching {
            val pkg = context.packageName
            // 先清掉可能残留的旧看门狗
            ShellUtils.fastCmd(getRootShell(), "pkill -f '$WATCHDOG_MARKER' 2>/dev/null; true")
            // `$pkg` 由 Kotlin 替换成包名；`${'$'}PKG` 是 shell 变量
            val script = """
# $WATCHDOG_MARKER
PKG="$pkg"
sleep 3
while true; do
  if ! pidof "\$PKG" >/dev/null 2>&1; then
    # 进程没了 → 被划掉后台了(或被系统回收)。若包还可见,就隐藏。
    if pm list packages 2>/dev/null | grep -q "\$PKG"; then
      pm hide "\$PKG" >/dev/null 2>&1
    fi
    exit 0
  fi
  sleep 3
done
""".trimIndent()
            // setsid 新建会话,脱离控制终端;用 Shell.cmd 一次性 shell 执行,
            // 避免 libsu 复用 shell 时把子进程一起带走。
            com.topjohnwu.superuser.Shell
                .cmd("setsid sh -c '$script' >/dev/null 2>&1 &")
                .exec()
            Log.i(TAG, "彻底隐藏:root 看门狗已启动(pidof 模式, pkg=$pkg)")
        }.onFailure { Log.w(TAG, "彻底隐藏:启动 root 看门狗失败(忽略): ", it) }
    }

    /** 停止 root 看门狗（关闭「彻底隐藏」时调用）。 */
    fun stopDeepHideWatcher(context: Context) {
        runCatching {
            ShellUtils.fastCmd(getRootShell(), "pkill -f '$WATCHDOG_MARKER' 2>/dev/null; true")
            Log.i(TAG, "彻底隐藏:root 看门狗已停止")
        }.onFailure { Log.w(TAG, "彻底隐藏:停止 root 看门狗失败(忽略): ", it) }
    }
}

/*
 * ⚠️ 2026-09-21(v0.13.157):这里原来有个 `StealthLook.isPlain()`("隐身期间素颜"),**已删除**。
 *
 * 用户给的判据(原话):
 *   「我指的是背景图片、动画公告这些,这些都是一个**没有 root 的用户**点进来才有的界面」
 * —— 隐身时要等于「一个没有 root 的用户打开这个 App」的**完整体验**:
 *     · 没有 root 的人也看得到 → 壁纸 / 透明度 / 动画 / 字体 / 扁平化 / 主题配色、公告弹窗、
 *       「关于 Qiangge / 支持开发 / 了解 KernelSU」三张卡、点「内核未就绪」弹出的原说明;
 *     · 只有有 root 的人才看得到 → 安装页里的「直接安装(推荐)」「安装到未使用槽位」。
 *
 * 所以"素颜"是**做反了**:个性化和 root 无关,一个没 root 的用户照样有壁纸、有动画。
 * 隐身时**只该改"显示什么内容",不该改"长什么样"**。
 * v0.13.157 起,隐身与正常的外观**完全同一条代码路径**:全工程不再有任何
 * 「隐身 → 不画壁纸 / 关透明 / 关特效 / 关扁平 / 换默认配色」的判断
 * (原来 8 处 `StealthLook.isPlain()` 调用点已逐条撤掉)。
 *
 * 判定隐身与否仍然只看 [Stealth.isEnabled](内容侧用),它内部 runCatching,读不到 = false。
 */

/**
 * 「重建界面」的推荐姿势:先清 ViewModelStore,再 recreate。
 *
 * 为什么要清 ViewModel:ViewModel 会跨 Activity.recreate() 存活(官方设计),
 * 而 MainActivityViewModel / HomeViewModel 的状态都是构造时算一次的 ——
 * 不清的话,界面会拿【旧 VM 的旧状态】重新渲染。
 * 典型症状:关掉隐身/切换界面风格后,界面还是旧样子,要等某次 refresh 才对。
 */
fun restartUiFresh(context: Context) {
    val activity = context.findActivity()
    (activity as? androidx.lifecycle.ViewModelStoreOwner)?.let {
        runCatching { it.viewModelStore.clear() }
    }
    activity?.recreate()
}

/**
 * 从任意 Context 里挖出真正的 Activity。
 * Compose 的 LocalContext 有时是 ContextThemeWrapper 而不是 Activity,
 * `context as? Activity` 会直接失败(recreate 静默不执行),所以必须解包。
 */
tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * 请求「显示在其他应用上层」(SYSTEM_ALERT_WINDOW) 权限 —— 彻底隐藏必需的配套权限。
 *
 * 为什么必需:拨号密令恢复时 [StealthReceiver] 要**从后台** startActivity 打开管理器,
 * 而 Android 10+ 默认禁止后台启动 Activity;持有 SYSTEM_ALERT_WINDOW 的 App 是该限制的
 * 官方豁免项。没有它,用户拨了正确密令,管理器也**不会自动弹出来**(实测原因就是这个)。
 *
 * @return true = 已经拿到权限;false = 还没拿到(已尝试拉起授权页)
 */
fun requestOverlayPermissionIfNeeded(context: Context): Boolean {
    if (android.provider.Settings.canDrawOverlays(context)) return true
    val activity = context.findActivity()
    if (activity == null) return false
    runCatching {
        activity.startActivity(
            android.content.Intent(
                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:${activity.packageName}")
            )
        )
    }.onFailure {
        android.util.Log.w("7kkernel-stealth", "拉起悬浮窗授权页失败(忽略): ", it)
    }
    return false
}

