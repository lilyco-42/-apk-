package com.acooldog.toolbox;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import android.os.LocaleList;

import java.util.Locale;

/**
 * 强制简体中文。
 *
 * 为什么不用 AppCompatDelegate.setApplicationLocales：
 * 本项目用 appcompat 1.5.1，该 API 需要 1.6.0+，用不了。
 *
 * 这里用最原始也最稳的办法：包装 Context 的 Resources，
 * 把 Configuration 的 locale 改成 zh-CN，再让 Resources 重新解析。
 */
final class AppLocale {

    static final Locale ZH = Locale.SIMPLIFIED_CHINESE;

    private AppLocale() {
    }

    static Context wrap(Context base) {
        Configuration config = new Configuration(base.getResources().getConfiguration());
        setLocale(config);
        return base.createConfigurationContext(config);
    }

    static void applyTo(Resources resources) {
        if (resources == null) {
            return;
        }
        Configuration config = resources.getConfiguration();
        if (isChinese(config)) {
            return;
        }
        Configuration updated = new Configuration(config);
        setLocale(updated);
        //noinspection deprecation
        resources.updateConfiguration(updated, resources.getDisplayMetrics());
    }

    private static boolean isChinese(Configuration config) {
        Locale current = current(config);
        return current != null && "zh".equals(current.getLanguage());
    }

    private static Locale current(Configuration config) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            LocaleList list = config.getLocales();
            return list.isEmpty() ? null : list.get(0);
        }
        //noinspection deprecation
        return config.locale;
    }

    private static void setLocale(Configuration config) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.setLocales(new LocaleList(ZH));
        } else {
            //noinspection deprecation
            config.setLocale(ZH);
        }
        config.setLayoutDirection(ZH);
    }
}
