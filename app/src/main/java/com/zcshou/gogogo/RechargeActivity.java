package com.acooldog.toolbox;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.acooldog.toolbox.config.WebPointsManager;

/**
 * 充值页：内嵌站点商城，自动带登录态，不用跳浏览器。
 *
 * 站点的充值/商城页面靠 Bearer token 调接口，token 存在 localStorage，
 * 所以进页面先注入 token，页面自己就能识别当前账号。
 */
public class RechargeActivity extends BaseActivity {

    private WebView webView;
    private ProgressBar progress;

    /** 直接落到商城充值页。 */
    private static final String URL_TOPUP = "https://lain42.top/shop/";

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_recharge);

        webView = findViewById(R.id.recharge_web);
        progress = findViewById(R.id.recharge_progress);
        TextView title = findViewById(R.id.recharge_title);
        if (title != null) {
            title.setText("充值积分");
        }
        TextView close = findViewById(R.id.recharge_close);
        if (close != null) {
            close.setOnClickListener(v -> finish());
        }

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setUserAgentString(s.getUserAgentString() + " PaoDouApp/2.4.1");

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                // 站内 + 爱发电收银台都留在 WebView，避免跳到外部浏览器
                if (url.startsWith("https://lain42.top")
                        || url.startsWith("https://afdian.com")
                        || url.startsWith("https://www.afdian.com")
                        || url.startsWith("https://ifdian.net")) {
                    return false;
                }
                try {
                    startActivity(new android.content.Intent(
                            android.content.Intent.ACTION_VIEW, request.getUrl()));
                } catch (Exception ignored) {
                }
                return true;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url != null && (url.startsWith("https://lain42.top")
                        || url.startsWith("https://afdian.com")
                        || url.startsWith("https://www.afdian.com")
                        || url.startsWith("https://ifdian.net"))) {
                    return false;
                }
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                // 页面脚本执行前就写入 token，页面首次拉数据即可识别登录态
                injectSession();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                progress.setVisibility(View.GONE);
                // 再写一次，覆盖页面初始化时可能清空的键
                injectSession();
            }
        });

        webView.setWebChromeClient(new android.webkit.WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progress.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
                progress.setProgress(newProgress);
            }
        });

        webView.loadUrl(getIntent().getStringExtra("url") != null
                ? getIntent().getStringExtra("url")
                : URL_TOPUP);
    }

    /** 把 App 的登录态写进页面 localStorage，页面刷新后即为已登录。 */
    private void injectSession() {
        String token = WebPointsManager.getToken(this);
        String user = WebPointsManager.getUsername(this);
        if (token == null || token.isEmpty()) {
            return;
        }
        // 站点各页面统一读 localStorage['wb-token']（见 /sub/assets/ui.js 的 tok()）
        String js = "(function(){try{"
                + "localStorage.setItem('wb-token'," + quote(token) + ");"
                + "localStorage.setItem('token'," + quote(token) + ");"
                + "localStorage.setItem('lain42_token'," + quote(token) + ");"
                + "localStorage.setItem('studio_token'," + quote(token) + ");"
                + "localStorage.setItem('username'," + quote(user) + ");"
                + "}catch(e){}})();";
        webView.evaluateJavascript(js, null);
    }

    private static String quote(String s) {
        if (s == null) {
            return "''";
        }
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }
}
