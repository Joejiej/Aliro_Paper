package com.example.ailiro_ud;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;

public class MainActivity extends AppCompatActivity {

    private MaterialButton startAuthButton;
    private MaterialButton genKeyButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        startAuthButton = findViewById(R.id.startAuthButton);
        genKeyButton = findViewById(R.id.genKeyButton);

        // 开始认证按钮逻辑
        startAuthButton.setOnClickListener(v -> {
            Toast.makeText(this, "开始认证流程...", Toast.LENGTH_SHORT).show();
            Intent intent = new Intent(this, AuthActivity.class);
            startActivity(intent);
        });

        // 生成公钥按钮逻辑
        genKeyButton.setOnClickListener(v -> {
            Intent intent = new Intent(this, PublicKeyActivity.class);
            startActivity(intent);
        });
    }
}
