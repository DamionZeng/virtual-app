package com.carlos.common.tsl;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import com.lody.virtual.client.VClient;
import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.client.ipc.VLocationManager;
import com.lody.virtual.os.VUserHandle;
import com.lody.virtual.remote.vloc.VLocation;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 企业微信分身的腾讯定位 SDK（sapp）接管器。
 *
 * 企业微信的 TencentLocationManager 位于它自己的 dex，由分身进程的
 * PathClassLoader 定义（父加载器是 BootClassLoader，路径里只有企业微信 APK）。
 * 这里把只包含影子 TencentLocationManager 的 tsl_shadow.dex 插到该加载器
 * dexElements 的头部，使企业微信解析该类时命中影子实现；随后由本类把
 * VirtualApp 的虚拟坐标持续写入影子类的静态字段。
 */
public final class TslShadowInstaller {

    private static final String TAG = "TslShadow";

    private static final String CLS_MANAGER = "com.tencent.map.geolocation.sapp.TencentLocationManager";
    private static final String ASSET_DEX = "tsl_shadow.dex";

    private static final AtomicBoolean sInstalled = new AtomicBoolean(false);

    private static long sLastCheck;

    private TslShadowInstaller() {
    }

    public static void tryInstall() {
        if (sInstalled.get()) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - sLastCheck < 1500) {
            return;
        }
        sLastCheck = now;

