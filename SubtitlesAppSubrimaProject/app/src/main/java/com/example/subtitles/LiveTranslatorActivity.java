package com.example.subtitles;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class LiveTranslatorActivity extends AppCompatActivity {

    private static final int REQUEST_MEDIA_PROJECTION = 1001;
    private static final int REQUEST_RECORD_AUDIO = 1002;

    private TextView statusText;
    private Button startButton;
    private Button stopButton;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        buildInterface();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.RECORD_AUDIO
                ) != PackageManager.PERMISSION_GRANTED) {

            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    REQUEST_RECORD_AUDIO
            );
        }
    }

    private void buildInterface() {

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(32, 50, 32, 40);
        root.setBackgroundColor(Color.BLACK);

        TextView title = new TextView(this);
        title.setText("SUBRIMA");
        title.setTextColor(Color.WHITE);
        title.setTextSize(32);
        title.setGravity(Gravity.CENTER);
        title.setTypeface(null, android.graphics.Typeface.BOLD);

        TextView subtitle = new TextView(this);
        subtitle.setText("LIVE TRANSLATOR");
        subtitle.setTextColor(Color.RED);
        subtitle.setTextSize(18);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setTypeface(null, android.graphics.Typeface.BOLD);

        TextView language = new TextView(this);
        language.setText("\nEnglish → Русский");
        language.setTextColor(Color.WHITE);
        language.setTextSize(20);
        language.setGravity(Gravity.CENTER);

        statusText = new TextView(this);
        statusText.setText(
                "\n● Захват: OFF" +
                "\n● Перевод: OFF" +
                "\n● Наушники: OFF"
        );
        statusText.setTextColor(Color.WHITE);
        statusText.setTextSize(17);
        statusText.setGravity(Gravity.CENTER);

        startButton = new Button(this);
        startButton.setText("START");
        startButton.setTextColor(Color.WHITE);
        startButton.setBackgroundColor(Color.RED);

        stopButton = new Button(this);
        stopButton.setText("STOP");
        stopButton.setTextColor(Color.WHITE);
        stopButton.setBackgroundColor(Color.DKGRAY);
        stopButton.setEnabled(false);

        TextView textMode = new TextView(this);
        textMode.setText("\n📝 Текст: ON\n🔊 Звук: ON");
        textMode.setTextColor(Color.WHITE);
        textMode.setTextSize(17);
        textMode.setGravity(Gravity.CENTER);

        root.addView(title);
        root.addView(subtitle);
        root.addView(language);
        root.addView(statusText);

        LinearLayout.LayoutParams buttonParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                );

        buttonParams.setMargins(0, 25, 0, 10);

        root.addView(startButton, buttonParams);
        root.addView(stopButton, buttonParams);
        root.addView(textMode);

        setContentView(root);

        startButton.setOnClickListener(v -> requestCapture());

        stopButton.setOnClickListener(v -> stopTranslator());
    }

    private void requestCapture() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                !Settings.canDrawOverlays(this)) {

            Intent overlayIntent = new Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())
            );

            startActivity(overlayIntent);

            Toast.makeText(
                    this,
                    "Разреши отображение поверх других приложений",
                    Toast.LENGTH_LONG
            ).show();

            return;
        }

        MediaProjectionManager manager =
                (MediaProjectionManager)
                        getSystemService(Context.MEDIA_PROJECTION_SERVICE);

        if (manager == null) {
            Toast.makeText(
                    this,
                    "MediaProjection недоступен",
                    Toast.LENGTH_LONG
            ).show();
            return;
        }

        startActivityForResult(
                manager.createScreenCaptureIntent(),
                REQUEST_MEDIA_PROJECTION
        );
    }

    @Override
    protected void onActivityResult(
            int requestCode,
            int resultCode,
            @Nullable Intent data) {

        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_MEDIA_PROJECTION) {

            if (resultCode != Activity.RESULT_OK || data == null) {

                Toast.makeText(
                        this,
                        "Захват аудио отменён",
                        Toast.LENGTH_SHORT
                ).show();

                return;
            }

            startTranslatorService(resultCode, data);
        }
    }

    private void startTranslatorService(int resultCode, Intent data) {

        Intent serviceIntent =
                new Intent(this, LiveTranslatorService.class);

        serviceIntent.putExtra(
                "result_code",
                resultCode
        );

        serviceIntent.putExtra(
                "projection_data",
                data
        );

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            ContextCompat.startForegroundService(
                    this,
                    serviceIntent
            );

        } else {

            startService(serviceIntent);
        }

        statusText.setText(
                "\n● Захват: ON" +
                "\n● Перевод: ON" +
                "\n● Наушники: ON"
        );

        startButton.setEnabled(false);
        stopButton.setEnabled(true);

        Toast.makeText(
                this,
                "SUBRIMA запущен",
                Toast.LENGTH_SHORT
        ).show();
    }

    private void stopTranslator() {

        Intent serviceIntent =
                new Intent(this, LiveTranslatorService.class);

        stopService(serviceIntent);

        statusText.setText(
                "\n● Захват: OFF" +
                "\n● Перевод: OFF" +
                "\n● Наушники: OFF"
        );

        startButton.setEnabled(true);
        stopButton.setEnabled(false);

        Toast.makeText(
                this,
                "SUBRIMA остановлен",
                Toast.LENGTH_SHORT
        ).show();
    }
  }
