package com.example.ailiro_ud;

import java.security.PublicKey;

//用于存放userdevice作为全局持有者，给apduservice调用
public class UserDeviceHolder {
    private static volatile UserDevice userDevice;

    public static void set(UserDevice device) {
        userDevice = device;
    }

    public static void setCIPK(PublicKey CIPK) throws Exception {
        userDevice.setCIPK(CIPK);
    }
    public static UserDevice get() {
        return userDevice;
    }

    public static boolean isInitialized() {
        return userDevice != null;
    }

    public static void clear() {
        userDevice = null;
    }
}
