package com.lody.virtual.client.ipc;

import android.app.PendingIntent;
import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import com.lody.virtual.client.VClient;
import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.client.hook.proxies.location.MockLocationHelper;
import com.lody.virtual.client.hook.utils.MethodParameterUtils;
import com.lody.virtual.helper.utils.Reflect;
import com.lody.virtual.helper.utils.VLog;
import com.lody.virtual.os.VUserHandle;
import com.lody.virtual.remote.vloc.VLocation;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @see LocationManager
 * <p>
 * 实现代码多，资源回收不及时：拦截gps状态，定位请求，并且交给虚拟定位服务，虚拟服务根据一样的条件，再次向系统定位服务请求
 * LocationManager.addgpslistener
 * LocationManager.request
 * <p>
 * 实现代码少：GpsStatusListenerTransport、ListenerTransport这2个对象，hook里面的方法，修改参数，都是binder
 */
public class VLocationManager {
    public static final String TAG = "VLoc";
    private static final String TAG_FIX = "LocFix";
    private static final String I_LOCATION_LISTENER = "android.location.ILocationListener";
    private Handler mWorkHandler;
    private HandlerThread mHandlerThread;
    private final List<Object> mGpsListeners = new ArrayList<>();
    private static VLocationManager sVLocationManager = new VLocationManager();

    private VLocationManager() {
        LocationManager locationManager = (LocationManager) VirtualCore.get().getContext().getSystemService(Context.LOCATION_SERVICE);
        MockLocationHelper.fakeGpsStatus(locationManager);
    }

    public static VLocationManager get() {
        return sVLocationManager;
    }


    private void checkWork() {
        if (mHandlerThread == null) {
            synchronized (this) {
                if (mHandlerThread == null) {
                    mHandlerThread = new HandlerThread("loc_thread");
                    mHandlerThread.start();
                }
            }
        }
        if (mWorkHandler == null) {
            synchronized (this) {
                if (mWorkHandler == null) {
                    mWorkHandler = new Handler(mHandlerThread.getLooper());
                }
            }
        }
    }

    private void stopGpsTask() {
        if (mWorkHandler != null) {
            mWorkHandler.removeCallbacks(mUpdateGpsStatusTask);
        }
    }

    private void startGpsTask() {
        checkWork();
        stopGpsTask();
        mWorkHandler.postDelayed(mUpdateGpsStatusTask, 5000);
    }

    private Runnable mUpdateGpsStatusTask = new Runnable() {
        @Override
        public void run() {
            synchronized (mGpsListeners) {
                for (Object listener : mGpsListeners) {
                    notifyGpsStatus(listener);
                }
            }
            mWorkHandler.postDelayed(mUpdateGpsStatusTask, 8000);
        }
    };


