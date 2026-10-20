package com.acooldog.toolbox.config;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * 把用户选中的图片压成服务端能收的 data URL。
 *
 * 服务端限制：PNG / JPEG，单张 <= 2 MiB，必须是 data:image/...;base64, 开头。
 * 这里统一输出 JPEG（预览图不需要透明），长边压到 1080，质量 80，
 * 一般能压到 100-200 KB，远低于上限。
 */
public final class PreviewImageEncoder {

    private static final int MAX_EDGE = 1080;
    private static final int QUALITY = 80;
    /** 服务端上限 2 MiB，留点余量。 */
    private static final int HARD_LIMIT = 1900 * 1024;

    private PreviewImageEncoder() {
    }

    /** 失败返回 null，调用方提示即可。 */
    public static String encode(Context context, Uri uri) {
        Bitmap bitmap = decode(context, uri);
        if (bitmap == null) {
            return null;
        }
        Bitmap scaled = null;
        try {
            scaled = scaleDown(bitmap, MAX_EDGE);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int quality = QUALITY;
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, out);
            while (out.size() > HARD_LIMIT && quality > 30) {
                quality -= 15;
                out.reset();
                scaled.compress(Bitmap.CompressFormat.JPEG, quality, out);
            }
            if (out.size() > HARD_LIMIT) {
                return null;
            }
            String base64 = android.util.Base64.encodeToString(
                    out.toByteArray(), android.util.Base64.NO_WRAP);
            return "data:image/jpeg;base64," + base64;
        } catch (Exception e) {
            return null;
        } finally {
            if (scaled != null && scaled != bitmap) {
                scaled.recycle();
            }
            if (bitmap != null && !bitmap.isRecycled()) {
                bitmap.recycle();
            }
        }
    }

    private static Bitmap decode(Context context, Uri uri) {
        try {
            InputStream in = context.getContentResolver().openInputStream(uri);
            if (in == null) {
                return null;
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int len;
            int total = 0;
            while ((len = in.read(buf)) != -1) {
                total += len;
                // 原图上限 12 MiB，防止有人选超大图把内存打爆
                if (total > 12 * 1024 * 1024) {
                    in.close();
                    return null;
                }
                bos.write(buf, 0, len);
            }
            in.close();
            byte[] raw = bos.toByteArray();

            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(raw, 0, raw.length, bounds);
            int sample = 1;
            if (bounds.outWidth > 0) {
                while (bounds.outWidth / (sample * 2) >= MAX_EDGE) {
                    sample *= 2;
                }
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            return BitmapFactory.decodeByteArray(raw, 0, raw.length, options);
        } catch (Exception e) {
            return null;
        }
    }

    private static Bitmap scaleDown(Bitmap source, int maxEdge) {
        int w = source.getWidth();
        int h = source.getHeight();
        int longEdge = Math.max(w, h);
        if (longEdge <= maxEdge) {
            return source;
        }
        float ratio = (float) maxEdge / longEdge;
        int nw = Math.max(1, Math.round(w * ratio));
        int nh = Math.max(1, Math.round(h * ratio));
        Bitmap out = Bitmap.createScaledBitmap(source, nw, nh, true);
        if (out != source) {
            source.recycle();
        }
        return out;
    }
}
