package com.tencent.map.geolocation.sapp;

/**
 * 编译期存根：运行时真正加载的是企业微信 dex 中的同名接口。
 */
public interface TencentLocationListener {

    void onLocationChanged(TencentLocation location, int error, String reason);

    void onStatusUpdate(String name, int status, String desc);
}
