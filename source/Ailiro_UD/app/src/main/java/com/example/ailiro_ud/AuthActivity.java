package com.example.ailiro_ud;

import static com.example.ailiro_ud.KeyStoreManager.getPublicKey;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Typeface;
import android.nfc.NfcAdapter;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.security.KeyChain;
import android.security.KeyChainAliasCallback;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.example.ailiro_ud.databinding.ActivityAuthBinding;
import com.upokecenter.cbor.CBORObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Security;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.X509EncodedKeySpec;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import android.util.Base64;

import org.bouncycastle.jce.provider.BouncyCastleProvider;

import java.util.List;
import java.util.Map;


public class AuthActivity extends AppCompatActivity {

    private static final String TAG = "AuthActivity";
    private static final String ALIAS_UDECC = "user_device_ecc_key";
    private AccessDataElement dataelement;
    private static PublicKey devicePK = null;

    private PublicKey CIPK = null;
    private ActivityAuthBinding binding;
    private Access_Document AD;
    private UserDevice userdevice;
    private Handler mainHandler;
    private TextView logTextView;


    // APDU命令类型常量
    private static final int STATE_IDLE = 0;
    private static final int STATE_AUTH0 = 1;
    private static final int STATE_LOADCERT = 2;
    private static final int STATE_AUTH1 = 3;

    private int currentState = STATE_IDLE;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityAuthBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        mainHandler = new Handler(Looper.getMainLooper());

        // 使用 ViewBinding 不再需要 findViewById
        // logTextView 已经被 logContainer 替代（用于气泡日志）
        LinearLayout logContainer = findViewById(R.id.logContainer);
        ScrollView logScrollView = findViewById(R.id.logScrollView);

        if (logContainer == null || logScrollView == null) {
            Log.e("AuthActivity", "❌ logContainer 或 logScrollView 没有在布局中找到！");
        }

        // 按钮事件
        binding.selectCertButton.setOnClickListener(v -> chooseUserCertificate());

        // 工具栏返回按钮
        binding.toolbar.setNavigationOnClickListener(v -> finish());

        // 初始化设备逻辑
        initUserDevice();

        // 第一条日志
        appendLog("认证页面已启动，等待 APDU 命令...");

        // 注册日志广播接收器
        LocalBroadcastManager lbm = LocalBroadcastManager.getInstance(this);
        lbm.registerReceiver(hceLogReceiver, new IntentFilter("HCE_LOG_EVENT"));
    }

    private BroadcastReceiver hceLogReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String log = intent.getStringExtra("log");
            // 在日志窗口或 TextView 显示
            Log.i("AuthActivityLog", log);
            appendLog(log);
            // 若你有 TextView:
            // textView.append(log + "\n");
        }
    };

