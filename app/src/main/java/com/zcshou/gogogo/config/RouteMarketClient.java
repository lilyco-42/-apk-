package com.acooldog.toolbox.config;

import android.content.Context;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;

/**
 * 路线社区客户端（lain42.top /studio/api/routes*）。
 *
 * 接口：
 *   GET    /routes                      列表，支持 school / q / sort / limit / offset
 *   GET    /routes/schools              学校聚合（做筛选下拉）
 *   GET    /routes/mine                 我上传的 / 我购买的 / 累计收益
 *   GET    /routes/{id}                 详情（未购买不含 route 内容）
 *   GET    /routes/{id}/preview         预览图
 *   POST   /routes                      上传（price >= 10）
 *   POST   /routes/{id}/buy             积分购买，平台抽成 10%
 *   POST   /routes/{id}/like            点赞 / 取消
 *   POST   /routes/{id}/download        已购下载
 *   DELETE /routes/{id}                 下架自己的
 */
public final class RouteMarketClient {

    public static final String BASE = WebPointsManager.API_BASE;

    /** 价格下限，与服务端一致。 */
    public static final int MIN_PRICE = 10;
    /** 平台抽成百分比。 */
    public static final int PLATFORM_FEE_PCT = 10;

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build();

    private RouteMarketClient() {
    }

    /** 卖家实得积分。 */
    public static int sellerIncome(int price) {
        int fee = Math.max(1, (int) Math.ceil(price * PLATFORM_FEE_PCT / 100.0));
        return Math.max(0, price - fee);
    }

    /** 平台抽成积分。 */
    public static int platformFee(int price) {
        return price - sellerIncome(price);
    }

    /* ---------------- 请求底层 ---------------- */

    private static Request.Builder base(String path, Context c) {
        Request.Builder b = new Request.Builder().url(BASE + path);
        String token = WebPointsManager.getToken(c);
        if (token != null && !token.isEmpty()) {
            b.addHeader("Authorization", "Bearer " + token);
        }
        return b;
    }

    private static void get(Context c, String path, Callback cb) {
        CLIENT.newCall(base(path, c).get().build()).enqueue(cb);
    }

    private static void post(Context c, String path, JSONObject body, Callback cb) {
        String payload = body == null ? "{}" : body.toString();
        CLIENT.newCall(base(path, c)
                .post(RequestBody.create(payload, JSON))
                .build()).enqueue(cb);
    }

    /* ---------------- 列表 ---------------- */

    public static void list(Context c, String school, String query, String sort,
                            int limit, int offset, Callback cb) {
        StringBuilder sb = new StringBuilder("/routes?limit=").append(limit)
                .append("&offset=").append(offset)
                .append("&sort=").append(sort == null ? "new" : sort);
        if (school != null && !school.isEmpty()) {
            sb.append("&school=").append(encode(school));
        }
        if (query != null && !query.trim().isEmpty()) {
            sb.append("&q=").append(encode(query.trim()));
        }
        get(c, sb.toString(), cb);
    }

    public static void schools(Context c, Callback cb) {
        get(c, "/routes/schools", cb);
    }

    public static void mine(Context c, Callback cb) {
        get(c, "/routes/mine", cb);
    }

    public static void detail(Context c, int id, Callback cb) {
        get(c, "/routes/" + id, cb);
    }

    public static void like(Context c, int id, Callback cb) {
        post(c, "/routes/" + id + "/like", null, cb);
    }

    public static void buy(Context c, int id, Callback cb) {
        post(c, "/routes/" + id + "/buy", null, cb);
    }

    public static void download(Context c, int id, Callback cb) {
        post(c, "/routes/" + id + "/download", null, cb);
    }

    public static void upload(Context c, String name, String school, String city,
                              String description, int price, JSONObject route,
                              String previewDataUrl, Callback cb) {
        JSONObject body = new JSONObject();
        try {
            body.put("name", name);
            body.put("school", school);
            body.put("city", city == null ? "" : city);
            body.put("description", description == null ? "" : description);
            body.put("price", price);
            body.put("route", route);
            if (previewDataUrl != null && !previewDataUrl.isEmpty()) {
                body.put("preview_data_url", previewDataUrl);
            }
        } catch (Exception ignored) {
        }
        post(c, "/routes", body, cb);
    }

    public static void remove(Context c, int id, Callback cb) {
        Request req = base("/routes/" + id, c).delete().build();
        CLIENT.newCall(req).enqueue(cb);
    }

    /* ---------------- 解析 ---------------- */

    public static String encode(String raw) {
        try {
            return java.net.URLEncoder.encode(raw, "UTF-8");
        } catch (Exception e) {
            return raw;
        }
    }

    /** 从任意响应体里取错误提示。 */
    public static String errorOf(String raw) {
        if (raw == null) {
            return "";
        }
        try {
            JSONObject o = new JSONObject(raw);
            String d = o.optString("detail", "");
            if (!d.isEmpty()) {
                return d;
            }
            return o.optString("message", "");
        } catch (Exception e) {
            return "";
        }
    }

    public static Item parseItem(@NonNull JSONObject o) {
        Item it = new Item();
        it.id = o.optInt("id", 0);
        it.name = o.optString("name", "");
        it.school = o.optString("school", "");
        it.city = o.optString("city", "");
        it.description = o.optString("description", "");
        it.pointsCount = o.optInt("points_count", 0);
        it.distanceM = o.optDouble("distance_m", 0);
        it.price = o.optInt("price", 0);
        it.fee = o.optInt("fee", platformFee(it.price));
        it.sellerIncome = o.optInt("seller_income", sellerIncome(it.price));
        it.downloads = o.optInt("downloads", 0);
        it.sales = o.optInt("sales", 0);
        it.likes = o.optInt("likes", 0);
        it.hasPreview = o.optBoolean("has_preview", false);
        it.owned = o.optBoolean("owned", false);
        it.isOwner = o.optBoolean("is_owner", false);
        it.liked = o.optBoolean("liked", false);
        it.createdAt = o.optString("created_at", "");
        JSONObject route = o.optJSONObject("route");
        if (route != null) {
            it.routeJson = route.toString();
        }
        return it;
    }

    public static List<Item> parseList(String raw) throws Exception {
        JSONObject o = new JSONObject(raw);
        JSONArray arr = o.optJSONArray("routes");
        List<Item> out = new ArrayList<>();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                out.add(parseItem(arr.getJSONObject(i)));
            }
        }
        return out;
    }

    public static List<String> parseSchools(String raw) throws Exception {
        JSONObject o = new JSONObject(raw);
        JSONArray arr = o.optJSONArray("schools");
        List<String> out = new ArrayList<>();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                String s = arr.getJSONObject(i).optString("school", "");
                if (!s.isEmpty()) {
                    out.add(s);
                }
            }
        }
        return out;
    }

    public static final class Item {
        public int id;
        public String name = "";
        public String school = "";
        public String city = "";
        public String description = "";
        public int pointsCount;
        public double distanceM;
        public int price;
        public int fee;
        public int sellerIncome;
        public int downloads;
        public int sales;
        public int likes;
        public boolean hasPreview;
        public boolean owned;
        public boolean isOwner;
        public boolean liked;
        public String createdAt = "";
        public String routeJson;

        public String distanceText() {
            if (distanceM >= 1000) {
                return String.format(java.util.Locale.CHINA, "%.2f km", distanceM / 1000.0);
            }
            return String.format(java.util.Locale.CHINA, "%.0f m", distanceM);
        }

        public String previewUrl() {
            return BASE + "/routes/" + id + "/preview";
        }
    }
}
