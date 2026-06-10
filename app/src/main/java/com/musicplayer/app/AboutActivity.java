package com.musicplayer.app;

import android.content.Context;
import android.os.Bundle;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

public class AboutActivity extends AppCompatActivity {

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.applyLocale(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);

        Toolbar toolbar = findViewById(R.id.toolbar_about);
        toolbar.setNavigationOnClickListener(v -> finish());

        TextView textVersion = findViewById(R.id.text_version);
        try {
            String version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            textVersion.setText("v" + version);
        } catch (Exception e) {
            textVersion.setText("v1.0");
        }
    }
}
