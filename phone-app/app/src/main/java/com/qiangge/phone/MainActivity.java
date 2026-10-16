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
 * 一副没 root 的样子。想解除隐身，就得用拨号盘输入密令（默认 {@code *#*#70707#*#*}）。
 *
 * 这个 App 就是这个入口：它把输入的密令通过一条<b>非受保护</b>的自定义广播
 * {@code com.qiangge.ksu.action.STEALTH_CODE} 发给管理器，
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
     * ⚠️ 不能用标准的 {@code android.provider.Telephony.SECRET_CODE}：它是 AOSP 的
     * <b>受保护广播</b>（framework 的 {@code <protected-broadcast>} 里写着），只有系统 /
     * 电话进程发得出去，普通 App 一发就抛 SecurityException —— 直接闪退。
     * 所以这里发管理器另外开的一条<b>非受保护</b>自定义广播，管理器按「同签名」放行
     * （见管理器 StealthReceiver 的处理）。
     */
    private void sendSecret(String digits) {
        Intent intent = new Intent("com.qiangge.ksu.action.STEALTH_CODE");
        intent.putExtra("code", digits);
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
