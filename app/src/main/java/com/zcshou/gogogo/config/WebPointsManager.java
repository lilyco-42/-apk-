package com.acooldog.toolbox.config;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;

import org.json.JSONObject;

/**
 * 对接 lain42.top 站点账号 + 积分体系。
 *
 * 服务端接口（已验证可用）：
 *   POST {BASE}/register        {username, password, email}  -> {ok, token, username, signup_bonus}
 *   POST {BASE}/login           {username, password}         -> {ok, token, username}
 *   GET  {BASE}/me              Authorization: Bearer <token> -> {ok, username, points:{balance}, entitlements}
 *   GET  {BASE}/points          Authorization: Bearer <token> -> {ok, points:{balance}}
 *   POST {BASE}/points/consume  {points, reason, reference}   -> {ok, points:{balance}, entry}
 *   POST {BASE}/redeem          {code}                       -> {ok, ...}  卡密兑换
 *
 * 失败统一返回 {"detail": "..."}。
 */
public final class WebPointsManager {

    /** 站点 API 基址（走 pingap -> billing:4700）。 */
    public static final String API_BASE = "https://lain42.top/studio/api";

    /** 用户侧页面（登录/充值/卡密兑换都在站点里）。 */
    public static final String SITE_HOME = "https://lain42.top/";
    public static final String SITE_BUY = "https://lain42.top/sub/buy.html";
    public static final String SITE_ACCOUNT = "https://lain42.top/account/";

    /** 每次开始一次模拟消耗多少积分（无畅跑卡时）。 */
    public static final int COST_PER_RUN = 1;

    /** 畅跑卡商品码，与站点 PRODUCTS 里的一致。 */
    public static final String PRODUCT_PASS = "paodou_6m";

    private static final String PREF_TOKEN = "pref_web_token";
    private static final String PREF_USER = "pref_web_username";
    private static final String PREF_BALANCE = "pref_web_balance";
    private static final String PREF_PASS_UNTIL = "pref_pass_until";
    private static final String PREF_PASS_CHECKED = "pref_pass_checked";

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .build();

    private WebPointsManager() {
    }

    private static SharedPreferences prefs(Context c) {
        return PreferenceManager.getDefaultSharedPreferences(c);
    }

