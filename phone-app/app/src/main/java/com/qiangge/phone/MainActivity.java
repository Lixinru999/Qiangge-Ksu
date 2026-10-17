package com.qiangge.phone;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 「电话」—— 强哥的拨号 / 密令入口。
 *
 * 它的用处：管理器开了「隐身模式」后，界面会伪装成"未安装"，桌面上那个图标点开也是
 * 一副没 root 的样子。想解除隐身，就得用拨号盘输入密令（默认 {@code *#*#626725#*#*}）。
 *
 * 这个 App 就是这个入口：它把输入的密令通过一条<b>非受保护</b>的自定义广播
 * {@code com.qiangge.ksu.action.STEALTH_CODE} <b>定向（setPackage）</b>发给管理器，
 * 管理器校验发送方「与本 App 同一签名」+ 密令数字后，就会关闭隐身、恢复真正的界面。
 *
 * （不能用标准的 {@code android.provider.Telephony.SECRET_CODE}：那条是受保护广播，
 *   普通 App 发出去会抛 SecurityException，见 {@link #sendSecret}。）
 */
public class MainActivity extends Activity {

    /** 标准密令写法：*#*#<数字>#*#*（AOSP 拨号盘的 secret code 格式） */
    private static final Pattern SECRET_WRAPPED =
            Pattern.compile("^\\*#\\*#(\\d{4,12})#\\*#\\*$");

    /** 另一种更短的写法：*#<数字>#*#* */
    private static final Pattern SECRET_SHORT =
            Pattern.compile("^\\*#(\\d{4,12})#\\*#\\*$");

    private static final int REQ_CALL_PHONE = 1001;

    /** 管理器包名 —— 密令广播必须「定向」发给它，理由见 {@link #sendSecret} */
    private static final String MANAGER_PACKAGE = "com.qiangge.ksu";

    /** 管理器侧 StealthReceiver 收的自定义 action（非受保护广播，见其源码注释） */
    private static final String ACTION_STEALTH_CODE = MANAGER_PACKAGE + ".action.STEALTH_CODE";

    /** 管理器主界面组件：applicationId / 类名（管理器 namespace 是 com.sevenk.core） */
    private static final String MANAGER_ACTIVITY = "com.sevenk.core.ui.MainActivity";

    /**
     * 拉起管理器时带的附加项：表示"这次是已授权的「电话」用 root 恢复并打开的"。
     * 必须与管理器侧 {@code Stealth.EXTRA_FROM_PHONE_RESTORE} **逐字符一致**。
     */
    private static final String EXTRA_FROM_PHONE_RESTORE = "from_phone_restore";

    /** 密令落盘位置（与管理器 StealthCodeStore 一致，卸载重装不丢） */
    private static final String CODE_PATH = "/data/adb/sevenk/stealth_code";
    private static final String LEGACY_CODE_PATH = "/data/adb/ksu/stealth_code";

    /** 默认密令（与管理器 StealthCodeStore.DEFAULT_CODE 一致） */
    private static final String DEFAULT_CODE = "626725";

    /** 旧版默认密令：读到时按新默认值处理（与管理器 StealthCodeStore 的迁移一致） */
    private static final String OLD_DEFAULT_CODE = "70707";

    /** root 命令执行超时（毫秒）。超过就当作"没有 root"，回退广播通道 */
    private static final long ROOT_TIMEOUT_MILLIS = 12_000L;

    private EditText input;

    /** 等用户授权电话权限后要拨的号码 */
    private String pendingNumber;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    // ------------------------------------------------------------------ UI

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(24), dp(16), dp(16));

        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextSize(26);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setText(R.string.hint);
        hint.setTextSize(12);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(10), 0, dp(10));
        root.addView(hint);

        input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_PHONE);
        input.setTextSize(28);
        input.setGravity(Gravity.CENTER);
        input.setHint(R.string.input_hint);
        input.setSingleLine(true);
        root.addView(input, matchWrap());

        addKeypad(root);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(10), 0, 0);

        Button secret = new Button(this);
        secret.setText(R.string.key_secret);
        secret.setOnClickListener(v -> onSecret());
        actions.addView(secret, weight1());

        Button dial = new Button(this);
        dial.setText(R.string.key_dial);
        dial.setOnClickListener(v -> onDial());
        actions.addView(dial, weight1());

        root.addView(actions, matchWrap());

        setContentView(root);
    }

    private void addKeypad(LinearLayout root) {
        String[][] rows = {
                {"1", "2", "3"},
                {"4", "5", "6"},
                {"7", "8", "9"},
                {"*", "0", "#"},
        };
        for (String[] row : rows) {
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            for (String key : row) {
                Button b = new Button(this);
                b.setText(key);
                b.setTextSize(20);
                b.setOnClickListener(v -> append(key));
                line.addView(b, weight1());
            }
            root.addView(line, matchWrap());
        }

        Button back = new Button(this);
        back.setText(R.string.key_back);
        back.setTextSize(14);
        back.setOnClickListener(v -> backspace());
        root.addView(back, matchWrap());
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams weight1() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(4), dp(4), dp(4), dp(4));
        return lp;
    }

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }

    // -------------------------------------------------------------- actions

    private void append(String key) {
        input.append(key);
    }

    private void backspace() {
        String text = input.getText().toString();
        if (text.isEmpty()) {
            return;
        }
        input.setText(text.substring(0, text.length() - 1));
        input.setSelection(input.getText().length());
    }

    /** 「拨号」：是密令就走密令，否则当普通号码拨出去。 */
    private void onDial() {
        String raw = input.getText().toString().trim();
        if (raw.isEmpty()) {
            toast(R.string.toast_empty);
            return;
        }
        String secret = extractSecret(raw);
        if (secret != null) {
            sendSecret(secret);
            return;
        }
        dialNumber(raw);
    }

    /** 「密令」：把当前输入里的数字直接当作密令发出去（不用写 *#*# 包裹）。 */
    private void onSecret() {
        String digits = input.getText().toString().replaceAll("[^0-9]", "");
        if (digits.isEmpty()) {
            toast(R.string.toast_need_digits);
            return;
        }
        sendSecret(digits);
    }

    /** 发隐身密令给管理器。
     *
     * 有两条通道，**优先走第 ① 条**：
     *
     * ① <b>root 通道</b>（管理器开了「彻底隐藏」后用这条）：管理器被系统级 {@code pm hide}
     *    隐藏后，整个包（含拨号密令接收器）都失效，**广播根本投递不进去**。所以这里直接用
     *    root 执行 {@code pm unhide com.qiangge.ksu} + {@code am start} 把管理器恢复并拉起。
     *    用 root 的前提是管理器已给本 App 写入**永久 root 授权**（见 [ROOT_TIMEOUT_MILLIS] 注释）。
     *
     * ② <b>广播通道</b>（老通道，管理器只是隐身、没被 {@code pm hide} 时用这条）：发一条
     *    管理器开的自定义<b>非受保护</b>广播，管理器按「同签名」放行并自行关隐身、拉起界面。
     *
     * ⚠️ 不能用标准的 {@code android.provider.Telephony.SECRET_CODE}：它是 AOSP 的
     * <b>受保护广播</b>（framework 的 {@code <protected-broadcast>} 里写着），只有系统 /
     * 电话进程发得出去，普通 App 一发就抛 SecurityException —— 直接闪退。
     *
     * ⚠️ 广播必须 {@code setPackage} 定向发给管理器。Android 8.0（API 26）起，清单里声明的
     * receiver <b>收不到「隐式广播」</b>—— 官方文档原文：「apps that target the API level 26
     * or higher can't register broadcast receivers for implicit broadcasts in their manifest
     * <b>unless the broadcast is sent specifically to them</b>」。
     * 我们这条自定义 action 不在官方「隐式广播豁免名单」里，不定向就会被系统直接丢掉。
     */
    private void sendSecret(final String digits) {
        Toast.makeText(this, R.string.toast_secret_sending, Toast.LENGTH_SHORT).show();
        // root shell 往返要时间，且 su 没被授权时会挂住等用户点确认 —— 必须离开主线程，
        // 免得卡住界面（ANR）。结果回主线程再提示 / 回退广播。
        new Thread(() -> {
            final RootOutcome outcome = rootRestore(digits);
            runOnUiThread(() -> {
                if (outcome == RootOutcome.BAD_CODE) {
                    Toast.makeText(MainActivity.this, R.string.toast_bad_code, Toast.LENGTH_LONG).show();
                    return;
                }
                if (outcome == RootOutcome.RESTORED) {
                    Toast.makeText(
                            MainActivity.this,
                            getString(R.string.toast_restored) + briefWarn(rootDetail),
                            Toast.LENGTH_LONG).show();
                }
                // ⚠️ 无论走没走通 root，最后**都**再发一次定向广播 ——「关隐身 + 从后台拉起管理器界面」
                //    这个动作由**管理器侧的 StealthReceiver** 完成：它持有 SYSTEM_ALERT_WINDOW
                //    （「显示在其他应用上层」）权限，是 Android 10+ 后台启动 Activity 限制的
                //    **官方豁免项**。以前这条路直接由 root `am start` 干，实测会被系统静静拦掉 ——
                //    于是出现用户反馈的那种"电话提示已恢复、管理器却根本没打开"。
                //    root 在这里只负责把被 `pm hide` 隐藏的包 `pm unhide` 回来（隐藏时广播投递不进去）。
                sendSecretBroadcast(digits);
            });
        }, "qiangge-root-restore").start();
    }

    /** root 恢复的结果三分：密令正确已解除隐藏 / 密令不对 / 没有 root（直接走广播） */
    private enum RootOutcome { RESTORED, BAD_CODE, NO_ROOT }

    /** 最近一次 root 命令的原始输出（只为诊断：`pm unhide` / `am start` 的报错见 [briefWarn]） */
    private String rootDetail = "";

    /**
     * 从 root 命令的原始输出里挑出**明确的报错**，拼成一句给用户看的备注；没有报错就返回空串。
     *
     * 为什么需要：脚本里 `pm unhide` / `am start` 的失败输出会被一并吞进 7K_OK 的标记里。
     * 以前不看它，就成了"提示已恢复、界面却没开"的谎报；现在只要有明显报错就把原话带出来。
     */
    private static String briefWarn(String out) {
        if (out == null) {
            return "";
        }
        String low = out.toLowerCase(java.util.Locale.ROOT);
        if (!low.contains("error") && !low.contains("denied")
                && !low.contains("exception") && !low.contains("unknown")) {
            return "";
        }
        String one = out.replaceAll("\\s+", " ").trim();
        if (one.length() > 160) {
            one = one.substring(0, 160) + "…";
        }
        return "（" + one + "）";
    }

    /**
     * 尝试用 root 处理密令：读密令 → 比对 → 对了才 {@code pm unhide}（并把管理器拉一下作兜底）。
     *
     * 密令在**同一段 root shell** 里读并比对，只有比对通过才会动手 —— 所以"输入正确的密令"才有效。
     * 读密令优先 {@link #CODE_PATH}（能跨卸载），再兜底旧路径 {@link #LEGACY_CODE_PATH}，
     * 都读不到才用默认 {@link #DEFAULT_CODE}（与内核侧 {@code StealthCodeStore} 的并集逻辑一致）。
     *
     * ⚠️ 7K_OK 标记**放在 `pm unhide` 之前**：这样即使后面的命令输出报错，标记也一定在，
     *    我们照样能判定"密令正确"，报错则由 [briefWarn] 单独拎出来提示。
     * ⚠️ `pm unhide` / `am start` 的输出**不再丢弃**（以前 `>/dev/null 2>&1`）——它们正是
     *    "界面没打开"时的唯一线索（`am start` 常被 Android 后台启动限制拦下并打印 Error）。
     *
     * 命令写成**单行**（用 {@code ;} 连接）：su 会把 {@code -c} 之后的参数拼成命令，
     * 多行脚本里的换行可能被吞掉，单行最稳。
     */
    private RootOutcome rootRestore(String digits) {
        // 只留数字：既当密令比对，也避免把别的字符拼进下面的 shell 脚本里
        final String code = digits.replaceAll("[^0-9]", "");
        if (code.isEmpty()) {
            return RootOutcome.NO_ROOT;
        }
        final String script =
                "got=$(cat " + CODE_PATH + " 2>/dev/null); " +
                "[ -z \"$got\" ] && got=$(cat " + LEGACY_CODE_PATH + " 2>/dev/null); " +
                "[ \"$got\" = \"" + OLD_DEFAULT_CODE + "\" ] && got=" + DEFAULT_CODE + "; " +
                "[ -z \"$got\" ] && got=" + DEFAULT_CODE + "; " +
                "if [ \"$got\" = '" + code + "' ]; then " +
                "echo 7K_OK; " +
                "pm unhide " + MANAGER_PACKAGE + " 2>&1; " +
                "am start -n " + MANAGER_PACKAGE + "/" + MANAGER_ACTIVITY +
                " --activity-new-task --activity-clear-task" +
                " --ez " + EXTRA_FROM_PHONE_RESTORE + " true 2>&1; " +
                "else echo 7K_BADCODE; fi";

        final String out = execRoot(script);
        if (out == null) {
            return RootOutcome.NO_ROOT; // 起不了 su / 超时 → 当作没有 root
        }
        rootDetail = out;
        if (out.contains("7K_OK")) {
            return RootOutcome.RESTORED;
        }
        if (out.contains("7K_BADCODE")) {
            return RootOutcome.BAD_CODE;
        }
        return RootOutcome.NO_ROOT;
    }

    /**
     * 跑一段 root 命令（{@code su -c <script>}），返回合并后的输出；失败/超时返回 null。
     *
     * ⚠️ 超时是必须的：本 App 没被授予永久 root 时，{@code su} 不会立刻失败，而是弹授权框
     * 挂在那里等用户点确认 —— 若无限等就一直卡着。超时即当作"没有 root"，回退广播通道。
     */
    private String execRoot(String script) {
        Process process = null;
        try {
            process = new ProcessBuilder("su", "-c", script)
                    .redirectErrorStream(true)
                    .start();
            final Process p = process;
            final StringBuilder out = new StringBuilder();
            Thread reader = new Thread(() -> {
                try (java.io.BufferedReader r = new java.io.BufferedReader(
                        new java.io.InputStreamReader(p.getInputStream()))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        out.append(line).append('\n');
                    }
                } catch (Throwable ignored) {
                    // 读输出出错不影响判定，交给 waitFor 的结果
                }
            });
            reader.setDaemon(true);
            reader.start();
            if (!p.waitFor(ROOT_TIMEOUT_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                return null; // 超时 → 当作没有 root
            }
            reader.join(1000);
            return out.toString();
        } catch (Throwable t) {
            return null;
        } finally {
            if (process != null) {
                try {
                    process.destroy();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 广播通道（老通道）：发自定义非受保护广播，由管理器侧关隐身并拉起界面。 */
    private void sendSecretBroadcast(String digits) {
        Intent intent = new Intent(ACTION_STEALTH_CODE);
        intent.putExtra("code", digits);
        intent.setPackage(MANAGER_PACKAGE);
        try {
            sendBroadcast(intent);
            Toast.makeText(this, R.string.toast_secret_sent, Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            // 正常情况下不该走到这里；真出错就把原因显示出来，别再静默闪退
            Toast.makeText(this, "发送失败：" + t, Toast.LENGTH_LONG).show();
        }
    }

    /** 普通拨号 */
    private void dialNumber(String number) {
        String normalized = number.replaceAll("[^0-9+*#,;]", "");
        if (normalized.isEmpty()) {
            toast(R.string.toast_empty);
            return;
        }
        Uri uri = Uri.parse("tel:" + normalized);
        if (checkSelfPermission(android.Manifest.permission.CALL_PHONE)
                == PackageManager.PERMISSION_GRANTED) {
            startActivity(new Intent(Intent.ACTION_CALL, uri));
        } else {
            pendingNumber = normalized;
            requestPermissions(
                    new String[]{android.Manifest.permission.CALL_PHONE}, REQ_CALL_PHONE);
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_CALL_PHONE || pendingNumber == null) {
            return;
        }
        String number = pendingNumber;
        pendingNumber = null;
        boolean granted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        Uri uri = Uri.parse("tel:" + number);
        if (granted) {
            startActivity(new Intent(Intent.ACTION_CALL, uri));
        } else {
            // 没给电话权限就不要"直接拨"，退回系统拨号盘让用户自己按拨号键
            toast(R.string.toast_no_call_permission);
            startActivity(new Intent(Intent.ACTION_DIAL, uri));
        }
    }

    /** 从拨号串里提取密令数字，不是密令就返回 null。 */
    private String extractSecret(String raw) {
        Matcher wrapped = SECRET_WRAPPED.matcher(raw);
        if (wrapped.matches()) {
            return wrapped.group(1);
        }
        Matcher shortForm = SECRET_SHORT.matcher(raw);
        if (shortForm.matches()) {
            return shortForm.group(1);
        }
        return null;
    }

    /** 处理来自拨号盘的 tel: 意图（点网页里的电话号码等） */
    private void handleIntent(Intent intent) {
        if (intent == null) {
            return;
        }
        String action = intent.getAction();
        if (!Intent.ACTION_DIAL.equals(action)
                && !Intent.ACTION_VIEW.equals(action)
                && !Intent.ACTION_CALL.equals(action)) {
            return;
        }
        Uri data = intent.getData();
        if (data != null && "tel".equals(data.getScheme())) {
            input.setText(data.getSchemeSpecificPart());
        }
    }

    private void toast(int resId) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show();
    }
}