//    private void initViews() {
//        // 设置工具栏
//        Toolbar toolbar = binding.toolbar;
//        setSupportActionBar(toolbar);
//        if (getSupportActionBar() != null) {
//            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
//            getSupportActionBar().setTitle("设备认证");
//        }
//
//        logTextView = binding.logTextView;
//        mainHandler = new Handler(Looper.getMainLooper());
//    }

    private void initdataelement(){
        dataelement = new AccessDataElement("1","WBL_backdoor");
        int ac = AccessDataElement.SECURE | AccessDataElement.PAYMENT_PERMISSION;
        int allowbits = 0b00000001;
        int denybits = 0b00000010;

        dataelement.setAccessRules(ac, allowbits,denybits);
        dataelement.AccessDocument_to_form();

    }
    private void initAD() throws Exception {
        devicePK = getPublicKey(ALIAS_UDECC);
        initdataelement();

        String dataelement_id = dataelement.getId();
        ArrayList<String> dataelement_id_list = new ArrayList<>();
        dataelement_id_list.add(dataelement_id);

        Map<Integer, Object> elementvalue = dataelement.getAccessDataelement();
        ArrayList<Map<Integer, Object>> element_list = new ArrayList<>();
        element_list.add(elementvalue);

        AD = new Access_Document(devicePK,  dataelement_id_list, element_list);
        byte[] unsignedItem = AD.get_IssuerSignedItems_cbor();

    }
    private void initUserDevice() {
        try {

            // 检查 CIPK 是否为空（即用户证书未选择）
            if (CIPK == null) {
                runOnUiThread(() -> {
                    new AlertDialog.Builder(this)
                            .setTitle("证书未加载")
                            .setMessage("尚未选择用户证书，请先点击“选择证书”按钮。")
                            .setCancelable(false)
                            .setPositiveButton("确定", (dialog, which) -> {
                                dialog.dismiss();
                                // 用户点击确定后调用选择证书逻辑
                                chooseUserCertificate();
                            })
                            .show();
                });
                return; // 不继续执行初始化
            }
            initAD();
            String filepath = "AD_signedItem.txt";
            byte[] signedItem = loadSignatureFromAssets(this,filepath);
            AD.get_IssuerSigned(signedItem);
            AD.form_IssuerAuth();
            userdevice = new UserDevice(this.getApplicationContext(),AD);
            UserDeviceHolder.set(userdevice);
            String PQCIPKpath = "dilithium3_selfsigned_cert.pem";
            X509Certificate PQCIcert = loadCertificate(this.getApplicationContext() ,PQCIPKpath);
            CIPK = PQCIcert.getPublicKey();

            UserDeviceHolder.setCIPK(CIPK);
            appendLog("UserDevice 初始化完成，HCE服务可立即使用");
            Log.e(TAG, "UserDevice初始化成功");

            byte[] AD_bytes = AD.getAD_CBOR();
            Map<Integer, Object> get_dict = Access_Document.get_decode(AD_bytes);

            List<Map<Integer, Object>> list2 = (List<Map<Integer, Object>>) get_dict.get(2);

            //反序列化过程中key的int会被转为string,做一次强制转换
            List<Map<Integer,Object>> convertedList = TrustFramework.convertList(list2);

            CBORObject cborArray = CBORObject.NewArray();
            for (Map<Integer, Object> map : convertedList) {
                CBORObject cborMap = CBORObject.NewMap();

                for (Map.Entry<Integer, Object> entry : map.entrySet()) {
                    cborMap.Add(entry.getKey(), CBORObject.FromObject(entry.getValue()));
                }

                cborArray.Add(cborMap);
            }

            byte[] getist = cborArray.EncodeToBytes();

//            boolean vr = ECC.verifySignature(getist,signedItem,CIPK);
        } catch (Exception e) {
            appendLog("UserDevice初始化失败: " + e.getMessage());
            Log.e(TAG, "初始化UserDevice失败", e);
        }
    }

    public static X509Certificate loadCertificate(Context context, String pemPath) throws Exception {
        // 1. 必须注册 BC Provider 以支持后量子算法
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }

        // 2. 指定使用 BC 供应者解析 X.509 证书
        CertificateFactory certFactory = CertificateFactory.getInstance("X.509");

        // 3. 根据路径打开输入流 (兼容 assets 或绝对路径)
        try (InputStream fis = getInputStream(context, pemPath)) {
            // generateCertificate 会自动处理 PEM 的 Base64 解码和头尾识别
            return (X509Certificate) certFactory.generateCertificate(fis);
        }
    }

    private static InputStream getInputStream(Context context, String path) throws Exception {
        try {
            return context.getAssets().open(path);
        } catch (Exception e) {
            return new FileInputStream(path);
        }
    }

    public void chooseUserCertificate() {
        KeyChain.choosePrivateKeyAlias(
                AuthActivity.this,
                alias -> {
                    Log.i("KeyChain", "alias() 回调触发, alias=" + alias);
                    if (alias != null) {
                        loadCertificateFromKeyChain(alias);

                        // 确保 Activity 还在运行
                        if (!isFinishing() && !isDestroyed()) {
                            new Handler(Looper.getMainLooper()).post(() -> {
                                appendLog("证书已选择，重新初始化 UserDevice...");
                                initUserDevice();
                            });
                        } else {
                            Log.w("KeyChain", "Activity 已销毁，无法更新 UI");
                        }
                    }
                },
                null, null, null, -1, null
        );
    }

    private void loadCertificateFromKeyChain(String alias) {
        try {
            PrivateKey privateKey = KeyChain.getPrivateKey(this, alias);
            X509Certificate[] certChain = KeyChain.getCertificateChain(this, alias);
            if (privateKey != null && certChain != null&& certChain.length > 0) {
                X509Certificate cert = certChain[0];
                CIPK = cert.getPublicKey();
                Log.i("KeyChain", "已成功加载证书与私钥: " + certChain[0].getSubjectDN());
            } else {
                Log.w("KeyChain", "未找到私钥或证书链");
            }
        } catch (Exception e) {
            Log.e("KeyChain", "加载证书失败: " + e.getMessage(), e);
        }
    }



    private void checkFileExists() {
        try {
            // 列出assets目录下所有文件
            String[] fileList = getAssets().list("");
            Log.d("Assets", "assets目录下的文件列表:");
            for (String file : fileList) {
                Log.d("Assets", "文件: " + file);
            }

            // 检查特定文件是否存在
            boolean fileExists = false;
            for (String file : fileList) {
                if (file.equals("AD_signedItem.txt")) {
                    fileExists = true;
                    break;
                }
            }

            if (fileExists) {
                Log.d("Assets", "AD_signedItem.txt 文件存在");
            } else {
                Log.e("Assets", "AD_signedItem.txt 文件不存在");
            }

        } catch (IOException e) {
            Log.e("Assets", "无法读取assets目录", e);
        }
    }
    /**
     * 从Base64 Assets文件读取签名
     */
    public byte[] loadSignatureFromAssets(Context context, String fileName) {
        try {
            // 检查文件是否存在
            String[] assetsFiles = context.getAssets().list("");
            boolean fileExists = false;
            for (String file : assetsFiles) {
                if (file.equals(fileName)) {
                    fileExists = true;
                    break;
                }
            }

            if (!fileExists) {
                Log.e("Signature", "文件不存在: " + fileName);
                return null;
            }

            // 读取文件
            InputStream inputStream = context.getAssets().open(fileName);
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int length;
            while ((length = inputStream.read(buffer)) != -1) {
                result.write(buffer, 0, length);
            }

            // 解码
            String base64String = result.toString(StandardCharsets.UTF_8.name())
                    .replaceAll("\\s", "");
            Log.d("Signature", "Base64头: " + base64String.substring(0, 30));
            Log.d("Signature", "Base64尾: " + base64String.substring(base64String.length() - 30));

            byte[] signature = Base64.decode(base64String, Base64.NO_WRAP);


            Log.d("Signature", "签名解码成功，长度: " + signature.length + " bytes");

            return signature;

        } catch (Exception e) {
            Log.e("Signature", "读取签名失败", e);
            return null;
        }
    }



    /**
     * 添加日志到界面
     */
    private void appendLog(String message) {
        mainHandler.post(() -> {
            String timestamp = "";
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                timestamp = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
            }

            String logMessage = "[" + timestamp + "] " + message;

            TextView logView = new TextView(this);
            logView.setText(logMessage);
            logView.setTextSize(13f);
            logView.setTypeface(Typeface.MONOSPACE);
            logView.setTextColor(getColor(R.color.logTextColor));
            logView.setBackground(ContextCompat.getDrawable(this, R.drawable.bg_log_bubble));

            // 圆角阴影效果（可选）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                logView.setElevation(4f); // 轻微浮起感
            }

            logView.setPadding(32, 20, 32, 20);

            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            params.setMargins(0, 8, 0, 8);
            logView.setLayoutParams(params);

            LinearLayout logContainer = findViewById(R.id.logContainer);
            logContainer.addView(logView);

            // 动画：淡入 + 上浮 + 弱阴影渐变
            logView.setAlpha(0f);
            logView.setTranslationY(30f);
            logView.setElevation(0f);
            logView.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(300)
                    .setInterpolator(new DecelerateInterpolator())
                    .withEndAction(() -> logView.setElevation(4f))
                    .start();

            ScrollView scrollView = findViewById(R.id.logScrollView);
            scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
        });
    }



    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);

        // 处理NFC intent
        if (NfcAdapter.ACTION_TECH_DISCOVERED.equals(intent.getAction())) {
            appendLog("NFC设备已连接");
            // 这里可以处理NFC连接建立
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mainHandler != null) {
            mainHandler.removeCallbacksAndMessages(null);
        }
    }
}