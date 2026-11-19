package com.carlos.common.tsl;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import com.lody.virtual.client.VClient;
import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.client.ipc.VLocationManager;
import com.lody.virtual.os.VUserHandle;
import com.lody.virtual.remote.vloc.VLocation;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
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
    /** 本进程是否已经把影子 dex 插入加载器，插入过就不再重复插入 */
    private static final AtomicBoolean sInjected = new AtomicBoolean(false);

    private static long sLastCheck;

    private TslShadowInstaller() {
    }

    /**
     * @return true 表示本进程不需要再调用（已安装，或无望安装），调用方可结束轮询
     */
    public static boolean tryInstall() {
        if (sInstalled.get() || sInjected.get()) {
            return true;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - sLastCheck < 1500) {
            return false;
        }
        sLastCheck = now;

        ClassLoader appLoader;
        try {
            appLoader = VClient.get().getClassLoader();
        } catch (Throwable t) {
            return false;
        }
        if (appLoader == null) {
            return false;
        }
        VLocation location = virtualLocation();
        if (location == null) {
            return false;
        }
        synchronized (TslShadowInstaller.class) {
            if (sInstalled.get() || sInjected.get()) {
                return true;
            }
            try {
                Context context = VirtualCore.get().getContext();
                if (context == null) {
                    return false;
                }
                File dex = extractDex(context);
                if (dex == null) {
                    Log.e(TAG, "shadow dex not found");
                    return false;
                }
                inject(appLoader, dex, context);
                // 插入后就不再重复插入，否则 dexElements 会不断增长
                sInjected.set(true);
                Class<?> shadowClass = Class.forName(CLS_MANAGER, true, appLoader);
                Object mark = shadowClass.getField("SHADOW_MARK").get(null);
                if (!"virtual".equals(mark)) {
                    Log.e(TAG, "unexpected shadow mark " + mark);
                    return true;
                }
                push(shadowClass, location);
                sInstalled.set(true);
                startPushLoop(appLoader);
                Log.i(TAG, "installed pkg=" + VClient.get().getCurrentPackage()
                        + " loader=" + appLoader.getClass().getName()
                        + " paths=" + dexPaths(appLoader)
                        + " location=" + location);
                return true;
            } catch (Throwable t) {
                Log.e(TAG, "install failed: " + t, t);
                return sInjected.get();
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

    /**
     * 解压影子 dex。
     *
     * 注意：绝不能直接覆盖已被其它进程 mmap 的旧文件——副本进程（企业微信有多个
     * 进程）会各自解压到同一路径，若在这里 FileOutputStream 截断文件，正在使用
     * 该 dex 的进程会因映射页失效触发 SIGBUS(BUS_ADRERR) 原生崩溃，表现为卡在
     * 开屏页。这里改为“内容相同则复用，否则写临时文件后原子改名”。
     */
    private static File extractDex(Context context) {
        File dir = new File(context.getFilesDir(), "tsl_shadow");
        if (!dir.exists() && !dir.mkdirs()) {
            return null;
        }
        File target = new File(dir, ASSET_DEX);
        byte[] data = readAsset(context, ASSET_DEX);
        if (data == null || data.length == 0) {
            return null;
        }
        if (target.length() == data.length && Arrays.equals(data, readAll(target))) {
            return target;
        }
        File tmp = new File(dir, ASSET_DEX + "." + android.os.Process.myPid() + ".tmp");
        FileOutputStream output = null;
        try {
            output = new FileOutputStream(tmp);
            output.write(data);
            output.flush();
            output.getFD().sync();
            output.close();
            output = null;
            if (tmp.renameTo(target)) {
                return target;
            }
            tmp.delete();
            return target.length() > 0 ? target : null;
        } catch (Throwable t) {
            Log.e(TAG, "extract dex failed: " + t);
            if (output != null) {
                try {
                    output.close();
                } catch (Throwable ignored) {
                }
            }
            tmp.delete();
            return null;
        }
    }

    private static byte[] readAsset(Context context, String name) {
        InputStream input = null;
        try {
            input = context.getAssets().open(name);
            ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) > 0) {
                out.write(buffer, 0, count);
            }
            return out.toByteArray();
        } catch (Throwable t) {
            Log.e(TAG, "read asset failed: " + t);
            return null;
        } finally {
            if (input != null) {
                try {
                    input.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static byte[] readAll(File file) {
        FileInputStream input = null;
        try {
            input = new FileInputStream(file);
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) file.length());
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) > 0) {
                out.write(buffer, 0, count);
            }
            return out.toByteArray();
        } catch (Throwable t) {
            return null;
        } finally {
            if (input != null) {
                try {
                    input.close();
                } catch (Throwable ignored) {
                }
            }
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
