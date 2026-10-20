package com.acooldog.toolbox.config;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;
import android.widget.ImageView;

import androidx.annotation.Nullable;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 极简图片加载器：OkHttp 拉字节 + BitmapFactory 采样 + LruCache。
 *
 * 项目里没有 Glide/Coil，为了一张路线预览图不值得引新依赖，这里自己做一个够用的：
 * - 内存缓存（按 URL）
 * - 后台线程下载，回主线程 setImageBitmap
 * - 列表复用时会比对 tag，避免图片错位
 */
public final class ImageLoader {

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .build();

    private static final ExecutorService POOL = Executors.newFixedThreadPool(3);

    /** 约 8MB 的内存缓存。 */
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    private ImageLoader() {
    }

    public static void clear() {
        CACHE.evictAll();
    }

    /**
     * 绑定图片。tag 用于复用校验：下载完成时若 ImageView 的 tag 已经变了，
     * 说明这一格被别的路线占用了，直接丢弃。
     */
    public static void load(ImageView view, String url, int targetWidthPx) {
        if (view == null) {
            return;
        }
        view.setTag(url);
        Bitmap cached = CACHE.get(url);
        if (cached != null) {
            view.setImageBitmap(cached);
            return;
        }
        view.setImageBitmap(null);
        if (url == null || url.isEmpty()) {
            return;
        }
        final int reqW = Math.max(1, targetWidthPx);
        POOL.execute(() -> {
            Bitmap bitmap = fetch(url, reqW);
            if (bitmap == null) {
                return;
            }
            CACHE.put(url, bitmap);
            view.post(() -> {
                Object tag = view.getTag();
                if (tag instanceof String && url.equals(tag)) {
                    view.setImageBitmap(bitmap);
                }
            });
        });
    }

    @Nullable
    private static Bitmap fetch(String url, int targetWidth) {
        Response response = null;
        try {
            response = CLIENT.newCall(new Request.Builder().url(url).get().build()).execute();
            if (!response.isSuccessful() || response.body() == null) {
                return null;
            }
            byte[] raw = response.body().bytes();
            if (raw.length == 0) {
                return null;
            }
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(raw, 0, raw.length, bounds);
            int sample = 1;
            if (bounds.outWidth > 0) {
                while (bounds.outWidth / (sample * 2) >= targetWidth) {
                    sample *= 2;
                }
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeByteArray(raw, 0, raw.length, options);
        } catch (Exception e) {
            return null;
        } finally {
            if (response != null) {
                response.close();
            }
        }
    }
}
