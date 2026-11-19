package com.carlos.common;

import android.util.Log;

import com.carlos.common.tsl.TslShadowInstaller;
import com.lody.virtual.client.VClient;
import com.lody.virtual.client.core.InvocationStubManager;
import com.lody.virtual.client.hook.annotations.LogInvocation;
import com.lody.virtual.client.hook.base.MethodInvocationProxy;
import com.lody.virtual.client.hook.proxies.location.GetCurrentLocation;
import com.lody.virtual.client.hook.proxies.location.LocationManagerStub;
import com.lody.virtual.client.interfaces.IInjector;

/**
 * 宿主 Application。VirtualApp 初始化完成后，向已经注入的 LocationManagerStub
 * 追加 getCurrentLocation 代理（该方法在 Android 11+ 才存在，原框架未拦截），
 * 并打开定位服务的调用日志，便于排查目标应用走的定位接口。
 *
 * 同时在每个进程内启动守护线程，等被克隆应用绑定完成后把 ComposeClassLoader
 * 的应用类加载器替换成腾讯定位 SDK 的影子加载器。注意：克隆进程里
 * {@code super.onCreate()} 会因虚拟框架初始化抛异常，所以守护线程放在静态块里启动。
 */
public class AppEx extends App {

    private static final String TAG = "TslShadow";

    static {
        startTslShadowWatcher();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "AppEx.onCreate pid=" + android.os.Process.myPid()
                + " process=" + currentProcessName());
        installLocationHooks();
    }

    private void installLocationHooks() {
        try {
            IInjector injector = InvocationStubManager.getInstance().findInjector(LocationManagerStub.class);
            if (injector instanceof MethodInvocationProxy) {
                MethodInvocationProxy proxy = (MethodInvocationProxy) injector;
                proxy.addMethodProxy(new GetCurrentLocation());
                proxy.getInvocationStub().setInvocationLoggingCondition(LogInvocation.Condition.ALWAYS);
                Log.i(TAG, "gcl installed pid=" + android.os.Process.myPid()
                        + " init=" + InvocationStubManager.getInstance().isInit());
            } else {
                Log.i(TAG, "gcl injector null pid=" + android.os.Process.myPid()
                        + " init=" + InvocationStubManager.getInstance().isInit());
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    private static void startTslShadowWatcher() {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                String lastState = "";
                while (true) {
                    try {
                        ClassLoader compose = VClient.get().getClassLoader();
                        String pkg;
                        try {
                            pkg = VClient.get().getCurrentPackage();
                        } catch (Throwable t) {
                            pkg = "?" + t;
                        }
                        String state = (compose == null ? "null" : compose.getClass().getName())
                                + "/" + pkg;
                        if (!state.equals(lastState)) {
                            lastState = state;
                            Log.i(TAG, "bind state=" + state);
                        }
                        if (compose != null) {
                            TslShadowInstaller.tryInstall();
                        }
                    } catch (Throwable t) {
                        String err = "poll error " + t;
                        if (!err.equals(lastState)) {
                            lastState = err;
                            Log.i(TAG, err);
                        }
                    }
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        }, "tsl-shadow-watcher");
        thread.setDaemon(true);
        thread.start();
    }

    private static String currentProcessName() {
        try {
            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.FileReader("/proc/self/cmdline"));
            String line = reader.readLine();
            reader.close();
            return line == null ? "?" : line.trim();
        } catch (Throwable t) {
            return "?";
        }
    }
}
