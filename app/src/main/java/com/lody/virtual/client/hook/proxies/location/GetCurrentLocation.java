package com.lody.virtual.client.hook.proxies.location;

import android.os.Bundle;
import android.util.Log;

import com.lody.virtual.client.hook.base.MethodProxy;
import com.lody.virtual.client.ipc.VLocationManager;
import com.lody.virtual.remote.vloc.VLocation;

import java.lang.reflect.Method;

/**
 * Android 11(API 30)+ 新增的 ILocationManager.getCurrentLocation 一次性定位接口，
 * 原框架未拦截，导致打卡类应用拿到的是真实位置。这里在虚拟定位开启时直接
 * 回传虚拟位置，不再向系统请求。
 */
public class GetCurrentLocation extends MethodProxy {

    private static final String TAG = "LocFix";

    @Override
    public String getMethodName() {
        return "getCurrentLocation";
    }

    @Override
    public Object call(Object who, Method method, Object... args) throws Throwable {
        if (!isFakeLocationEnable()) {
            return super.call(who, method, args);
        }
        Object transport = VLocationManager.findListenerTransport(args);
        VLocation vLocation = VLocationManager.get().getCurAppLocation();
        Log.i(TAG, "getCurrentLocation transport="
                + (transport == null ? "null" : transport.getClass().getName())
                + " fake=" + (vLocation == null ? "null"
                : vLocation.latitude + "," + vLocation.longitude));
        if (transport != null && vLocation != null) {
            VLocationManager.deliverLocation(transport, vLocation.toSysLocation());
        }
        finishCallback(args);
        return null;
    }

    private static void finishCallback(Object[] args) {
        try {
            Class<?> callbackType = Class.forName("android.os.IRemoteCallback");
            for (Object arg : args) {
                if (arg != null && callbackType.isInstance(arg)) {
                    callbackType.getMethod("sendResult", Bundle.class).invoke(arg, (Object) null);
                    return;
                }
            }
        } catch (Throwable ignored) {
        }
    }
}
