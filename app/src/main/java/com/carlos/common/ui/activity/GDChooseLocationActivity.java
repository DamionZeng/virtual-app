package com.carlos.common.ui.activity;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.widget.Toolbar;

import com.carlos.R;
import com.carlos.common.ui.activity.base.VActivity;
import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.client.ipc.VirtualLocationManager;
import com.lody.virtual.remote.vloc.VLocation;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

/**
 * 虚拟定位地点选择页：使用百度地图 JS SDK 提供地图浏览、地点搜索与选择。
 *
 * 注意：app/sdk/lib-release.aar 中已移除同名的旧实现（高德地图版本），
 * 由本类接管 MirrorActivity 中“地点选择”的跳转。
 */
public class GDChooseLocationActivity extends VActivity {

    private static final String EXTRA_LOCATION = "virtual_location";
    private static final String EXTRA_PACKAGE = "virtual.extras.package";
    private static final String EXTRA_USERID = "virtual.extras.userid";
    private static final String EXTRA_ADDRESS = "virtual.extras.address";

    /** 默认中心点（BD09 坐标，北京天安门附近）。 */
    private static final double DEFAULT_LAT = 39.915119;
    private static final double DEFAULT_LNG = 116.403963;

    private static final int MODE_STOP = 0;
    private static final int MODE_MOCK = 2;

    private WebView mWebView;
    private ProgressBar mProgress;
    private TextView mErrorView;

    private String mPackageName;
    private int mUserId;
    private VLocation mLocation;
    private String mAddress;
    private boolean mMocking;
    private boolean mFinished;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(getLayoutId());

        Toolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle(R.string.choose_location_title);
        setSupportActionBar(toolbar);
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setTitle(R.string.choose_location_title);
        }
        enableBackHome();

        mWebView = findViewById(R.id.webview);
        mProgress = findViewById(R.id.progress);
        mErrorView = findViewById(R.id.tv_error);

        Intent intent = getIntent();
        mPackageName = intent.getStringExtra(EXTRA_PACKAGE);
        if (mPackageName == null) {
            mPackageName = "";
        }
        mUserId = intent.getIntExtra(EXTRA_USERID, 0);
        mLocation = intent.getParcelableExtra(EXTRA_LOCATION);
        mAddress = intent.getStringExtra(EXTRA_ADDRESS);

        initWebView();
        loadMap();
    }

    private int getLayoutId() {
        return R.layout.activity_baidu_location;
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void initWebView() {
        WebSettings settings = mWebView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        mWebView.setBackgroundColor(0x00000000);
        mWebView.addJavascriptInterface(new JsBridge(), "Android");
        mWebView.setWebChromeClient(new WebChromeClient());
        mWebView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return true;
            }
        });
    }

    private void loadMap() {
        String ak = getString(R.string.baidu_map_ak).trim();
        if (TextUtils.isEmpty(ak) || ak.startsWith("YOUR_") || ak.startsWith("请")) {
            showError(getString(R.string.baidu_map_ak_missing));
            return;
        }

        double lat = DEFAULT_LAT;
        double lng = DEFAULT_LNG;
        if (mLocation != null && !isEmpty(mLocation)) {
            // 虚拟定位内部使用 GCJ-02，百度地图使用 BD-09，展示前需要转换。
            double[] bd = gcj02ToBd09(mLocation.getLatitude(), mLocation.getLongitude());
            lat = bd[0];
            lng = bd[1];
        }

        String html = readAsset("baidu_map.html");
        if (html == null) {
            showError(getString(R.string.baidu_map_load_failed));
            return;
        }
        html = html.replace("__AK__", jsEscape(ak))
                .replace("__LAT__", String.format(Locale.US, "%.8f", lat))
                .replace("__LNG__", String.format(Locale.US, "%.8f", lng))
                .replace("__ADDRESS__", jsEscape(mAddress))
                .replace("__MOCKING__", String.valueOf(isMocking()));
        mWebView.loadDataWithBaseURL("https://api.map.baidu.com/", html, "text/html", "utf-8", null);
    }

    private boolean isMocking() {
        if (mMocking) {
            return true;
        }
        try {
            mMocking = VirtualLocationManager.get().isUseVirtualLocation(mUserId, mPackageName);
        } catch (Exception ignored) {
            mMocking = false;
        }
        return mMocking;
    }

    private void showError(String message) {
        if (mProgress != null) {
            mProgress.setVisibility(View.GONE);
        }
        if (mErrorView != null) {
            mErrorView.setText(message);
            mErrorView.setVisibility(View.VISIBLE);
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    /**
     * 保存选择的位置并按原有逻辑开启模拟（会重启分身进程使定位立即生效）。
     */
    private void saveAndStartMock(double bdLat, double bdLng, String address) {
        if (mFinished) {
            return;
        }
        if (TextUtils.isEmpty(mPackageName)) {
            Toast.makeText(this, R.string.baidu_map_save_failed, Toast.LENGTH_SHORT).show();
            return;
        }

        double[] gcj = bd09ToGcj02(bdLat, bdLng);
        VLocation location = new VLocation(round8(gcj[0]), round8(gcj[1]));
        String finalAddress = address == null ? "" : address;

        try {
            VirtualCore.get().killApp(mPackageName, mUserId);
            VirtualLocationManager.get().setMode(mUserId, mPackageName, MODE_MOCK);
            VirtualLocationManager.get().setLocation(mUserId, mPackageName, location);
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.baidu_map_save_failed) + " " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
            return;
        }

        mLocation = location;
        mAddress = finalAddress;
        mFinished = true;

        Intent data = getIntent();
        data.putExtra(EXTRA_LOCATION, location);
        data.putExtra(EXTRA_PACKAGE, mPackageName);
        data.putExtra(EXTRA_USERID, mUserId);
        data.putExtra(EXTRA_ADDRESS, finalAddress);
        setResult(RESULT_OK, data);
        finish();
    }

    /**
     * 停止模拟，行为与旧版一致。
     */
    private void stopMock() {
        if (mFinished) {
            return;
        }
        try {
            VirtualLocationManager.get().setMode(mUserId, mPackageName, MODE_STOP);
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.baidu_map_save_failed) + " " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
            return;
        }

        VLocation location = new VLocation(0, 0);
        mFinished = true;

        Intent data = getIntent();
        data.putExtra(EXTRA_LOCATION, location);
        data.putExtra(EXTRA_PACKAGE, mPackageName);
        data.putExtra(EXTRA_USERID, mUserId);
        data.putExtra(EXTRA_ADDRESS, mAddress);
        setResult(RESULT_OK, data);
        finish();
    }

    private String readAsset(String name) {
        InputStream input = null;
        ByteArrayOutputStream output = null;
        try {
            input = getAssets().open(name);
            output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int length;
            while ((length = input.read(buffer)) != -1) {
                output.write(buffer, 0, length);
            }
            return new String(output.toByteArray(), "UTF-8");
        } catch (IOException e) {
            return null;
        } finally {
            if (input != null) {
                try {
                    input.close();
                } catch (IOException ignored) {
                }
            }
            if (output != null) {
                try {
                    output.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static boolean isEmpty(VLocation location) {
        return Math.abs(location.getLatitude()) < 0.000001
                && Math.abs(location.getLongitude()) < 0.000001;
    }

    private static double round8(double value) {
        return Math.round(value * 100000000d) / 100000000d;
    }

    private static String jsEscape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "")
                .replace("\n", "\\n");
    }

    /** GCJ-02 转 BD-09。 */
    private static double[] gcj02ToBd09(double lat, double lng) {
        double z = Math.sqrt(lng * lng + lat * lat)
                + 0.00002 * Math.sin(lat * Math.PI * 3000.0 / 180.0);
        double theta = Math.atan2(lat, lng)
                + 0.000003 * Math.cos(lng * Math.PI * 3000.0 / 180.0);
        return new double[]{z * Math.sin(theta) + 0.006, z * Math.cos(theta) + 0.0065};
    }

    /** BD-09 转 GCJ-02。 */
    private static double[] bd09ToGcj02(double lat, double lng) {
        double x = lng - 0.0065;
        double y = lat - 0.006;
        double z = Math.sqrt(x * x + y * y)
                - 0.00002 * Math.sin(y * Math.PI * 3000.0 / 180.0);
        double theta = Math.atan2(y, x)
                - 0.000003 * Math.cos(x * Math.PI * 3000.0 / 180.0);
        return new double[]{z * Math.sin(theta), z * Math.cos(theta)};
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mWebView != null) {
            mWebView.onResume();
        }
    }

    @Override
    protected void onPause() {
        if (mWebView != null) {
            mWebView.onPause();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (mWebView != null) {
            mWebView.removeJavascriptInterface("Android");
            mWebView.destroy();
            mWebView = null;
        }
        super.onDestroy();
    }

    public class JsBridge {

        @JavascriptInterface
        public void onMapReady() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (mProgress != null) {
                        mProgress.setVisibility(View.GONE);
                    }
                    if (mErrorView != null) {
                        mErrorView.setVisibility(View.GONE);
                    }
                }
            });
        }

        @JavascriptInterface
        public void onMapError(final String message) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (mErrorView != null) {
                        mErrorView.setText(message);
                        mErrorView.setVisibility(View.VISIBLE);
                    }
                }
            });
        }

        @JavascriptInterface
        public void onConfirm(final double latitude, final double longitude, final String address) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    saveAndStartMock(latitude, longitude, address);
                }
            });
        }

        @JavascriptInterface
        public void onStopMock() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    stopMock();
                }
            });
        }
    }
}
