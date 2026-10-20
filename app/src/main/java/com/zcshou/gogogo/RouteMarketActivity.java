package com.acooldog.toolbox;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;

import com.acooldog.toolbox.config.RouteMarketClient;
import com.acooldog.toolbox.config.WebPointsManager;
import com.acooldog.toolbox.utils.GoUtils;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Response;

/**
 * 路线社区：浏览 / 按学校筛选 / 点赞 / 积分购买 / 下载导入 / 上传。
 */
public class RouteMarketActivity extends BaseActivity {

    public static final String EXTRA_SCHOOL = "extra_school";

    private final List<RouteMarketClient.Item> items = new ArrayList<>();
    private MarketAdapter adapter;

    private String view = "all";          // all | mine | bought
    private String school = "";
    private String sort = "new";
    private String query = "";
    private List<String> schools = new ArrayList<>();

    private boolean uploading = false;
    private int pendingPrice = 0;
    private JSONObject pendingRouteJson;
    private String pendingName = "";
    private String pendingSchool = "";
    private String pendingCity = "";
    private String pendingDesc = "";
    /** 已选预览图的 data URL，空串表示不传。 */
    private String pendingPreviewDataUrl = "";
    private androidx.appcompat.app.AlertDialog uploadDialog;

    private final ActivityResultLauncher<String[]> uploadPicker = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), this::handlePickedRoute);

    private final ActivityResultLauncher<String[]> imagePicker = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), this::handlePickedImage);

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_route_market);

        MaterialToolbar toolbar = findViewById(R.id.toolbar_market);
        toolbar.setNavigationOnClickListener(v -> finish());

        adapter = new MarketAdapter();
        ((ListView) findViewById(R.id.market_list)).setAdapter(adapter);

        String preset = getIntent().getStringExtra(EXTRA_SCHOOL);
        if (preset != null && !preset.isEmpty()) {
            school = preset;
            findViewById(R.id.btn_pick_school).setVisibility(View.VISIBLE);
        }

        findViewById(R.id.market_tab_all).setOnClickListener(v -> switchView("all"));
        findViewById(R.id.market_tab_mine).setOnClickListener(v -> switchView("mine"));
        findViewById(R.id.market_tab_bought).setOnClickListener(v -> switchView("bought"));
        findViewById(R.id.btn_pick_school).setOnClickListener(v -> pickSchool());
        findViewById(R.id.btn_pick_sort).setOnClickListener(v -> pickSort());
        findViewById(R.id.btn_upload_route).setOnClickListener(v -> startUpload());
        findViewById(R.id.btn_market_refresh).setOnClickListener(v -> {
            loadSchools();
            reload();
        });

        loadSchools();
        reload();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!items.isEmpty()) {
            reload();
        }
    }

    /* ---------------- 加载 ---------------- */

    private void switchView(String v) {
        view = v;
        findViewById(R.id.market_filter_row)
                .setVisibility("all".equals(v) ? View.VISIBLE : View.GONE);
        reload();
    }

    private void setLoading(boolean on) {
        findViewById(R.id.market_loading).setVisibility(on ? View.VISIBLE : View.INVISIBLE);
    }

    private void reload() {
        if (!WebPointsManager.isLoggedIn(this) && !"all".equals(view)) {
            toast("请先在首页登录 lain42 账号");
            switchView("all");
            return;
        }
        setLoading(true);
        if ("all".equals(view)) {
            RouteMarketClient.list(this, school, query, sort, 40, 0,
                    new SimpleCallback() {
                        @Override public void onBody(String body) {
                            setLoading(false);
                            try {
                                items.clear();
                                items.addAll(RouteMarketClient.parseList(body));
                                adapter.notifyDataSetChanged();
                                JSONObject o = new JSONObject(body);
                                ((TextView) findViewById(R.id.market_stat))
                                        .setText("共 " + o.optInt("total", items.size()) + " 条路线");
                                showEmpty(items.isEmpty(), "还没有路线\n点下方「上传路线」发布第一条");
                            } catch (Exception e) {
                                fail("解析失败：" + e.getMessage());
                            }
                        }

                        @Override public void onFail(String msg) {
                            fail(msg);
                        }
                    });
        } else {
            RouteMarketClient.mine(this, new SimpleCallback() {
                @Override public void onBody(String body) {
                    setLoading(false);
                    try {
                        items.clear();
                        JSONObject o = new JSONObject(body);
                        org.json.JSONArray arr = o.optJSONArray(
                                "mine".equals(view) ? "uploaded" : "purchased");
                        if (arr != null) {
                            for (int i = 0; i < arr.length(); i++) {
                                items.add(RouteMarketClient.parseItem(arr.getJSONObject(i)));
                            }
                        }
                        adapter.notifyDataSetChanged();
                        TextView stat = findViewById(R.id.market_stat);
                        if ("mine".equals(view)) {
                            stat.setText("已上传 " + items.size() + " 条 · 累计收益 "
                                    + o.optInt("earned", 0) + " 积分");
                            showEmpty(items.isEmpty(), "你还没有上传过路线");
                        } else {
                            stat.setText("已购买 " + items.size() + " 条");
                            showEmpty(items.isEmpty(), "你还没有购买过路线");
                        }
                    } catch (Exception e) {
                        fail("解析失败：" + e.getMessage());
                    }
                }

                @Override public void onFail(String msg) {
                    fail(msg);
                }
            });
        }
    }

    private void fail(String msg) {
        setLoading(false);
        toast(msg);
        showEmpty(items.isEmpty(), msg);
    }

    private void showEmpty(boolean empty, String text) {
        TextView v = findViewById(R.id.market_empty);
        v.setText(text);
        v.setVisibility(empty ? View.VISIBLE : View.GONE);
        findViewById(R.id.market_list).setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    private void loadSchools() {
        RouteMarketClient.schools(this, new SimpleCallback() {
            @Override public void onBody(String body) {
                try {
                    schools = RouteMarketClient.parseSchools(body);
                } catch (Exception ignored) {
                }
            }

            @Override public void onFail(String msg) {
            }
        });
    }

    /* ---------------- 筛选 ---------------- */

    private void pickSchool() {
        List<String> options = new ArrayList<>();
        options.add("全部学校");
        options.addAll(schools);
        new MaterialAlertDialogBuilder(this)
                .setTitle("按学校筛选")
                .setItems(options.toArray(new String[0]), (d, which) -> {
                    school = which == 0 ? "" : options.get(which);
                    ((MaterialButton) findViewById(R.id.btn_pick_school))
                            .setText(school.isEmpty() ? "全部学校" : school);
                    reload();
                })
                .show();
    }

    private void pickSort() {
        String[] labels = {"最新发布", "销量优先", "点赞最多", "价格从低到高"};
        String[] keys = {"new", "hot", "like", "cheap"};
        new MaterialAlertDialogBuilder(this)
                .setTitle("排序")
                .setItems(labels, (d, which) -> {
                    sort = keys[which];
                    ((MaterialButton) findViewById(R.id.btn_pick_sort)).setText(labels[which]);
                    reload();
                })
                .show();
    }

    /* ---------------- 点赞 / 购买 / 下载 ---------------- */

    private void toggleLike(RouteMarketClient.Item it) {
        if (!requireLogin()) {
            return;
        }
        RouteMarketClient.like(this, it.id, new SimpleCallback() {
            @Override public void onBody(String body) {
                try {
                    JSONObject o = new JSONObject(body);
                    it.liked = o.optBoolean("liked", !it.liked);
                    it.likes = o.optInt("likes", it.likes);
                    adapter.notifyDataSetChanged();
                } catch (Exception ignored) {
                }
            }

            @Override public void onFail(String msg) {
                toast(msg);
            }
        });
    }

    private void buy(RouteMarketClient.Item it) {
        if (!requireLogin()) {
            return;
        }
        int fee = RouteMarketClient.platformFee(it.price);
        int income = RouteMarketClient.sellerIncome(it.price);
        new MaterialAlertDialogBuilder(this)
                .setTitle("购买路线")
                .setMessage(it.name + "\n\n价格 " + it.price + " 积分\n"
                        + "作者实得 " + income + " 积分（平台抽成 " + fee + " 积分，10%）\n\n"
                        + "当前余额 " + WebPointsManager.getBalance(this) + " 积分")
                .setPositiveButton("确认购买", (d, w) -> doBuy(it))
                .setNegativeButton("取消", null)
                .show();
    }

    private void doBuy(RouteMarketClient.Item it) {
        setLoading(true);
        RouteMarketClient.buy(this, it.id, new SimpleCallback() {
            @Override public void onBody(String body) {
                setLoading(false);
                try {
                    JSONObject o = new JSONObject(body);
                    if (o.has("balance")) {
                        WebPointsManager.saveBalance(RouteMarketActivity.this,
                                o.optInt("balance", 0));
                    }
                    it.owned = true;
                    adapter.notifyDataSetChanged();
                    toast(o.optBoolean("idempotent", false)
                            ? "你已经买过了" : "购买成功，剩余 " + o.optInt("balance", 0) + " 积分");
                    JSONObject route = o.optJSONObject("route");
                    if (route != null) {
                        importRoute(route, it.name);
                    }
                    reload();
                } catch (Exception e) {
                    toast("购买成功，但解析失败");
                }
            }

            @Override public void onFail(String msg) {
                setLoading(false);
                toast(msg);
                if (msg.contains("积分不足")) {
                    showTopUpDialog();
                }
            }
        });
    }

    private void download(RouteMarketClient.Item it) {
        if (!requireLogin()) {
            return;
        }
        setLoading(true);
        RouteMarketClient.download(this, it.id, new SimpleCallback() {
            @Override public void onBody(String body) {
                setLoading(false);
                try {
                    JSONObject o = new JSONObject(body);
                    JSONObject route = o.optJSONObject("route");
                    if (route != null) {
                        importRoute(route, it.name);
                    } else {
                        toast("没有拿到路线内容");
                    }
                } catch (Exception e) {
                    toast("解析失败");
                }
            }

            @Override public void onFail(String msg) {
                setLoading(false);
                toast(msg);
            }
        });
    }

    /** 把路线写进本地路线库。 */
    private void importRoute(JSONObject route, String name) {
        try {
            java.io.File dir = new java.io.File(getExternalFilesDir(null), "routes");
            if (!dir.exists() && !dir.mkdirs()) {
                toast("无法创建路线目录");
                return;
            }
            // 列表显示的是 JSON 内部的 name，这里同步改掉，
            // 否则同一条路线买两次会出现两条一模一样的条目。
            String unique = uniqueRouteName(dir, name);
            try {
                route.put("name", unique);
            } catch (Exception ignored) {
            }
            String safe = unique.replaceAll("[\\\\/:*?\"<>|]", "_").replaceAll("\\s+", "_");
            if (safe.isEmpty()) {
                safe = "route";
            }
            java.io.File target = new java.io.File(dir, safe + ".route.json");
            int n = 1;
            while (target.exists()) {
                target = new java.io.File(dir, safe + "-" + (n++) + ".route.json");
            }
            java.io.FileOutputStream out = new java.io.FileOutputStream(target);
            out.write(route.toString(2).getBytes("UTF-8"));
            out.close();
            toast("已导入到路线库：" + unique);
        } catch (Exception e) {
            toast("导入失败：" + e.getMessage());
        }
    }

    /* ---------------- 上传 ---------------- */

    private void startUpload() {
        if (!requireLogin()) {
            return;
        }
        pendingPrice = 20;
        pendingRouteJson = null;
        pendingName = "";
        pendingSchool = school;
        pendingCity = "";
        pendingDesc = "";
        pendingPreviewDataUrl = "";
        uploading = true;
        uploadPicker.launch(new String[]{"application/json", "*/*"});
    }

    private void handlePickedRoute(Uri uri) {
        uploading = false;
        if (uri == null) {
            return;
        }
        try {
            InputStream in = getContentResolver().openInputStream(uri);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int len;
            int total = 0;
            while ((len = in.read(buf)) != -1) {
                total += len;
                if (total > 512 * 1024) {
                    in.close();
                    toast("路线文件不能超过 512 KiB");
                    return;
                }
                bos.write(buf, 0, len);
            }
            in.close();
            pendingRouteJson = new JSONObject(new String(bos.toByteArray(), "UTF-8"));
            if (pendingName.isEmpty()) {
                pendingName = pendingRouteJson.optString("name", "");
            }
            showUploadForm();
        } catch (Exception e) {
            toast("读取失败：" + e.getMessage());
        }
    }

    private void showUploadForm() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);

        TextView routeInfo = new TextView(this);
        int pts = pendingRouteJson == null ? 0
                : pendingRouteJson.optJSONArray("points") == null ? 0
                : pendingRouteJson.optJSONArray("points").length();
        routeInfo.setText("路线：" + (pendingName.isEmpty() ? "未命名" : pendingName)
                + "\n坐标点：" + pts + " 个");
        routeInfo.setTextColor(getColor(R.color.textSecondary));
        routeInfo.setTextSize(13);
        box.addView(routeInfo);

        EditText nameInput = field(box, "路线名称", pendingName);
        EditText schoolInput = field(box, "学校（必填）", pendingSchool);
        EditText cityInput = field(box, "城市", pendingCity);
        EditText descInput = field(box, "简介", pendingDesc);
        EditText priceInput = field(box, "价格（积分，最低 10）", String.valueOf(pendingPrice));
        priceInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);

        TextView feeHint = new TextView(this);
        feeHint.setTextColor(getColor(R.color.saberGold));
        feeHint.setTextSize(12.5f);
        feeHint.setPadding(0, pad / 2, 0, 0);
        box.addView(feeHint);
        updateFeeHint(feeHint, pendingPrice);

        android.text.TextWatcher watcher = new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                try {
                    updateFeeHint(feeHint, Integer.parseInt(s.toString().trim()));
                } catch (Exception e) {
                    updateFeeHint(feeHint, 0);
                }
            }

            @Override public void afterTextChanged(android.text.Editable s) {
            }
        };
        priceInput.addTextChangedListener(watcher);

        // ---- 预览图 ----
        TextView previewLabel = new TextView(this);
        previewLabel.setText("预览图（可选）");
        previewLabel.setTextSize(12);
        previewLabel.setTextColor(getColor(R.color.textSecondary));
        previewLabel.setPadding(0, pad / 2, 0, 0);
        box.addView(previewLabel);

        android.widget.ImageView previewThumb = new android.widget.ImageView(this);
        LinearLayout.LayoutParams thumbParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (int) (120 * getResources()
                .getDisplayMetrics().density));
        previewThumb.setLayoutParams(thumbParams);
        previewThumb.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        previewThumb.setVisibility(pendingPreviewDataUrl.isEmpty() ? View.GONE : View.VISIBLE);
        if (!pendingPreviewDataUrl.isEmpty()) {
            try {
                byte[] bytes = android.util.Base64.decode(
                        pendingPreviewDataUrl.substring(pendingPreviewDataUrl.indexOf(',') + 1),
                        android.util.Base64.DEFAULT);
                previewThumb.setImageBitmap(android.graphics.BitmapFactory
                        .decodeByteArray(bytes, 0, bytes.length));
            } catch (Exception ignored) {
            }
        }
        box.addView(previewThumb);

        MaterialButton pickImage = new MaterialButton(this, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        pickImage.setText(pendingPreviewDataUrl.isEmpty() ? "选择预览图" : "重新选择预览图");
        pickImage.setAllCaps(false);
        LinearLayout.LayoutParams pickParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        pickParams.topMargin = pad / 2;
        pickImage.setLayoutParams(pickParams);
        box.addView(pickImage);

        TextView previewHint = new TextView(this);
        previewHint.setText("不选也可以上传，只是列表里没有图。建议截图路线地图，长边 1080 会自动压缩。");
        previewHint.setTextSize(11.5f);
        previewHint.setTextColor(getColor(R.color.textSecondary));
        previewHint.setPadding(0, pad / 4, 0, 0);
        box.addView(previewHint);

        pickImage.setOnClickListener(x -> {
            // 选图会离开当前界面，先把表单里已填的内容存下来，
            // 选完图后 showUploadForm() 会用这些值把表单原样恢复。
            pendingName = nameInput.getText().toString().trim();
            pendingSchool = schoolInput.getText().toString().trim();
            pendingCity = cityInput.getText().toString().trim();
            pendingDesc = descInput.getText().toString().trim();
            try {
                pendingPrice = Integer.parseInt(priceInput.getText().toString().trim());
            } catch (Exception ignored) {
            }
            if (uploadDialog != null) {
                uploadDialog.dismiss();
                uploadDialog = null;
            }
            imagePicker.launch(new String[]{"image/*"});
        });

        uploadDialog = new MaterialAlertDialogBuilder(this)
                .setTitle("上传路线")
                .setView(box)
                .setPositiveButton("提交", (d, w) -> {
                    pendingName = nameInput.getText().toString().trim();
                    pendingSchool = schoolInput.getText().toString().trim();
                    pendingCity = cityInput.getText().toString().trim();
                    pendingDesc = descInput.getText().toString().trim();
                    int price;
                    try {
                        price = Integer.parseInt(priceInput.getText().toString().trim());
                    } catch (Exception e) {
                        price = 0;
                    }
                    if (pendingName.isEmpty()) {
                        toast("请填写路线名称");
                        return;
                    }
                    if (pendingSchool.isEmpty()) {
                        toast("请填写学校");
                        return;
                    }
                    if (price < RouteMarketClient.MIN_PRICE) {
                        toast("价格不能低于 " + RouteMarketClient.MIN_PRICE + " 积分");
                        return;
                    }
                    doUpload(price);
                })
                .setNegativeButton("取消", null)
                .show();
        uploadDialog.show();
    }

    private void handlePickedImage(Uri uri) {
        if (uri == null) {
            showUploadForm();
            return;
        }
        toast("正在处理图片…");
        final String encoded = com.acooldog.toolbox.config.PreviewImageEncoder
                .encode(this, uri);
        if (encoded == null) {
            toast("图片处理失败，换一张试试");
            showUploadForm();
            return;
        }
        pendingPreviewDataUrl = encoded;
        toast("预览图已就绪");
        showUploadForm();
    }

    /**
     * 与 FileRouteRepository.uniqueRouteName 同样的规则：
     * 本地已有同名路线时返回「名字 (2)」，避免列表里两条一模一样。
     */
    private String uniqueRouteName(java.io.File dir, String name) {
        String base = name == null || name.trim().isEmpty() ? "未命名路线" : name.trim();
        java.util.Set<String> existing = new java.util.HashSet<>();
        java.io.File[] files = dir.listFiles((d, n) -> n.endsWith(".json"));
        if (files != null) {
            for (java.io.File f : files) {
                try {
                    java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                    java.io.FileInputStream in = new java.io.FileInputStream(f);
                    byte[] buf = new byte[4096];
                    int len;
                    while ((len = in.read(buf)) != -1) {
                        bos.write(buf, 0, len);
                    }
                    in.close();
                    JSONObject o = new JSONObject(new String(bos.toByteArray(), "UTF-8"));
                    existing.add(o.optString("name", ""));
                } catch (Exception ignored) {
                    // 读不出来就不参与重名判断
                }
            }
        }
        if (!existing.contains(base)) {
            return base;
        }
        for (int i = 2; i < 1000; i++) {
            String candidate = base + " (" + i + ")";
            if (!existing.contains(candidate)) {
                return candidate;
            }
        }
        return base + " (" + System.currentTimeMillis() + ")";
    }

    private void updateFeeHint(TextView hint, int price) {
        if (price < RouteMarketClient.MIN_PRICE) {
            hint.setText("价格不能低于 " + RouteMarketClient.MIN_PRICE + " 积分");
        } else {
            hint.setText("平台抽成 " + RouteMarketClient.platformFee(price)
                    + " 积分（10%），你实得 " + RouteMarketClient.sellerIncome(price) + " 积分");
        }
    }

    private EditText field(LinearLayout parent, String label, String value) {
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(12);
        tv.setTextColor(getColor(R.color.textSecondary));
        tv.setPadding(0, pad / 2, 0, 0);
        parent.addView(tv);

        EditText et = new EditText(this);
        et.setText(value);
        et.setTextColor(getColor(R.color.textPrimary));
        et.setTextSize(14);
        et.setSingleLine(true);
        parent.addView(et);
        return et;
    }

    private void doUpload(int price) {
        setLoading(true);
        RouteMarketClient.upload(this, pendingName, pendingSchool, pendingCity,
                pendingDesc, price, pendingRouteJson, pendingPreviewDataUrl, new SimpleCallback() {
                    @Override public void onBody(String body) {
                        setLoading(false);
                        toast("上传成功，已进入社区");
                        pendingPreviewDataUrl = "";
                        switchView("mine");
                        loadSchools();
                    }

                    @Override public void onFail(String msg) {
                        setLoading(false);
                        toast("上传失败：" + msg);
                    }
                });
    }

    /* ---------------- 辅助 ---------------- */

    private boolean requireLogin() {
        if (WebPointsManager.isLoggedIn(this)) {
            return true;
        }
        showLoginDialog();
        return false;
    }

    /** 社区页内直接登录/注册，不再跳回首页。 */
    private void showLoginDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);

        final EditText userInput = new EditText(this);
        userInput.setHint("站点账号 / 用户名");
        userInput.setSingleLine(true);
        box.addView(userInput);

        final EditText passInput = new EditText(this);
        passInput.setHint("密码");
        passInput.setSingleLine(true);
        passInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.addView(passInput);

        new MaterialAlertDialogBuilder(this)
                .setTitle("登录 lain42 账号")
                .setView(box)
                .setPositiveButton("登录", (d, w) -> {
                    String u = userInput.getText().toString().trim();
                    String p = passInput.getText().toString();
                    if (u.isEmpty() || p.isEmpty()) {
                        toast("请输入账号和密码");
                        return;
                    }
                    doLogin(u, p);
                })
                .setNeutralButton("注册新账号", (d, w) -> showRegisterDialog())
                .setNegativeButton("取消", null)
                .show();
    }

    private void showRegisterDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);

        final EditText userInput = new EditText(this);
        userInput.setHint("用户名（3-24 位字母/数字/下划线）");
        userInput.setSingleLine(true);
        box.addView(userInput);

        final EditText passInput = new EditText(this);
        passInput.setHint("密码（至少 8 位）");
        passInput.setSingleLine(true);
        passInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.addView(passInput);

        final EditText pass2Input = new EditText(this);
        pass2Input.setHint("再输一次密码");
        pass2Input.setSingleLine(true);
        pass2Input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.addView(pass2Input);

        TextView hint = new TextView(this);
        hint.setText("注册即送 25 积分，可直接用来购买路线。");
        hint.setTextSize(12);
        hint.setTextColor(getColor(R.color.textSecondary));
        hint.setPadding(0, pad / 2, 0, 0);
        box.addView(hint);

        new MaterialAlertDialogBuilder(this)
                .setTitle("注册 lain42 账号")
                .setView(box)
                .setPositiveButton("注册", (d, w) -> {
                    String u = userInput.getText().toString().trim();
                    String p = passInput.getText().toString();
                    String p2 = pass2Input.getText().toString();
                    if (u.isEmpty() || p.isEmpty()) {
                        toast("请输入用户名和密码");
                        return;
                    }
                    if (!u.matches("[A-Za-z0-9_]{3,24}")) {
                        toast("用户名需 3-24 位字母/数字/下划线");
                        return;
                    }
                    if (p.length() < 8) {
                        toast("密码至少 8 位");
                        return;
                    }
                    if (!p.equals(p2)) {
                        toast("两次输入的密码不一致");
                        return;
                    }
                    doRegister(u, p);
                })
                .setNegativeButton("返回登录", (d, w) -> showLoginDialog())
                .show();
    }

    private void doLogin(String username, String password) {
        setLoading(true);
        WebPointsManager.login(this, username, password, new SimpleCallback() {
            @Override void onBody(String body) {
                setLoading(false);
                try {
                    JSONObject o = new JSONObject(body);
                    String token = o.optString("token", "");
                    String user = o.optString("username", username);
                    if (token.isEmpty()) {
                        toast("登录失败");
                        return;
                    }
                    WebPointsManager.saveSession(RouteMarketActivity.this, token, user);
                    toast("登录成功");
                    refreshBalance();
                    loadSchools();
                    reload();
                } catch (Exception e) {
                    toast("登录失败：解析异常");
                }
            }

            @Override void onFail(String msg) {
                setLoading(false);
                toast("登录失败：" + msg);
            }
        });
    }

    private void doRegister(String username, String password) {
        setLoading(true);
        WebPointsManager.register(this, username, password, "", new SimpleCallback() {
            @Override void onBody(String body) {
                setLoading(false);
                try {
                    JSONObject o = new JSONObject(body);
                    String token = o.optString("token", "");
                    String user = o.optString("username", username);
                    if (token.isEmpty()) {
                        toast("注册失败");
                        return;
                    }
                    WebPointsManager.saveSession(RouteMarketActivity.this, token, user);
                    JSONObject bonus = o.optJSONObject("signup_bonus");
                    int pts = bonus == null ? 0 : bonus.optInt("points", 0);
                    toast(pts > 0 ? "注册成功，赠送 " + pts + " 积分" : "注册成功");
                    refreshBalance();
                    loadSchools();
                    reload();
                } catch (Exception e) {
                    toast("注册失败：解析异常");
                }
            }

            @Override void onFail(String msg) {
                setLoading(false);
                toast("注册失败：" + msg);
            }
        });
    }

    /** 拉一次余额，让账号栏显示最新值。 */
    private void refreshBalance() {
        WebPointsManager.fetchMe(this, new SimpleCallback() {
            @Override void onBody(String body) {
                try {
                    JSONObject o = new JSONObject(body);
                    JSONObject pts = o.optJSONObject("points");
                    if (pts != null) {
                        WebPointsManager.saveBalance(RouteMarketActivity.this,
                                pts.optInt("balance", 0));
                    }
                } catch (Exception ignored) {
                }
            }

            @Override void onFail(String msg) {
            }
        });
    }

    private void showTopUpDialog() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("积分不足")
                .setMessage("充值或兑换积分后再来购买。")
                .setPositiveButton("去充值", (d, w) -> {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW,
                                Uri.parse(WebPointsManager.SITE_BUY)));
                    } catch (Exception ignored) {
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void toast(String msg) {
        GoUtils.DisplayToast(this, msg);
    }

    /* ---------------- 适配器 ---------------- */

    private final class MarketAdapter extends BaseAdapter {
        @Override public int getCount() {
            return items.size();
        }

        @Override public Object getItem(int position) {
            return items.get(position);
        }

        @Override public long getItemId(int position) {
            return items.get(position).id;
        }

        @Override public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView != null ? convertView
                    : LayoutInflater.from(RouteMarketActivity.this)
                    .inflate(R.layout.item_route_market, parent, false);
            RouteMarketClient.Item it = items.get(position);

            ((TextView) v.findViewById(R.id.item_name)).setText(it.name);
            ((TextView) v.findViewById(R.id.item_meta)).setText(
                    (it.school.isEmpty() ? "未填学校" : it.school)
                            + (it.city.isEmpty() ? "" : " · " + it.city));
            TextView desc = v.findViewById(R.id.item_desc);
            desc.setText(it.description.isEmpty() ? "—" : it.description);
            ((TextView) v.findViewById(R.id.item_stats)).setText(
                    it.pointsCount + " 个点 · " + it.distanceText()
                            + " · ↓ " + it.downloads + " 次下载"
                            + (it.sales > 0 ? " · 售出 " + it.sales : ""));
            ((TextView) v.findViewById(R.id.item_price)).setText(it.price + " 积分");

            MaterialButton like = v.findViewById(R.id.btn_like);
            like.setText((it.liked ? "♥ " : "♡ ") + it.likes);
            like.setOnClickListener(x -> toggleLike(it));

            MaterialButton action = v.findViewById(R.id.btn_action);
            if (it.owned) {
                action.setText("下载");
                action.setOnClickListener(x -> download(it));
            } else if (it.isOwner) {
                action.setText("我的");
                action.setOnClickListener(x ->
                        toast("这是你自己上传的路线，可在「我的上传」管理"));
            } else {
                action.setText("购买");
                action.setOnClickListener(x -> buy(it));
            }

            android.widget.ImageView preview = v.findViewById(R.id.item_preview);
            if (it.hasPreview) {
                preview.setVisibility(View.VISIBLE);
                com.acooldog.toolbox.config.ImageLoader.load(preview, it.previewUrl(),
                        getResources().getDisplayMetrics().widthPixels);
            } else {
                preview.setVisibility(View.GONE);
                preview.setImageBitmap(null);
                preview.setTag(null);
            }
            return v;
        }
    }

    /** 只关心成功 body / 失败提示的回调，统一切回主线程。 */
    private abstract class SimpleCallback implements Callback {
        private final android.os.Handler main =
                new android.os.Handler(android.os.Looper.getMainLooper());

        abstract void onBody(String body);

        abstract void onFail(String msg);

        @Override public void onFailure(Call call, java.io.IOException e) {
            final String msg = "网络错误：" + e.getMessage();
            main.post(() -> {
                if (!isFinishing()) {
                    onFail(msg);
                }
            });
        }

        @Override public void onResponse(Call call, Response response) {
            String body;
            try {
                body = response.body() == null ? "" : response.body().string();
            } catch (Exception e) {
                body = "";
            }
            final boolean ok = response.isSuccessful();
            final String text = body;
            final int code = response.code();
            final String err = ok ? "" : RouteMarketClient.errorOf(body);
            main.post(() -> {
                if (isFinishing()) {
                    return;
                }
                if (ok) {
                    onBody(text);
                } else {
                    onFail(err.isEmpty() ? ("HTTP " + code) : err);
                }
            });
        }
    }
}
