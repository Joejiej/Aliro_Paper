package com.example.ailiro_ud;

import android.app.Service;
import android.content.Intent;
import android.nfc.cardemulation.HostApduService;
import android.os.Bundle;
import android.util.Log;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;

public class MyHostApduService extends HostApduService {

    private static final byte[] SELECT_OK_SW = {(byte) 0x90, 0x00};
    private static final byte[] UNKNOWN_CMD_SW = {0x6D, 0x00};

    private static final byte[] AID = {
            (byte)0xF0, 0x10, 0x20, 0x30, 0x40, 0x50
    };

    private final Map<Byte, ByteArrayOutputStream> apduCache = new HashMap<>();
    private final Map<Byte, Queue<byte[]>> responseQueue = new HashMap<>();

    private static final int MAX_APDU_CHUNK = 240; // 一次最多返回240字节（留点空间）

    private static final String TAG = "MyHostApduService";

    private EccSessionManager eccManager;

    private UserDevice userDevice;
    private byte lastIns = (byte)0x00;

    //异步签名标识
    private volatile boolean isSigning = false;

    @Override
    public void onCreate() {
        super.onCreate();
        logToActivity("MyHostApduService 已启动，等待NFC连接...");
    }

    @Override
    public byte[] processCommandApdu(byte[] apdu, Bundle extras) {
        if (!UserDeviceHolder.isInitialized()) {
            logToActivity("UserDevice 未初始化");
            return new byte[]{(byte) 0x6F, (byte) 0x00};
        }

        userDevice = UserDeviceHolder.get();

        Log.d(TAG, "=== APDU Processing Start ===");


        if (apdu == null || apdu.length < 4) {
            logToActivity("接收到无效 APDU");
            return UNKNOWN_CMD_SW;
        }

        byte cla = apdu[0];
        byte ins = apdu[1];
        byte p1 = apdu[2];
        byte lc = apdu[4];

        int tag = 0;

        try {
            switch ((byte)ins) {
                case (byte) 0xA4: // SELECT AID
//                    logToActivity("接收到 SELECT 指令，生成临时密钥对...");
                    eccManager = new EccSessionManager();
                    eccManager.generateTempKeyPair();
//                    logToActivity("临时公钥生成成功");
                    if(checkSelectApdu(apdu)){
                        return SELECT_OK_SW;
                    }
                    return UNKNOWN_CMD_SW;

                case (byte) 0x80: // AUTH0 分包命令
                    this.lastIns = ins;
                    return handleChunkedApdu(ins, p1, apdu, lc);

                case (byte) 0x81: // AUTH1 分包命令
                    this.lastIns = ins;
                    return handleChunkedApdu(ins, p1, apdu, lc);

                case (byte) 0xD1: // LOADCERT 分包命令
                    this.lastIns = ins;
                    return  handleChunkedApdu(ins, p1, apdu, lc);

                //提供响应分包机制
                case (byte) 0xC0: { // 取下一包
                    // 查找当前活跃的响应队列（假设上一条命令为 AUTH0 或 AUTH1）
                    byte followins = this.lastIns;
                    return getNextChunkWith61(followins);
                }

                default:
                    logToActivity("未知指令 INS = " + String.format("%02X", ins));
                    return UNKNOWN_CMD_SW;
            }
        } catch (Exception e) {
            logToActivity("处理APDU出错: " + e.getMessage());
            Log.e("MyHostApduService", "APDU处理异常", e);
            return UNKNOWN_CMD_SW;
        }
    }

