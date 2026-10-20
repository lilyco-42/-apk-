package com.acooldog.toolbox;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import com.acooldog.toolbox.update.GiteeReleaseChecker;
import com.acooldog.toolbox.update.GiteeReleaseInfo;
import com.acooldog.toolbox.utils.GoUtils;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.noties.markwon.Markwon;
import okhttp3.OkHttpClient;

public class HomeActivity extends BaseActivity {
    private static final String PREF_IGNORED_RELEASE = "pref_ignored_gitee_release";
    private static final String PREF_LAST_AUTO_CHECK_TIME = "pref_last_auto_check_time";
    private static final int SDK_PERMISSION_REQUEST = 127;

    private ExecutorService ioExecutor;
    private SharedPreferences sharedPreferences;
    private OkHttpClient okHttpClient;
    private boolean updateCheckInFlight;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);

        ioExecutor = Executors.newSingleThreadExecutor();
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        okHttpClient = new OkHttpClient();

        findViewById(R.id.card_route_create).setOnClickListener(v -> open(RouteCreateActivity.class));
        findViewById(R.id.card_route_run).setOnClickListener(v -> startRunWithPoints());
        findViewById(R.id.card_route_library).setOnClickListener(v -> open(RouteActivity.class));
        findViewById(R.id.card_route_market).setOnClickListener(v ->
                startActivity(new Intent(this, RouteMarketActivity.class)));
        findViewById(R.id.btn_check_update).setOnClickListener(v -> checkGiteeReleaseUpdate(true));
        findViewById(R.id.btn_settings).setOnClickListener(v -> open(SettingsActivity.class));
        findViewById(R.id.card_mock_status).setOnClickListener(v -> openMockLocationSettings());

        requestMissingPermissions();
        checkGiteeReleaseUpdate(false);
        refreshMockStatus();

        accountView = findViewById(R.id.tv_account_bar);
        if (accountView != null) {
            accountView.setOnClickListener(v -> {
                if (com.acooldog.toolbox.config.WebPointsManager.isLoggedIn(this)) {
                    showAccountDialog();
                } else {
                    showLoginDialog();
                }
            });
        }
        refreshAccountBar();
        checkCardLicense();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshMockStatus();
        refreshAccountBar();
    }

    @Override
    protected void onDestroy() {
        if (ioExecutor != null) {
            ioExecutor.shutdownNow();
        }
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == SDK_PERMISSION_REQUEST) {
            refreshMockStatus();
            if (!allPermissionsGranted(grantResults)) {
                GoUtils.DisplayToast(this, getString(R.string.app_error_permission));
            }
        }
    }

    private void open(Class<?> activityClass) {
        startActivity(new Intent(this, activityClass));
    }

    private void refreshMockStatus() {
        boolean ready = GoUtils.isAllowMockLocation(this);
        View card = findViewById(R.id.card_mock_status);
        TextView title = findViewById(R.id.tv_mock_status_title);
        TextView detail = findViewById(R.id.tv_mock_status_detail);
        TextView action = findViewById(R.id.tv_mock_status_action);
        if (card == null || title == null || detail == null || action == null) {
            return;
        }
        boolean hasLocation = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        card.setClickable(!ready || !hasLocation);
        card.setFocusable(!ready || !hasLocation);
        action.setVisibility(ready && hasLocation ? View.GONE : View.VISIBLE);
        if (ready && hasLocation) {
            card.setBackgroundResource(R.drawable.bg_status_ok);
            title.setText(R.string.home_mock_on_title);
            title.setTextColor(getResources().getColor(R.color.saberCyan, getTheme()));
            detail.setText(R.string.home_mock_on_detail);
            action.setText("");
        } else {
            card.setBackgroundResource(R.drawable.bg_status_warn);
            title.setText(R.string.home_mock_off_title);
            title.setTextColor(getResources().getColor(R.color.saberGold, getTheme()));
            detail.setText(R.string.home_mock_off_detail);
            action.setText(R.string.home_mock_action);
            if (!hasLocation) {
                title.setText(R.string.home_location_needed);
                detail.setText(R.string.home_location_detail);
                action.setText(R.string.home_permission_action);
            }
        }
    }

    private void openMockLocationSettings() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
            return;
        }
        if (GoUtils.isAllowMockLocation(this)) {
            return;
        }
        GoUtils.showEnableMockLocationDialog(this);
    }

    private void requestMissingPermissions() {
        List<String> missing = new ArrayList<>();
        addIfMissing(missing, Manifest.permission.ACCESS_FINE_LOCATION);
        addIfMissing(missing, Manifest.permission.ACCESS_COARSE_LOCATION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            addIfMissing(missing, Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!missing.isEmpty()) {
            requestPermissions(missing.toArray(new String[0]), SDK_PERMISSION_REQUEST);
        }
    }

    private void addIfMissing(List<String> missing, String permission) {
        if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            missing.add(permission);
        }
    }

    private boolean allPermissionsGranted(@NonNull int[] grantResults) {
        if (grantResults.length == 0) {
            return false;
        }
        for (int result : grantResults) {
            if (result != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private void checkGiteeReleaseUpdate(boolean manual) {
        if (updateCheckInFlight) return;
        String currentVersion = GoUtils.getVersionName(this);
        if (currentVersion == null) {
            return;
        }
        if (!manual) {
            long lastCheck = sharedPreferences.getLong(PREF_LAST_AUTO_CHECK_TIME, 0L);
            if (!com.acooldog.toolbox.update.UpdateCheckPolicy.shouldCheck(System.currentTimeMillis(), lastCheck)) {
                return;
            }
        }
        if (!GoUtils.isNetworkAvailable(this)) {
            if (manual) {
                GoUtils.DisplayToast(this, getString(R.string.app_error_network));
            }
            return;
        }
        if (manual) {
            GoUtils.DisplayToast(this, getString(R.string.update_checking));
        }
        updateCheckInFlight = true;
        findViewById(R.id.btn_check_update).setEnabled(false);
        ioExecutor.execute(() -> {
            try {
                GiteeReleaseChecker checker = new GiteeReleaseChecker(okHttpClient);
                GiteeReleaseInfo releaseInfo = checker.fetchLatestRelease();
                if (releaseInfo == null) {
                    if (manual) {
                        runOnUiThread(() -> GoUtils.DisplayToast(this, getString(R.string.update_check_failed)));
                    }
                    return;
                }

                sharedPreferences.edit().putLong(PREF_LAST_AUTO_CHECK_TIME, System.currentTimeMillis()).apply();
                String ignoredTag = sharedPreferences.getString(PREF_IGNORED_RELEASE, "");
                boolean newer = checker.isNewerThan(releaseInfo.getTagName(), currentVersion);
                if (!newer) {
                    if (manual) {
                        runOnUiThread(() -> GoUtils.DisplayToast(this, getString(R.string.update_last)));
                    }
                    return;
                }
                if (!manual && releaseInfo.getTagName().equals(ignoredTag)) {
                    return;
                }

                runOnUiThread(() -> showReleaseUpdateDialog(releaseInfo));
            } catch (Exception exception) {
                if (manual) {
                    runOnUiThread(() -> GoUtils.DisplayToast(this, buildDetailedToast(R.string.update_check_failed, exception)));
                }
            } finally {
                runOnUiThread(() -> {
                    updateCheckInFlight = false;
                    if (!isFinishing() && !isDestroyed()) {
                        findViewById(R.id.btn_check_update).setEnabled(true);
                    }
                });
            }
        });
    }

    private void showReleaseUpdateDialog(GiteeReleaseInfo releaseInfo) {
        if (isFinishing() || isDestroyed()) return;
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_release_update, null);
        android.widget.TextView versionView = dialogView.findViewById(R.id.update_release_version);
        android.widget.TextView changelogView = dialogView.findViewById(R.id.update_release_changelog);

        versionView.setText(getString(R.string.update_dialog_version, releaseInfo.getTagName()));
        Markwon.create(this).setMarkdown(
                changelogView,
                releaseInfo.getChangelog().isEmpty() ? getString(R.string.update_dialog_empty_log) : releaseInfo.getChangelog()
        );

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.update_dialog_title)
                .setView(dialogView)
                .setPositiveButton(R.string.update_dialog_download, (dialog, which) -> {
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(releaseInfo.getDownloadUrl()));
                    startActivity(intent);
                })
                .setNegativeButton(R.string.update_dialog_acknowledged, (dialog, which) ->
                        sharedPreferences.edit().putString(PREF_IGNORED_RELEASE, releaseInfo.getTagName()).apply())
                .show();
    }

    private String buildDetailedToast(int prefixResId, Exception exception) {
        String detail = exception == null || exception.getMessage() == null ? "" : exception.getMessage().trim();
        if (detail.isEmpty()) {
            return getString(prefixResId);
        }
        return getString(prefixResId) + " " + detail;
    }

    /* ================== 账号 / 积分 ================== */

    private void checkCardLicense() {
        if (!com.acooldog.toolbox.config.WebPointsManager.isLoggedIn(this)) {
            showLoginDialog();
            return;
        }
        // 已登录：后台静默刷新积分余额
        com.acooldog.toolbox.config.WebPointsManager.fetchMe(this, new okhttp3.Callback() {
            @Override
            public void onFailure(@NonNull okhttp3.Call call, @NonNull java.io.IOException e) {
                // 离线时用本地缓存余额
            }

            @Override
            public void onResponse(@NonNull okhttp3.Call call, @NonNull okhttp3.Response response) throws java.io.IOException {
                String raw = response.body().string();
                com.acooldog.toolbox.config.WebPointsManager.Result r =
                        com.acooldog.toolbox.config.WebPointsManager.parse(raw);
                if (r.ok) {
                    com.acooldog.toolbox.config.WebPointsManager.saveBalance(HomeActivity.this, r.balance);
                } else if (response.code() == 401) {
                    runOnUiThread(() -> {
                        com.acooldog.toolbox.config.WebPointsManager.logout(HomeActivity.this);
                        GoUtils.DisplayToast(HomeActivity.this, "登录已失效，请重新登录");
                        showLoginDialog();
                    });
                }
            }
        });
    }

    private android.widget.TextView accountView;

    /** 在主页顶部显示「账号 · 积分」，点击可刷新/切换账号。 */
    private void refreshAccountBar() {
        if (accountView == null) {
            return;
        }
        if (!com.acooldog.toolbox.config.WebPointsManager.isLoggedIn(this)) {
            accountView.setText("未登录 · 点击登录");
            return;
        }
        String user = com.acooldog.toolbox.config.WebPointsManager.getUsername(this);
        int balance = com.acooldog.toolbox.config.WebPointsManager.getBalance(this);
        int days = com.acooldog.toolbox.config.WebPointsManager.getPassDaysLeft(this);
        if (days > 0) {
            accountView.setText(user + " · 积分 " + balance + " · 畅跑卡 " + days + " 天");
        } else {
            accountView.setText(user + " · 积分 " + balance);
        }
        syncEntitlement();
    }

    /** 静默同步畅跑卡状态，失败不打扰用户。 */
    private void syncEntitlement() {
        if (!com.acooldog.toolbox.config.WebPointsManager.isLoggedIn(this)) {
            return;
        }
        com.acooldog.toolbox.config.WebPointsManager.fetchEntitlement(this, new okhttp3.Callback() {
            @Override
            public void onFailure(@NonNull okhttp3.Call call, @NonNull java.io.IOException e) {
                // 断网时保留本地缓存
            }

            @Override
            public void onResponse(@NonNull okhttp3.Call call, @NonNull okhttp3.Response response) throws java.io.IOException {
                String raw = response.body().string();
                boolean active = com.acooldog.toolbox.config.WebPointsManager
                        .applyEntitlement(HomeActivity.this, raw);
                runOnUiThread(() -> {
                    android.widget.TextView v = findViewById(R.id.tv_account_bar);
                    if (v == null) {
                        return;
                    }
                    String u = com.acooldog.toolbox.config.WebPointsManager.getUsername(HomeActivity.this);
                    int b = com.acooldog.toolbox.config.WebPointsManager.getBalance(HomeActivity.this);
                    int d = com.acooldog.toolbox.config.WebPointsManager.getPassDaysLeft(HomeActivity.this);
                    if (active && d > 0) {
                        v.setText(u + " · 积分 " + b + " · 畅跑卡 " + d + " 天");
                    } else {
                        v.setText(u + " · 积分 " + b);
                    }
                });
            }
        });
    }

    /** 账号面板：用列表承载 4 个入口，避免弹窗按钮位不够。 */
    private void showAccountDialog() {
        int days = com.acooldog.toolbox.config.WebPointsManager.getPassDaysLeft(this);
        String user = com.acooldog.toolbox.config.WebPointsManager.getUsername(this);
        int balance = com.acooldog.toolbox.config.WebPointsManager.getBalance(this);
        String passLine = days > 0
                ? "畅跑卡：剩余 " + days + " 天（模拟不扣积分）"
                : "畅跑卡：未开通（每次模拟扣 "
                  + com.acooldog.toolbox.config.WebPointsManager.COST_PER_RUN + " 积分）";

        String[] items = {"畅跑卡 / 开通", "充值积分（站内）", "卡密兑换", "推荐好友 / 我的邀请码", "刷新积分与权益", "退出登录"};
        // MaterialAlertDialog 同时给 message 和 items 时，message 会把列表吞掉，
        // 所以状态信息并进标题，列表只放操作项。
        String title = user + " · 积分 " + balance
                + (days > 0 ? " · 畅跑卡 " + days + " 天" : " · 未开通畅跑卡");
        new MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0:
                            showBuyPassDialog();
                            break;
                        case 1:
                            open(RechargeActivity.class);
                            break;
                        case 2:
                            showCardActivationDialog();
                            break;
                        case 3:
                            openReferral();
                            break;
                        case 4:
                            checkCardLicense();
                            syncEntitlement();
                            GoUtils.DisplayToast(this, "正在刷新");
                            break;
                        default:
                            com.acooldog.toolbox.config.WebPointsManager.logout(this);
                            refreshAccountBar();
                            showLoginDialog();
                            break;
                    }
                })
                .show();
    }

    /** 打开推荐分成页（站内 WebView）。 */
    private void openReferral() {
        android.content.Intent i = new android.content.Intent(this, RechargeActivity.class);
        i.putExtra("url", "https://lain42.top/referral.html");
        startActivity(i);
    }

    /** 畅跑卡购买/状态弹窗。 */
    private void showBuyPassDialog() {
        int days = com.acooldog.toolbox.config.WebPointsManager.getPassDaysLeft(this);
        int balance = com.acooldog.toolbox.config.WebPointsManager.getBalance(this);
        final int price = 450;

        if (days > 0) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle("畅跑卡生效中")
                    .setMessage("剩余 " + days + " 天，模拟路线不扣积分。\n到期后自动回落到每次 "
                            + com.acooldog.toolbox.config.WebPointsManager.COST_PER_RUN + " 积分。")
                    .setPositiveButton("知道了", null)
                    .show();
            return;
        }

        new MaterialAlertDialogBuilder(this)
                .setTitle("校园跑 · 6 个月畅跑卡")
                .setMessage("价格 " + price + " 积分（¥45）\n"
                        + "有效期内模拟路线不扣积分，不限次数。\n\n"
                        + "当前积分 " + balance
                        + (balance < price ? "\n\n积分不足，还差 " + (price - balance) + " 积分" : ""))
                .setPositiveButton(balance < price ? "去充值" : "立即开通", (d, w) -> {
                    if (balance < price) {
                        open(RechargeActivity.class);
                    } else {
                        doBuyPass();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void doBuyPass() {
        GoUtils.DisplayToast(this, "正在开通…");
        com.acooldog.toolbox.config.WebPointsManager.purchasePass(this, new okhttp3.Callback() {
            @Override
            public void onFailure(@NonNull okhttp3.Call call, @NonNull java.io.IOException e) {
                runOnUiThread(() -> GoUtils.DisplayToast(HomeActivity.this, "开通失败：网络异常"));
            }

            @Override
            public void onResponse(@NonNull okhttp3.Call call, @NonNull okhttp3.Response response) throws java.io.IOException {
                String raw = response.body().string();
                boolean ok = false;
                String msg = "开通失败";
                try {
                    org.json.JSONObject o = new org.json.JSONObject(raw);
                    ok = o.optBoolean("ok", false);
                    if (ok) {
                        org.json.JSONObject pts = o.optJSONObject("points");
                        if (pts != null) {
                            com.acooldog.toolbox.config.WebPointsManager
                                    .saveBalance(HomeActivity.this, pts.optInt("balance", 0));
                        }
                    } else {
                        String d = o.optString("detail", "");
                        if (!d.isEmpty()) {
                            msg = d;
                        }
                    }
                } catch (Exception ignored) {
                }
                final boolean done = ok;
                final String fmsg = msg;
                runOnUiThread(() -> {
                    GoUtils.DisplayToast(HomeActivity.this, done ? "开通成功" : fmsg);
                    refreshAccountBar();
                    syncEntitlement();
                });
            }
        });
    }

    private void showLoginDialog() {
        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (getResources().getDisplayMetrics().density * 20);
        box.setPadding(pad, pad / 2, pad, 0);

        final android.widget.EditText userInput = new android.widget.EditText(this);
        userInput.setHint("站点账号 / 用户名");
        userInput.setSingleLine(true);
        box.addView(userInput);

        final android.widget.EditText passInput = new android.widget.EditText(this);
        passInput.setHint("密码");
        passInput.setSingleLine(true);
        passInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.addView(passInput);

        new MaterialAlertDialogBuilder(this)
                .setTitle("登录 lain42 账号")
                .setView(box)
                .setCancelable(false)
                .setNeutralButton("注册新账号", (dialog, which) -> showRegisterDialog())
                .setPositiveButton("登录", (dialog, which) -> {
                    String u = userInput.getText().toString().trim();
                    String p = passInput.getText().toString();
                    if (u.isEmpty() || p.isEmpty()) {
                        GoUtils.DisplayToast(this, "请输入账号和密码");
                        showLoginDialog();
                        return;
                    }
                    doLogin(u, p);
                })
                .setNegativeButton("卡密激活", (dialog, which) -> showCardActivationDialog())
                .show();
    }

    /** App 内注册，直接调 /register，注册成功即登录，不跳浏览器。 */
    private void showRegisterDialog() {
        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (getResources().getDisplayMetrics().density * 20);
        box.setPadding(pad, pad / 2, pad, 0);

        final android.widget.EditText userInput = new android.widget.EditText(this);
        userInput.setHint("用户名（3-24 位字母/数字/下划线）");
        userInput.setSingleLine(true);
        box.addView(userInput);

        final android.widget.EditText passInput = new android.widget.EditText(this);
        passInput.setHint("密码（至少 8 位）");
        passInput.setSingleLine(true);
        passInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.addView(passInput);

        final android.widget.EditText pass2Input = new android.widget.EditText(this);
        pass2Input.setHint("再输一次密码");
        pass2Input.setSingleLine(true);
        pass2Input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.addView(pass2Input);

        final android.widget.EditText emailInput = new android.widget.EditText(this);
        emailInput.setHint("邮箱（可选）");
        emailInput.setSingleLine(true);
        emailInput.setInputType(android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        box.addView(emailInput);

        final android.widget.EditText refInput = new android.widget.EditText(this);
        refInput.setHint("邀请码（可选，填了对方拿分成）");
        refInput.setSingleLine(true);
        refInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        box.addView(refInput);

        android.widget.TextView hint = new android.widget.TextView(this);
        hint.setText("注册即送 25 积分，可直接用来跑路线。");
        hint.setTextSize(12);
        hint.setTextColor(getColor(R.color.textSecondary));
        hint.setPadding(0, pad / 2, 0, 0);
        box.addView(hint);

        new MaterialAlertDialogBuilder(this)
                .setTitle("注册 lain42 账号")
                .setView(box)
                .setCancelable(false)
                .setPositiveButton("注册", (dialog, which) -> {
                    String u = userInput.getText().toString().trim();
                    String p = passInput.getText().toString();
                    String p2 = pass2Input.getText().toString();
                    String mail = emailInput.getText().toString().trim();
                    if (u.isEmpty() || p.isEmpty()) {
                        GoUtils.DisplayToast(this, "请输入用户名和密码");
                        showRegisterDialog();
                        return;
                    }
                    if (!u.matches("[A-Za-z0-9_]{3,24}")) {
                        GoUtils.DisplayToast(this, "用户名需 3-24 位字母/数字/下划线");
                        showRegisterDialog();
                        return;
                    }
                    if (p.length() < 8) {
                        GoUtils.DisplayToast(this, "密码至少 8 位");
                        showRegisterDialog();
                        return;
                    }
                    if (!p.equals(p2)) {
                        GoUtils.DisplayToast(this, "两次输入的密码不一致");
                        showRegisterDialog();
                        return;
                    }
                    doRegister(u, p, mail, refInput.getText().toString().trim());
                })
                .setNegativeButton("返回登录", (dialog, which) -> showLoginDialog())
                .show();
    }

    private void doRegister(String username, String password, String email, String ref) {
        com.acooldog.toolbox.config.WebPointsManager.register(this, username, password, email, ref,
                new okhttp3.Callback() {
                    @Override
                    public void onFailure(@NonNull okhttp3.Call call, @NonNull java.io.IOException e) {
                        runOnUiThread(() -> {
                            GoUtils.DisplayToast(HomeActivity.this, "注册失败：网络异常");
                            showRegisterDialog();
                        });
                    }

                    @Override
                    public void onResponse(@NonNull okhttp3.Call call, @NonNull okhttp3.Response response) throws java.io.IOException {
                        String raw = response.body().string();
                        com.acooldog.toolbox.config.WebPointsManager.Result r =
                                com.acooldog.toolbox.config.WebPointsManager.parse(raw);
                        if (r.ok && !r.token.isEmpty()) {
                            // 注册接口直接返回 token，等于注册即登录
                            com.acooldog.toolbox.config.WebPointsManager.saveSession(
                                    HomeActivity.this, r.token, r.username);
                            runOnUiThread(() -> {
                                String msg = r.bonus > 0
                                        ? "注册成功，赠送 " + r.bonus + " 积分"
                                        : "注册成功";
                                GoUtils.DisplayToast(HomeActivity.this, msg);
                                refreshAccountBar();
                            });
                            com.acooldog.toolbox.config.WebPointsManager.fetchMe(HomeActivity.this,
                                    new okhttp3.Callback() {
                                        @Override
                                        public void onFailure(@NonNull okhttp3.Call c2, @NonNull java.io.IOException e2) {
                                        }

                                        @Override
                                        public void onResponse(@NonNull okhttp3.Call c2, @NonNull okhttp3.Response resp2) throws java.io.IOException {
                                            com.acooldog.toolbox.config.WebPointsManager.Result me =
                                                    com.acooldog.toolbox.config.WebPointsManager.parse(
                                                            resp2.body().string());
                                            if (me.ok) {
                                                runOnUiThread(() -> {
                                                    com.acooldog.toolbox.config.WebPointsManager
                                                            .saveBalance(HomeActivity.this, me.balance);
                                                    refreshAccountBar();
                                                });
                                            }
                                        }
                                    });
                        } else {
                            String msg = r.message == null || r.message.isEmpty()
                                    ? "注册失败" : r.message;
                            runOnUiThread(() -> {
                                GoUtils.DisplayToast(HomeActivity.this, msg);
                                showRegisterDialog();
                            });
                        }
                    }
                });
    }

    private void doLogin(String username, String password) {
        com.acooldog.toolbox.config.WebPointsManager.login(this, username, password, new okhttp3.Callback() {
            @Override
            public void onFailure(@NonNull okhttp3.Call call, @NonNull java.io.IOException e) {
                runOnUiThread(() -> {
                    GoUtils.DisplayToast(HomeActivity.this, "登录失败：网络异常");
                    showLoginDialog();
                });
            }

            @Override
            public void onResponse(@NonNull okhttp3.Call call, @NonNull okhttp3.Response response) throws java.io.IOException {
                com.acooldog.toolbox.config.WebPointsManager.Result r =
                        com.acooldog.toolbox.config.WebPointsManager.parse(response.body().string());
                if (r.ok && !r.token.isEmpty()) {
                    com.acooldog.toolbox.config.WebPointsManager.saveSession(HomeActivity.this, r.token, r.username);
                    // 登录后立刻拉一次积分
                    com.acooldog.toolbox.config.WebPointsManager.fetchMe(HomeActivity.this, new okhttp3.Callback() {
                        @Override
                        public void onFailure(@NonNull okhttp3.Call c2, @NonNull java.io.IOException e2) {
                            runOnUiThread(() -> {
                                refreshAccountBar();
                                GoUtils.DisplayToast(HomeActivity.this, "登录成功");
                            });
                        }

                        @Override
                        public void onResponse(@NonNull okhttp3.Call c2, @NonNull okhttp3.Response resp2) throws java.io.IOException {
                            com.acooldog.toolbox.config.WebPointsManager.Result me =
                                    com.acooldog.toolbox.config.WebPointsManager.parse(resp2.body().string());
                            runOnUiThread(() -> {
                                if (me.ok) {
                                    com.acooldog.toolbox.config.WebPointsManager.saveBalance(HomeActivity.this, me.balance);
                                    GoUtils.DisplayToast(HomeActivity.this, "登录成功，当前积分 " + me.balance);
                                } else {
                                    GoUtils.DisplayToast(HomeActivity.this, "登录成功");
                                }
                                refreshAccountBar();
                            });
                        }
                    });
                } else {
                    String msg = r.message == null || r.message.isEmpty() ? "账号或密码错误" : r.message;
                    runOnUiThread(() -> {
                        GoUtils.DisplayToast(HomeActivity.this, msg);
                        showLoginDialog();
                    });
                }
            }
        });
    }

    /** 进入模拟前扣积分；持有畅跑卡则直接放行。余额不足则引导去充值。 */
    private void startRunWithPoints() {
        if (!com.acooldog.toolbox.config.WebPointsManager.isLoggedIn(this)) {
            showLoginDialog();
            return;
        }
        // 畅跑卡有效期内不扣积分
        if (com.acooldog.toolbox.config.WebPointsManager.hasActivePass(this)) {
            int days = com.acooldog.toolbox.config.WebPointsManager.getPassDaysLeft(this);
            GoUtils.DisplayToast(this, "畅跑卡生效中，剩余 " + days + " 天，本次不扣积分");
            open(RouteRunActivity.class);
            return;
        }
        int balance = com.acooldog.toolbox.config.WebPointsManager.getBalance(this);
        if (balance < com.acooldog.toolbox.config.WebPointsManager.COST_PER_RUN) {
            showNotEnoughDialog(balance);
            return;
        }
        final int cost = com.acooldog.toolbox.config.WebPointsManager.COST_PER_RUN;
        String ref = com.acooldog.toolbox.config.WebPointsManager.newReference(this);
        com.acooldog.toolbox.config.WebPointsManager.consume(this, cost, "校园跑模拟", ref, new okhttp3.Callback() {
            @Override
            public void onFailure(@NonNull okhttp3.Call call, @NonNull java.io.IOException e) {
                runOnUiThread(() -> {
                    GoUtils.DisplayToast(HomeActivity.this, "扣费失败：网络异常，请检查网络");
                });
            }

            @Override
            public void onResponse(@NonNull okhttp3.Call call, @NonNull okhttp3.Response response) throws java.io.IOException {
                String raw = response.body().string();
                com.acooldog.toolbox.config.WebPointsManager.Result r =
                        com.acooldog.toolbox.config.WebPointsManager.parse(raw);
                if (r.ok) {
                    com.acooldog.toolbox.config.WebPointsManager.saveBalance(HomeActivity.this, r.balance);
                    runOnUiThread(() -> {
                        refreshAccountBar();
                        GoUtils.DisplayToast(HomeActivity.this, "已扣 " + cost + " 积分，剩余 " + r.balance);
                        open(RouteRunActivity.class);
                    });
                } else if (r.notEnough()) {
                    runOnUiThread(() -> showNotEnoughDialog(r.balance));
                } else {
                    runOnUiThread(() -> GoUtils.DisplayToast(HomeActivity.this,
                            r.message.isEmpty() ? "扣费失败" : r.message));
                }
            }
        });
    }

    private void showNotEnoughDialog(int balance) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("积分不足")
                .setMessage("当前积分 " + balance + "，每次模拟需要 "
                        + com.acooldog.toolbox.config.WebPointsManager.COST_PER_RUN + " 积分。")
                .setPositiveButton("去充值", (dialog, which) ->
                        openUrl(com.acooldog.toolbox.config.WebPointsManager.SITE_BUY))
                .setNegativeButton("取消", null)
                .show();
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception exception) {
            GoUtils.DisplayToast(this, "无法打开页面");
        }
    }

    private void showCardActivationDialog() {
        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (getResources().getDisplayMetrics().density * 18);
        box.setPadding(pad, pad / 2, pad, 0);

        final android.widget.EditText input = new android.widget.EditText(this);
        input.setHint("请输入卡密，如 PDBP-XXXX-XXXX-XXXX");
        input.setSingleLine(true);
        box.addView(input);

        android.widget.TextView hint = new android.widget.TextView(this);
        hint.setText("畅跑卡卡密兑换后，6 个月内模拟不扣积分。");
        hint.setTextSize(12);
        hint.setTextColor(getColor(R.color.textSecondary));
        hint.setPadding(0, pad / 2, 0, 0);
        box.addView(hint);

        new MaterialAlertDialogBuilder(this)
                .setTitle("卡密兑换")
                .setView(box)
                .setCancelable(true)
                .setPositiveButton("兑换", (dialog, which) -> {
                    String card = input.getText().toString().trim();
                    if (card.isEmpty()) {
                        GoUtils.DisplayToast(this, "卡密不能为空");
                        return;
                    }
                    doRedeem(card);
                })
                .setNeutralButton("去爱发电买", (dialog, which) -> openAfdian())
                .setNegativeButton("取消", null)
                .show();
    }

    /** 打开爱发电充值页（站内 WebView）。 */
    private void openAfdian() {
        android.content.Intent i = new android.content.Intent(this, RechargeActivity.class);
        i.putExtra("url", "https://lain42.top/shop/#topup");
        startActivity(i);
    }

    private void doRedeem(String code) {
        if (!com.acooldog.toolbox.config.WebPointsManager.isLoggedIn(this)) {
            GoUtils.DisplayToast(this, "请先登录站点账号");
            showLoginDialog();
            return;
        }
        com.acooldog.toolbox.config.WebPointsManager.redeem(this, code, new okhttp3.Callback() {
            @Override
            public void onFailure(@NonNull okhttp3.Call call, @NonNull java.io.IOException e) {
                runOnUiThread(() -> GoUtils.DisplayToast(HomeActivity.this, "兑换失败：网络异常"));
            }

            @Override
            public void onResponse(@NonNull okhttp3.Call call, @NonNull okhttp3.Response response) throws java.io.IOException {
                com.acooldog.toolbox.config.WebPointsManager.Result r =
                        com.acooldog.toolbox.config.WebPointsManager.parse(response.body().string());
                runOnUiThread(() -> {
                    if (r.ok) {
                        GoUtils.DisplayToast(HomeActivity.this, "兑换成功");
                        refreshAccountBar();
                        syncEntitlement();
                    } else {
                        GoUtils.DisplayToast(HomeActivity.this,
                                r.message.isEmpty() ? "卡密无效" : r.message);
                    }
                });
            }
        });
    }
}
