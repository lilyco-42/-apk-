package com.acooldog.toolbox.config;

import androidx.annotation.NonNull;

import com.acooldog.toolbox.BuildConfig;

import java.io.IOException;

import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 统一 App 出站的 User-Agent。
 *
 * <p>OkHttp 默认发 {@code User-Agent: okhttp/4.12.0}。站点反代（pingap 的
 * {@code block_script_bots} 规则）把以 {@code okhttp/} 开头的 UA 当成自动化脚本，
 * 直接回 403 加一段非 JSON 纯文本，客户端解析失败后只会显示「服务器返回数据解析失败」，
 * 真实原因被完全掩盖。</p>
 *
 * <p>调用方显式设置的 UA（{@code .header("User-Agent", ...)}）优先，本拦截器不会覆盖。</p>
 */
public final class HttpUserAgent implements Interceptor {

    /** UA 前缀，需与服务端放行规则一致。 */
    public static final String PREFIX = "PaDouBuPao";

    /** 实际发送的 UA，形如 {@code PaDouBuPao/v2.4.2}。 */
    public static final String VALUE = PREFIX + "/" + BuildConfig.VERSION_NAME;

    private static final HttpUserAgent INSTANCE = new HttpUserAgent();

    public static HttpUserAgent get() {
        return INSTANCE;
    }

    private HttpUserAgent() {
    }

    @NonNull
    @Override
    public Response intercept(@NonNull Chain chain) throws IOException {
        Request request = chain.request();
        if (request.header("User-Agent") != null) {
            return chain.proceed(request);
        }
        return chain.proceed(request.newBuilder().header("User-Agent", VALUE).build());
    }
}