    public boolean hasVirtualLocation(String packageName, int userId) {
        try {
            return VirtualLocationManager.get().getMode(userId, packageName) != VirtualLocationManager.MODE_CLOSE;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }

    public boolean isProviderEnabled(String provider) {
        return LocationManager.GPS_PROVIDER.equals(provider);
    }

    public VLocation getLocation(String packageName, int userId) {
        return getVirtualLocation(packageName, null, userId);
    }

    public VLocation getCurAppLocation() {
        return getVirtualLocation(VClient.get().getCurrentPackage(), null, VUserHandle.myUserId());
    }

    public VLocation getVirtualLocation(String packageName, Location loc, int userId) {
        try {
            if (VirtualLocationManager.get().getMode(userId, packageName) == VirtualLocationManager.MODE_USE_GLOBAL) {
                return VirtualLocationManager.get().getGlobalLocation();
            } else {
                return VirtualLocationManager.get().getLocation(userId, packageName);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    public String getPackageName() {
        return VClient.get().getCurrentPackage();
    }

    public void removeGpsStatusListener(final Object[] args) {
        if (args[0] instanceof PendingIntent) {
            return;
        }
        boolean needStop;
        synchronized (mGpsListeners) {
            mGpsListeners.remove(args[0]);
            needStop = mGpsListeners.size() == 0;
        }
        if (needStop) {
            stopGpsTask();
        }
    }


    public void addGpsStatusListener(final Object[] args) {
        final Object GpsStatusListenerTransport = args[0];
        MockLocationHelper.invokeSvStatusChanged(GpsStatusListenerTransport);
        if (GpsStatusListenerTransport != null) {
            synchronized (mGpsListeners) {
                mGpsListeners.add(GpsStatusListenerTransport);
            }
        }
        checkWork();
        notifyGpsStatus(GpsStatusListenerTransport);
        startGpsTask();
    }

    private void notifyGpsStatus(final Object transport) {
        if (transport == null) {
            return;
        }
//        checkWork();
        mWorkHandler.post(new Runnable() {
            @Override
            public void run() {
//                GpsStatusGenerate.fakeGpsStatus(transport);
                MockLocationHelper.invokeSvStatusChanged(transport);
                MockLocationHelper.invokeNmeaReceived(transport);
            }
        });
    }

    public void removeUpdates(final Object[] args) {
        Object listener = findListenerTransport(args);
        if (listener == null && args.length > 0) {
            listener = args[0];
        }
        if (listener != null) {
            UpdateLocationTask task = getTask(listener);
            if (task != null) {
                task.stop();
            }
        }
    }

    /**
     * 不同系统版本的 ILocationManager.requestLocationUpdates 参数布局不同，
     * Android 12+ 中 transport 不一定在 index 1，这里统一按类型查找。
     */
    public static Object findListenerTransport(Object[] args) {
        if (args == null) {
            return null;
        }
        Class<?> listenerType = null;
        try {
            listenerType = Class.forName(I_LOCATION_LISTENER);
        } catch (Throwable ignored) {
        }
        if (listenerType != null) {
            for (Object arg : args) {
                if (arg != null && listenerType.isInstance(arg)) {
                    return arg;
                }
            }
        }
        for (Object arg : args) {
            if (arg != null && arg.getClass().getName().contains("ListenerTransport")) {
                return arg;
            }
        }
        return null;
    }

    public void requestLocationUpdates(Object[] args) {
        final Object listenerTransport = findListenerTransport(args);
        if (listenerTransport == null) {
            Log.e(TAG, "ListenerTransport:null");
            return;
        }
        VLocation location = getCurAppLocation();
        if (location == null) {
            Log.e(TAG, "VLocation:null");
            return;
        }
        long mInterval;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            try {
                mInterval = Reflect.on(args[0]).get("mInterval");
            } catch (Throwable e) {
                mInterval = 60 * 1000;
            }
        } else {
            mInterval = MethodParameterUtils.getFirstParam(args, Long.class);
        }
        Log.i(TAG_FIX, "requestLocationUpdates transport=" + listenerTransport.getClass().getName()
                + " interval=" + mInterval);
        checkWork();
        notifyLocation(listenerTransport, location.toSysLocation(), true);
        UpdateLocationTask task = getTask(listenerTransport);
        if (task == null) {
            synchronized (mLocationTaskMap) {
                task = new UpdateLocationTask(listenerTransport, mInterval);
                mLocationTaskMap.put(listenerTransport, task);
            }
        }
        task.start();
    }

    /**
     * Android 12+ 将 LocationManager$ListenerTransport 更名为
     * LocationManager$LocationListenerTransport，旧的 mirror 反射字段为 null，
     * 因此这里直接反射 transport 对象，兼容各版本的方法签名。
     */
    public static boolean deliverLocation(Object transport, Location location) {
        if (transport == null || location == null) {
            return false;
        }
        Class<?> clazz = transport.getClass();
        try {
            Field field = clazz.getDeclaredField("mListener");
            field.setAccessible(true);
            Object listener = field.get(transport);
            if (listener instanceof LocationListener) {
                ((LocationListener) listener).onLocationChanged(location);
                Log.i(TAG_FIX, "deliver(mListener) " + location.getLatitude() + "," + location.getLongitude());
                return true;
            }
        } catch (Throwable ignored) {
        }
        try {
            Class<?> callback = Class.forName("android.os.IRemoteCallback");
            Method method = clazz.getDeclaredMethod("onLocationChanged", List.class, callback);
            method.setAccessible(true);
            method.invoke(transport, Collections.singletonList(location), null);
            Log.i(TAG_FIX, "deliver(List,IRemoteCallback) " + location.getLatitude() + "," + location.getLongitude());
            return true;
        } catch (Throwable ignored) {
        }
        try {
            Method method = clazz.getDeclaredMethod("onLocationChanged", List.class);
            method.setAccessible(true);
            method.invoke(transport, Collections.singletonList(location));
            Log.i(TAG_FIX, "deliver(List) " + location.getLatitude() + "," + location.getLongitude());
            return true;
        } catch (Throwable ignored) {
        }
        try {
            Method method = clazz.getDeclaredMethod("onLocationChanged", Location.class);
            method.setAccessible(true);
            method.invoke(transport, location);
            Log.i(TAG_FIX, "deliver(Location) " + location.getLatitude() + "," + location.getLongitude());
            return true;
        } catch (Throwable t) {
            Log.e(TAG_FIX, "deliver failed for " + clazz.getName() + ": " + t);
        }
        return false;
    }

    private boolean notifyLocation(final Object ListenerTransport, final Location location, boolean post) {
        if (ListenerTransport == null || location == null) {
            return false;
        }
        if (!post) {
            return deliverLocation(ListenerTransport, location);
        }
        mWorkHandler.post(new Runnable() {
            @Override
            public void run() {
                deliverLocation(ListenerTransport, location);
            }
        });
        return true;
    }

    private final Map<Object, UpdateLocationTask> mLocationTaskMap = new HashMap<>();

    private UpdateLocationTask getTask(Object locationListener) {
        UpdateLocationTask task;
        synchronized (mLocationTaskMap) {
            task = mLocationTaskMap.get(locationListener);
        }
        return task;
    }

    private class UpdateLocationTask implements Runnable {
        private Object mListenerTransport;
        private long mTime;
        private volatile boolean mRunning;

        private UpdateLocationTask(Object ListenerTransport, long time) {
            mListenerTransport = ListenerTransport;
            mTime = time;
        }

        @Override
        public void run() {
            if (mRunning) {
                VLocation location = getCurAppLocation();
                if (location != null) {
                    if (notifyLocation(mListenerTransport, location.toSysLocation(), false)) {
                        start();
                    }
                }
            }
        }

        public void start() {
            mRunning = true;
            mWorkHandler.removeCallbacks(this);
            if (mTime > 0) {
                mWorkHandler.postDelayed(this, mTime);
            } else {
                mWorkHandler.post(this);
            }
        }

        public void stop() {
            mRunning = false;
            mWorkHandler.removeCallbacks(this);
        }
    }
}
