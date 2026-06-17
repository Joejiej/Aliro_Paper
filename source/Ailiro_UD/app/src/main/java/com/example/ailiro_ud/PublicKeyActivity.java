package com.example.ailiro_ud;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.security.PublicKey;

import com.example.ailiro_ud.databinding.ActivityPublicKeyBinding;

public class PublicKeyActivity extends AppCompatActivity {

    private static final String TAG = "PublicKeyActivity";
    private static final String ALIAS_UDECC = "user_device_ecc_key";
    private static final String ALIAS_DIKEK = "DilithiumKek";

    private ActivityPublicKeyBinding binding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityPublicKeyBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        binding.topAppBar.setNavigationOnClickListener(v -> finish());

        try {
            // 只读 Keystore 中的 ECC 公钥
            PublicKey publicKey = KeyStoreManager.getPublicKey(ALIAS_UDECC);

            if (publicKey == null) {
                binding.contentView.setText("未能获取椭圆曲线公钥，请检查密钥是否存在。");
                binding.copyButton.setEnabled(false);
                return;
            }

            String base64PubKey =
                    Base64.encodeToString(publicKey.getEncoded(), Base64.NO_WRAP);

            String info =
                    "算法: " + publicKey.getAlgorithm() + "\n\n" +
                            "格式: " + publicKey.getFormat() + "\n\n" +
                            "Base64 编码公钥:\n" + base64PubKey;

            binding.contentView.setText(info);

            binding.copyButton.setOnClickListener(v -> {
                ClipboardManager clipboard =
                        (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clip =
                        ClipData.newPlainText("PublicKey", base64PubKey);
                clipboard.setPrimaryClip(clip);
                Toast.makeText(this, "公钥已复制到剪贴板", Toast.LENGTH_SHORT).show();
            });

            if(!KeyStoreManager.hasAESKey(ALIAS_DIKEK)){
                Log.e(TAG, "Dilithiumeke加载失败");
            }

        } catch (Exception e) {
            Log.e(TAG, "公钥加载失败", e);
            binding.contentView.setText("公钥加载失败：" + e.getMessage());
        }
    }

}