    /**
     * 分包处理逻辑
     */
    private byte[] handleChunkedApdu(byte ins, byte p1, byte[] apdu, byte lc) throws Exception {
        if (apdu.length < 5 + (lc & 0xFF) ) {
            logToActivity("APDU 数据长度异常");
            return UNKNOWN_CMD_SW;
        }

        // 取出 Data 部分
        byte[] dataPart = Arrays.copyOfRange(apdu, 5, 5 + (lc & 0xFF));

        // 获取或创建缓存
        ByteArrayOutputStream buffer = apduCache.computeIfAbsent(ins, k -> new ByteArrayOutputStream());
        buffer.write(dataPart);
        byte[] fullResponse = null;

        Queue<byte[]> chunks = responseQueue.get(ins);
        byte[] nextChunk = (chunks != null) ? chunks.poll() : null;

        if (p1 == (byte) 0x10) {
            // 后续还有包
            logToActivity("收到分包（INS=" + String.format("%02X", ins) + "），缓存长度=" + buffer.size());

            return new byte[]{(byte)0x90, (byte) 0x00}; // 表示“继续发送下一包”
        } else {
            // 最后一包
            byte[] fullData = buffer.toByteArray();
            apduCache.remove(ins); // 清除缓存
            logToActivity("分包接收完成，共 " + fullData.length + " 字节");

            // 调用实际处理函数
            if (ins == (byte) 0x80) {
                logToActivity("========");
                logToActivity("开始AUTH0认证阶段");
                responseQueue.clear();
                fullResponse = userDevice.AUTH0response(fullData);
                logToActivity("AUTH0认证阶段结束");
                logToActivity("========");
            } else if (ins == (byte) 0x81) {
                logToActivity("========");
                logToActivity("开始AUTH1认证阶段");
                logToActivity("启动签名器进行异步签名");

                this.isSigning = true;
                this.lastIns = ins;
                responseQueue.clear();

                // 启动子线程，解开主线程死锁
                new Thread(() -> {
                    try {
                        // 这里内部会 bindService，因为主线程即将空闲，所以能成功
                        byte[] serviceFullResponse = userDevice.AUTH1response(fullData);

                        // 将结果切分并存入队列
                        Queue<byte[]> serviceChunks = new LinkedList<>();
                        for (int i = 0; i < serviceFullResponse.length; i += MAX_APDU_CHUNK) {
                            int end = Math.min(serviceFullResponse.length, i + MAX_APDU_CHUNK);
                            serviceChunks.add(Arrays.copyOfRange(serviceFullResponse, i, end));
                        }
                        responseQueue.put(ins, serviceChunks);

                        logToActivity("AUTH1认证阶段结束");
                        logToActivity("========");
                        logToActivity("签名完成，结果已存入队列");
                    } catch (Exception e) {
                        logToActivity("签名出错: " + e.getMessage());
                    } finally {
                        this.isSigning = false; // 标记结束
                    }
                }).start();

                // 关键：立即返回，让主线程释放！
                // 61 00 让对方等待
                return new byte[]{(byte)0x61, (byte)0x00};

            } else if (ins == (byte) 0xD1){
                logToActivity("========");
                logToActivity("开始LOADCERT阶段");
                responseQueue.clear();
                fullResponse = userDevice.LOADCERT_Response(fullData);
                logToActivity("LOADCERT阶段结束");
                logToActivity("========");
            } else {
                return UNKNOWN_CMD_SW;
            }

            // 分包存入队列
            chunks = new LinkedList<>();
            for (int i = 0; i < fullResponse.length; i += MAX_APDU_CHUNK) {
                int end = Math.min(fullResponse.length, i + MAX_APDU_CHUNK);
                byte[] chunk = Arrays.copyOfRange(fullResponse, i, end);
                chunks.add(chunk);
            }
            responseQueue.put(ins, chunks);

            logToActivity("生成响应分包，共 " + chunks.size() + " 包");

            return getNextChunkWith61(ins);

        }

    }


    private byte[] getNextChunkWith61(byte ins) throws InterruptedException {
        Queue<byte[]> chunks = responseQueue.get(ins);


        if (this.isSigning) {
            // 0x63 00 或者是自定义的等待码，让读卡器过几百毫秒再发 C0
            Thread.sleep(500);
            return new byte[]{(byte)0x61, (byte)0x00};
        }

        if (chunks == null && (this.isSigning==true)) {
            logToActivity("还在等待响应");
            return new byte[]{(byte)0x61, (byte)0x00}; // 无数据
        }
        byte[] chunk = chunks.poll();
        if (chunks.isEmpty()) {
            responseQueue.remove(ins); // 发完了就清理
            return concat(chunk, new byte[]{(byte)0x90, (byte)0x00});
        } else {
            // 计算剩余字节总数
            int remaining = 0;
            for (byte[] b : chunks) remaining += b.length;

            // 标准 61xx：xx 是剩余长度，最大 FF (255)
            byte sw2 = (remaining > 255) ? (byte)0xFF : (byte)remaining;

            // 记录当前正在分发哪个 INS 的后续，供 GET RESPONSE 使用
            this.lastIns = ins;

            return concat(chunk, new byte[]{(byte)0x61, sw2});
        }
    }

    private boolean checkSelectApdu(byte[] apdu) {
        logToActivity("接受SELECT选择");
        if (apdu.length < 5 + AID.length) return false;
        int lc = apdu[4] & 0xFF;
        byte[] aid = Arrays.copyOfRange(apdu, 5, 5 + lc);
        return Arrays.equals(aid, AID);
    }
    @Override
    public void onDeactivated(int reason) {
        logToActivity("HCE 连接断开，原因: " + reason);
        apduCache.clear();
        responseQueue.clear();
        if (eccManager != null) {
            eccManager.destroy();
            eccManager = null;
        }
    }

    private byte[] concat(byte[] a, byte[] b) {
        byte[] result = new byte[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }

    /** 向 AuthActivity 发送日志广播 */
    private void logToActivity(String message) {
        Intent intent = new Intent("HCE_LOG_EVENT");
        intent.putExtra("log", message);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
        Log.i("MyHostApduService", message);
    }
}