        ClassLoader appLoader;
        try {
            appLoader = VClient.get().getClassLoader();
        } catch (Throwable t) {
            return;
        }
        if (appLoader == null) {
            return;
        }
        VLocation location = virtualLocation();
        if (location == null) {
            return;
        }
        synchronized (TslShadowInstaller.class) {
            if (sInstalled.get()) {
                return;
            }
            try {
                Context context = VirtualCore.get().getContext();
                if (context == null) {
                    return;
                }
                File dex = extractDex(context);
                if (dex == null) {
                    Log.e(TAG, "shadow dex not found");
                    return;
                }
                inject(appLoader, dex, context);
                Class<?> shadowClass = Class.forName(CLS_MANAGER, true, appLoader);
                Object mark = shadowClass.getField("SHADOW_MARK").get(null);
                if (!"virtual".equals(mark)) {
                    Log.e(TAG, "unexpected shadow mark " + mark);
                    return;
                }
                push(shadowClass, location);
                sInstalled.set(true);
                startPushLoop(appLoader);
                Log.i(TAG, "installed pkg=" + VClient.get().getCurrentPackage()
                        + " loader=" + appLoader.getClass().getName()
                        + " paths=" + dexPaths(appLoader)
                        + " location=" + location);
            } catch (Throwable t) {
                Log.e(TAG, "install failed: " + t, t);
            }
        }
    }

    private static VLocation virtualLocation() {
        try {
            String pkg = VClient.get().getCurrentPackage();
            int userId = VUserHandle.myUserId();
            if (!VLocationManager.get().hasVirtualLocation(pkg, userId)) {
                return null;
            }
            VLocation location = VLocationManager.get().getCurAppLocation();
            if (location == null || (location.latitude == 0 && location.longitude == 0)) {
                return null;
            }
            return location;
        } catch (Throwable t) {
            return null;
        }
    }

    private static File extractDex(Context context) {
        File dir = new File(context.getFilesDir(), "tsl_shadow");
        if (!dir.exists() && !dir.mkdirs()) {
            return null;
        }
        File target = new File(dir, ASSET_DEX);
        try {
            InputStream input = context.getAssets().open(ASSET_DEX);
            FileOutputStream output = new FileOutputStream(target);
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) > 0) {
                output.write(buffer, 0, count);
            }
            output.close();
            input.close();
            return target;
        } catch (Throwable t) {
            Log.e(TAG, "extract dex failed: " + t);
            return null;
        }
    }

    private static void inject(ClassLoader appLoader, File dexFile, Context context) throws Throwable {
        File optDir = new File(context.getFilesDir(), "tsl_shadow_opt");
        if (!optDir.exists() && !optDir.mkdirs()) {
            Log.w(TAG, "cannot create opt dir " + optDir);
        }
        dalvik.system.DexClassLoader shadowLoader = new dalvik.system.DexClassLoader(
                dexFile.getAbsolutePath(), optDir.getAbsolutePath(), null, null);
        Object shadowPathList = declarationField(shadowLoader.getClass(), "pathList")
                .get(shadowLoader);
        Field shadowElementsField = declarationField(shadowPathList.getClass(), "dexElements");
        Object[] shadowElements = (Object[]) shadowElementsField.get(shadowPathList);
        if (shadowElements == null || shadowElements.length == 0) {
            throw new IllegalStateException("empty shadow dex");
        }

        Object appPathList = declarationField(appLoader.getClass(), "pathList").get(appLoader);
        Field appElementsField = declarationField(appPathList.getClass(), "dexElements");
        Object[] appElements = (Object[]) appElementsField.get(appPathList);
        Object[] merged = (Object[]) Array.newInstance(
                appElements.getClass().getComponentType(), appElements.length + 1);
        merged[0] = shadowElements[0];
        System.arraycopy(appElements, 0, merged, 1, appElements.length);
        setDexElements(appElementsField, appPathList, merged);
        Log.i(TAG, "dex injected shadow=" + shadowElements.length
                + " app=" + appElements.length + " -> " + merged.length);
    }

    private static void startPushLoop(final ClassLoader appLoader) {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Class<?> shadowClass = Class.forName(CLS_MANAGER, true, appLoader);
                    while (true) {
                        VLocation location = virtualLocation();
                        if (location != null) {
                            push(shadowClass, location);
                        } else {
                            field(shadowClass, "sEnabled").setBoolean(null, false);
                        }
                        try {
                            Thread.sleep(300);
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                } catch (Throwable t) {
                    Log.e(TAG, "push loop failed: " + t);
                }
            }
        }, "tsl-location-push");
        thread.setDaemon(true);
        thread.start();
    }

    private static void push(Class<?> shadowClass, VLocation location) throws Throwable {
        field(shadowClass, "sLatitude").setDouble(null, location.latitude);
        field(shadowClass, "sLongitude").setDouble(null, location.longitude);
        field(shadowClass, "sAccuracy").setFloat(null, 30f);
        field(shadowClass, "sProvider").set(null, "gps");
        field(shadowClass, "sEnabled").setBoolean(null, true);
    }

    private static Field field(Class<?> clazz, String name) throws NoSuchFieldException {
        Field result = clazz.getField(name);
        result.setAccessible(true);
        return result;
    }

    private static Field declarationField(Class<?> clazz, String name) throws NoSuchFieldException {
        Class<?> current = clazz;
        while (current != null) {
            try {
                Field result = current.getDeclaredField(name);
                result.setAccessible(true);
                return result;
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static void setDexElements(Field field, Object pathList, Object[] elements)
            throws Throwable {
        try {
            field.set(pathList, elements);
            return;
        } catch (Throwable ignored) {
        }
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        Object unsafe = theUnsafe.get(null);
        Method objectFieldOffset = unsafeClass.getMethod("objectFieldOffset", Field.class);
        long offset = (Long) objectFieldOffset.invoke(unsafe, field);
        Method putObject = unsafeClass.getMethod("putObject",
                Object.class, long.class, Object.class);
        putObject.invoke(unsafe, pathList, offset, elements);
    }

    private static String dexPaths(ClassLoader loader) {
        try {
            Object pathList = declarationField(loader.getClass(), "pathList").get(loader);
            Field elementsField = declarationField(pathList.getClass(), "dexElements");
            Object[] elements = (Object[]) elementsField.get(pathList);
            if (elements == null) {
                return "0";
            }
            StringBuilder builder = new StringBuilder();
            builder.append(elements.length).append(':');
            Field pathField = declarationField(elements.getClass().getComponentType(), "path");
            int count = Math.min(elements.length, 8);
            for (int i = 0; i < count; i++) {
                if (i > 0) {
                    builder.append(',');
                }
                builder.append(pathField.get(elements[i]));
            }
            return builder.toString();
        } catch (Throwable t) {
            return "?" + t;
        }
    }
}
