package com.acooldog.toolbox;

import android.content.Context;
import android.content.res.Configuration;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

/**
 * 所有页面的基类。
 *
 * 关键点：attachBaseContext 里强制把 Locale 设成简体中文。
 * 这个 App 的中文字符串是全的，英文只有零星几条（values-en 里 30 条 vs values 里几百条），
 * 跟随系统语言时会出现「Route workspace / 路线模拟」这种中英混排。
 * 直接锁死中文，任何语言的系统上都显示一致的中文界面。
 */
public class BaseActivity extends AppCompatActivity {

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(AppLocale.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppLocale.applyTo(getResources());
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        androidx.core.view.WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView())
                .setAppearanceLightStatusBars(false);
        androidx.core.view.WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView())
                .setAppearanceLightNavigationBars(false);
    }

    @Override
    public void setContentView(int layoutResId) {
        super.setContentView(layoutResId);
        android.view.ViewGroup content = findViewById(android.R.id.content);
        android.view.View root = content.getChildAt(0);
        int left = root.getPaddingLeft();
        int top = root.getPaddingTop();
        int right = root.getPaddingRight();
        int bottom = root.getPaddingBottom();
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root, (view, windowInsets) -> {
            androidx.core.graphics.Insets insets = windowInsets.getInsets(
                    androidx.core.view.WindowInsetsCompat.Type.systemBars()
                            | androidx.core.view.WindowInsetsCompat.Type.displayCutout()
                            | androidx.core.view.WindowInsetsCompat.Type.ime());
            view.setPadding(left + insets.left, top + insets.top,
                    right + insets.right, bottom + insets.bottom);
            return androidx.core.view.WindowInsetsCompat.CONSUMED;
        });
        androidx.core.view.ViewCompat.requestApplyInsets(root);
    }

    @NonNull
    @Override
    public android.content.res.Resources getResources() {
        android.content.res.Resources res = super.getResources();
        AppLocale.applyTo(res);
        return res;
    }

    /** 仅供编译期校验，避免误删 import。 */
    @SuppressWarnings("unused")
    private void unusedConfigurationReference(Configuration configuration) {
    }
}
