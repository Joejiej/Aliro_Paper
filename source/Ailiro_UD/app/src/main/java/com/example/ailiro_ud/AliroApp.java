package com.example.ailiro_ud;

import android.app.Application;
import android.content.Context;
import android.util.Log;

public class AliroApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();

// 1. 获取当前进程名
        String processName = getProcessName(this);
        Log.i("AliroApp", "Process starting: " + processName);

        // 2. 只有主进程才初始化 KeyStore 相关业务
        // 主进程的进程名等于包名 "com.example.ailiro_ud"
        if (getPackageName().equals(processName)) {
            Log.i("AliroApp", "Running in Main Process, initializing PQC Key Managers...");

            Context appCtx = getApplicationContext();
            // 会话PQC（一次性）
            PQKeyManager.init(
                    appCtx,
                    "DilithiumKek",
                    "KyberKek"
            );
        } else {
            // 这里是 :pqc_signer 进程或其他独立进程
            Log.i("AliroApp", "Running in Sub-Process (" + processName + "), skipping heavy initialization.");
        }
    }

    private String getProcessName(Context context) {
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            return Application.getProcessName();
        }
        // 适配旧版本 Android 的通用方案
        int pid = android.os.Process.myPid();
        android.app.ActivityManager am = (android.app.ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (am != null) {
            for (android.app.ActivityManager.RunningAppProcessInfo processInfo : am.getRunningAppProcesses()) {
                if (processInfo.pid == pid) {
                    return processInfo.processName;
                }
            }
        }
        return "";
    }
}
