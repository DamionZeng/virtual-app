package com.tencent.map.geolocation.sapp;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.Pair;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;

/**
 * 腾讯定位 SDK（sapp）的影子实现。
 *
 * 该类会被单独编译成 tsl_shadow.dex，并在企业微信分身进程里插入到应用
 * PathClassLoader 的 dexElements 头部，从而先于企业微信自带的同名类被解析。
 * 因此这里不能依赖 VirtualApp 的类，坐标由宿主安装器通过静态字段写入。
 *
 * 修改本类后必须重新生成 app/src/main/assets/tsl_shadow.dex：
 * d8 --lib android.jar --lib &lt;三个存根 class 所在目录&gt; --output out
 * TencentLocationManager.class TencentLocationManager$1.class TencentLocationManager$2.class
 * 再把 out/classes.dex 覆盖到 assets/tsl_shadow.dex。
 */
public final class TencentLocationManager {

    private static final String TAG = "TslShadow";

    public static final String SHADOW_MARK = "virtual";

    public static final int COORDINATE_TYPE_GCJ02 = 1;
    public static final int COORDINATE_TYPE_WGS84 = 0;
    public static final String TYPE_OAID = "oaId";
    public static final String TYPE_QIMEI = "qImei";

    public static volatile boolean sEnabled;
    public static volatile double sLatitude;
    public static volatile double sLongitude;
    public static volatile float sAccuracy = 30f;
    public static volatile String sProvider = TencentLocation.GPS_PROVIDER;

    private static volatile TencentLocationManager sInstance;

    private TencentLocationManager() {
    }

    public static TencentLocationManager getInstance(Context context) {
        return instance();
    }

    public static TencentLocationManager getInstance(Context context, Pair pair) {
        return instance();
    }

    private static TencentLocationManager instance() {
        if (sInstance == null) {
            synchronized (TencentLocationManager.class) {
                if (sInstance == null) {
                    sInstance = new TencentLocationManager();
                    Log.i(TAG, "shadow instance created");
                }
            }
        }
        return sInstance;
    }

    public static void setDeviceID(Pair pair) {
    }

    public String getBuild() {
        return "shadow";
    }

    public String getVersion() {
        return "shadow";
    }

    public int getCoordinateType() {
        return COORDINATE_TYPE_GCJ02;
    }

    public void setCoordinateType(int type) {
    }

    public boolean startIndoorLocation() {
        return true;
    }

    public boolean stopIndoorLocation() {
        return true;
    }

    public TencentLocation getLastKnownLocation() {
        return build();
    }

    public int requestSingleFreshLocation(TencentLocationRequest request,
                                          TencentLocationListener listener, Looper looper) {
        return requestSingleFreshLocation(request, listener, looper, true);
    }

    public int requestSingleFreshLocation(TencentLocationRequest request,
                                          TencentLocationListener listener, Looper looper,
                                          boolean allowGps) {
        Log.i(TAG, "requestSingleFreshLocation allowGps=" + allowGps);
        deliver(listener, looper);
        return 0;
    }

    public int requestLocationUpdates(TencentLocationRequest request,
                                      TencentLocationListener listener) {
        Log.i(TAG, "requestLocationUpdates");
        deliver(listener, null);
        return 0;
    }

    public int requestLocationUpdates(TencentLocationRequest request,
                                      TencentLocationListener listener, Looper looper) {
        Log.i(TAG, "requestLocationUpdates looper=" + looper);
        deliver(listener, looper);
        return 0;
    }

    public void removeUpdates(TencentLocationListener listener) {
    }

    private static void deliver(final TencentLocationListener listener, Looper looper) {
        if (listener == null) {
            return;
        }
        final TencentLocation location = build();
        Runnable task = new Runnable() {
            @Override
            public void run() {
                try {
                    if (location == null) {
                        Log.w(TAG, "no virtual location");
                        listener.onLocationChanged(null, TencentLocation.ERROR_UNKNOWN,
                                "no virtual location");
                    } else {
                        listener.onLocationChanged(location, TencentLocation.ERROR_OK, "");
                        Log.i(TAG, "onLocationChanged " + sLatitude + "," + sLongitude);
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "callback failed: " + t);
                }
            }
        };
        if (looper != null) {
            new Handler(looper).post(task);
        } else {
            task.run();
        }
    }

    private static TencentLocation build() {
        if (!sEnabled) {
            return null;
        }
        final double latitude = sLatitude;
        final double longitude = sLongitude;
        final float accuracy = sAccuracy;
        final String provider = sProvider;
        return (TencentLocation) Proxy.newProxyInstance(
                TencentLocation.class.getClassLoader(),
                new Class<?>[]{TencentLocation.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        String name = method.getName();
                        if ("getLatitude".equals(name)) {
                            return latitude;
                        }
                        if ("getLongitude".equals(name)) {
                            return longitude;
                        }
                        if ("getAccuracy".equals(name)) {
                            return accuracy;
                        }
                        if ("getProvider".equals(name)) {
                            return provider;
                        }
                        if ("getTime".equals(name)) {
                            return System.currentTimeMillis();
                        }
                        if ("getElapsedRealtime".equals(name)) {
                            return SystemClock.elapsedRealtime();
                        }
                        if ("getExtra".equals(name)) {
                            return new Bundle();
                        }
                        if ("getAddress".equals(name)) {
                            return "";
                        }
                        if ("hashCode".equals(name)) {
                            return System.identityHashCode(proxy);
                        }
                        if ("equals".equals(name)) {
                            return proxy == args[0];
                        }
                        if ("toString".equals(name)) {
                            return "TencentLocation{" + latitude + "," + longitude + "}";
                        }
                        Class<?> returnType = method.getReturnType();
                        if (returnType == String.class) {
                            return "";
                        }
                        if (returnType == int.class) {
                            return 0;
                        }
                        if (returnType == long.class) {
                            return 0L;
                        }
                        if (returnType == float.class) {
                            return 0f;
                        }
                        if (returnType == double.class) {
                            return 0d;
                        }
                        if (returnType == boolean.class) {
                            return false;
                        }
                        if (List.class.isAssignableFrom(returnType)) {
                            return null;
                        }
                        return null;
                    }
                });
    }
}