    public static String getDeviceId(Context c) {
        String id = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ANDROID_ID);
        return id == null ? "unknown" : id;
    }

    /* ---------------- 本地状态 ---------------- */

    public static boolean isLoggedIn(Context c) {
        String t = prefs(c).getString(PREF_TOKEN, "");
        return t != null && !t.isEmpty();
    }

    public static String getToken(Context c) {
        return prefs(c).getString(PREF_TOKEN, "");
    }

    public static String getUsername(Context c) {
        return prefs(c).getString(PREF_USER, "");
    }

    public static int getBalance(Context c) {
        return prefs(c).getInt(PREF_BALANCE, 0);
    }

    public static void saveSession(Context c, String token, String username) {
        prefs(c).edit()
                .putString(PREF_TOKEN, token)
                .putString(PREF_USER, username)
                .apply();
    }

    public static void saveBalance(Context c, int balance) {
        prefs(c).edit().putInt(PREF_BALANCE, Math.max(0, balance)).apply();
    }

    public static void logout(Context c) {
        prefs(c).edit()
                .remove(PREF_TOKEN)
                .remove(PREF_USER)
                .remove(PREF_BALANCE)
                .remove(PREF_PASS_UNTIL)
                .remove(PREF_PASS_CHECKED)
                .apply();
    }

    /* ---------------- 畅跑卡 ---------------- */

    /** 本地缓存的到期时间（毫秒），0 表示没买过/已失效。 */
    public static long getPassUntil(Context c) {
        return prefs(c).getLong(PREF_PASS_UNTIL, 0L);
    }

    /** 缓存是否仍在有效期内。断网时靠它兜底，不至于把人锁死。 */
    public static boolean hasActivePass(Context c) {
        return getPassUntil(c) > System.currentTimeMillis();
    }

    /** 上次成功校验的时间，用于避免每次进页面都打网络。 */
    public static long getPassCheckedAt(Context c) {
        return prefs(c).getLong(PREF_PASS_CHECKED, 0L);
    }

    public static void savePass(Context c, long untilMillis) {
        prefs(c).edit()
                .putLong(PREF_PASS_UNTIL, untilMillis)
                .putLong(PREF_PASS_CHECKED, System.currentTimeMillis())
                .apply();
    }

    /** 剩余天数；没有卡返回 0。 */
    public static int getPassDaysLeft(Context c) {
        long left = getPassUntil(c) - System.currentTimeMillis();
        if (left <= 0) {
            return 0;
        }
        return (int) Math.ceil(left / 86400000.0);
    }

    /** 拉取权益状态，更新本地缓存。 */
    public static void fetchEntitlement(Context c, Callback cb) {
        send(c, "/paodou/entitlement", "GET", null, cb);
    }

    /** 解析 /paodou/entitlement 的返回，写回本地缓存。 */
    public static boolean applyEntitlement(Context c, String raw) {
        try {
            JSONObject o = new JSONObject(raw);
            if (!o.optBoolean("ok", false)) {
                return false;
            }
            if (!o.optBoolean("active", false)) {
                savePass(c, 0L);
                return false;
            }
            String expires = o.optString("expires_at", "");
            long until = parseIso(expires);
            if (until <= 0) {
                return false;
            }
            savePass(c, until);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 解析 2027-04-18T14:32:06+00:00 这种带时区的 ISO 时间。 */
    private static long parseIso(String s) {
        if (s == null || s.isEmpty()) {
            return 0L;
        }
        String[] patterns = {
                "yyyy-MM-dd'T'HH:mm:ssXXX",
                "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
                "yyyy-MM-dd'T'HH:mm:ss",
        };
        for (String p : patterns) {
            try {
                java.text.SimpleDateFormat f = new java.text.SimpleDateFormat(p, java.util.Locale.US);
                f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
                java.util.Date d = f.parse(s);
                if (d != null) {
                    return d.getTime();
                }
            } catch (Exception ignored) {
            }
        }
        return 0L;
    }

    /** 兑换畅跑卡（扣积分）。 */
    public static void purchasePass(Context c, Callback cb) {
        JSONObject json = new JSONObject();
        try {
            json.put("product", PRODUCT_PASS);
        } catch (Exception ignored) {
        }
        send(c, "/points/purchase", "POST", json, cb);
    }

    /* ---------------- 请求 ---------------- */

    private static void send(Context c, String path, String method, JSONObject payload, Callback cb) {
        Request.Builder builder = new Request.Builder().url(API_BASE + path);
        if ("GET".equals(method)) {
            builder.get();
        } else {
            RequestBody body = RequestBody.create(payload == null ? "{}" : payload.toString(), JSON);
            builder.post(body);
        }
        String token = getToken(c);
        if (token != null && !token.isEmpty()) {
            builder.addHeader("Authorization", "Bearer " + token);
        }
        CLIENT.newCall(builder.build()).enqueue(cb);
    }

    public static void login(Context c, String username, String password, Callback cb) {
        JSONObject json = new JSONObject();
        try {
            json.put("username", username);
            json.put("password", password);
        } catch (Exception ignored) {
        }
        send(c, "/login", "POST", json, cb);
    }

    public static void register(Context c, String username, String password, String email, Callback cb) {
        JSONObject json = new JSONObject();
        try {
            json.put("username", username);
            json.put("password", password);
            json.put("email", email == null ? "" : email);
        } catch (Exception ignored) {
        }
        send(c, "/register", "POST", json, cb);
    }

    /** 拉取账号信息（含积分余额）。 */
    public static void fetchMe(Context c, Callback cb) {
        send(c, "/me", "GET", null, cb);
    }

    /** 消费积分；reference 保证同一次扣费可重试而不会重复扣。 */
    public static void consume(Context c, int points, String reason, String reference, Callback cb) {
        JSONObject json = new JSONObject();
        try {
            json.put("points", points);
            json.put("reason", reason);
            json.put("reference", reference);
        } catch (Exception ignored) {
        }
        send(c, "/points/consume", "POST", json, cb);
    }

    /** 卡密兑换。 */
    public static void redeem(Context c, String code, Callback cb) {
        JSONObject json = new JSONObject();
        try {
            json.put("code", code);
        } catch (Exception ignored) {
        }
        send(c, "/redeem", "POST", json, cb);
    }

    /** 为一次模拟生成幂等引用号。 */
    public static String newReference(Context c) {
        return "paodoubp-" + getDeviceId(c) + "-" + System.currentTimeMillis();
    }

    /* ---------------- 解析 ---------------- */

    public static Result parse(@NonNull String raw) {
        Result r = new Result();
        try {
            JSONObject o = new JSONObject(raw);
            r.ok = o.optBoolean("ok", false);
            r.token = o.optString("token", "");
            r.username = o.optString("username", "");
            r.message = o.optString("detail", "");
            if (r.message.isEmpty()) {
                r.message = o.optString("message", "");
            }
            JSONObject pts = o.optJSONObject("points");
            if (pts != null) {
                r.balance = pts.optInt("balance", 0);
            } else if (o.has("balance")) {
                r.balance = o.optInt("balance", 0);
            }
            JSONObject bonus = o.optJSONObject("signup_bonus");
            if (bonus != null) {
                r.bonus = bonus.optInt("points", 0);
            }
        } catch (Exception e) {
            r.ok = false;
            r.message = "服务器返回数据解析失败";
        }
        return r;
    }

    public static final class Result {
        public boolean ok;
        public String token = "";
        public String username = "";
        public String message = "";
        public int balance;
        public int bonus;

        public boolean notEnough() {
            return message != null && message.contains("不足");
        }
    }
}
