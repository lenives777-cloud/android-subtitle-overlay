package com.example.subtitles;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.example.subtitles.gpt.GptLiveClient;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class LiveTranslatorService extends Service {

    private static final String CHANNEL_ID = "SUBRIMA_TRANSLATOR";
    private static final int NOTIFICATION_ID = 9001;

    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNEL_CONFIG =
            AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_ENCODING =
            AudioFormat.ENCODING_PCM_16BIT;

    /*
     * Пока API-ключ не храним непосредственно в коде.
     *
     * Следующим шагом подключим безопасное получение ключа
     * через настройки приложения.
     */
    private static final String OPENAI_API_KEY = "";

    private MediaProjection mediaProjection;
    private AudioRecord audioRecord;

    private volatile boolean running = false;

    /*
     * ВАЖНО:
     *
     * Захват аудио и обработка аудио работают
     * в разных потоках.
     */
    private ExecutorService captureExecutor;
    private ExecutorService processingExecutor;

    /*
     * Не допускаем одновременную обработку нескольких
     * аудиофрагментов.
     */
    private final AtomicBoolean processing = new AtomicBoolean(false);

    private GptLiveClient gptClient;

    private WindowManager windowManager;
    private TextView overlayText;

    @Override
    public void onCreate() {
        super.onCreate();

        createNotificationChannel();

        captureExecutor =
                Executors.newSingleThreadExecutor();

        processingExecutor =
                Executors.newSingleThreadExecutor();

        if (!OPENAI_API_KEY.trim().isEmpty()) {
            gptClient =
                    new GptLiveClient(OPENAI_API_KEY);
        }

        startForeground(
                NOTIFICATION_ID,
                createNotification()
        );
    }

    @Override
    public int onStartCommand(
            Intent intent,
            int flags,
            int startId) {

        if (intent == null) {
            return START_NOT_STICKY;
        }

        int resultCode =
                intent.getIntExtra(
                        "result_code",
                        0
                );

        Intent projectionData;

        if (Build.VERSION.SDK_INT >= 33) {

            projectionData =
                    intent.getParcelableExtra(
                            "projection_data",
                            Intent.class
                    );

        } else {

            projectionData =
                    intent.getParcelableExtra(
                            "projection_data"
                    );
        }

        if (projectionData == null) {
            showOverlay("SUBRIMA\nОшибка MediaProjection");
            stopSelf();
            return START_NOT_STICKY;
        }

        startCapture(
                resultCode,
                projectionData
        );

        return START_STICKY;
    }

    private void startCapture(
            int resultCode,
            Intent projectionData) {

        if (running) {
            return;
        }

        try {

            MediaProjectionManager manager =
                    (MediaProjectionManager)
                            getSystemService(
                                    Context.MEDIA_PROJECTION_SERVICE
                            );

            if (manager == null) {
                showOverlay("SUBRIMA\nMediaProjection ERROR");
                stopSelf();
                return;
            }

            mediaProjection =
                    manager.getMediaProjection(
                            resultCode,
                            projectionData
                    );

            if (mediaProjection == null) {
                showOverlay("SUBRIMA\nProjection ERROR");
                stopSelf();
                return;
            }

            if (Build.VERSION.SDK_INT <
                    Build.VERSION_CODES.Q) {

                showOverlay(
                        "SUBRIMA\nAndroid 10+ required"
                );

                stopSelf();
                return;
            }

            AudioPlaybackCaptureConfiguration config =
                    new AudioPlaybackCaptureConfiguration.Builder(
                            mediaProjection
                    )
                            .addMatchingUsage(
                                    AudioAttributes.USAGE_MEDIA
                            )
                            .addMatchingUsage(
                                    AudioAttributes.USAGE_GAME
                            )
                            .build();

            int minBuffer =
                    AudioRecord.getMinBufferSize(
                            SAMPLE_RATE,
                            CHANNEL_CONFIG,
                            AUDIO_ENCODING
                    );

            if (minBuffer <= 0) {
                minBuffer = SAMPLE_RATE;
            }

            int bufferSize =
                    Math.max(
                            minBuffer * 2,
                            SAMPLE_RATE * 2
                    );

            AudioFormat format =
                    new AudioFormat.Builder()
                            .setEncoding(
                                    AUDIO_ENCODING
                            )
                            .setSampleRate(
                                    SAMPLE_RATE
                            )
                            .setChannelMask(
                                    CHANNEL_CONFIG
                            )
                            .build();

            audioRecord =
                    new AudioRecord.Builder()
                            .setAudioFormat(format)
                            .setBufferSizeInBytes(
                                    bufferSize
                            )
                            .setAudioPlaybackCaptureConfig(
                                    config
                            )
                            .build();

            if (audioRecord.getState() !=
                    AudioRecord.STATE_INITIALIZED) {

                showOverlay(
                        "SUBRIMA\nAudioRecord ERROR"
                );

                stopSelf();
                return;
            }

            running = true;

            showOverlay(
                    "SUBRIMA\n● LIVE\nЗахват: ON"
            );

            captureExecutor.execute(
                    this::captureLoop
            );

        } catch (Exception e) {

            e.printStackTrace();

            showOverlay(
                    "SUBRIMA\nОшибка запуска"
            );

            stopSelf();
        }
    }

    private void captureLoop() {

        try {

            audioRecord.startRecording();

            byte[] buffer =
                    new byte[SAMPLE_RATE * 2];

            ByteArrayOutputStream chunk =
                    new ByteArrayOutputStream();

            long chunkStart =
                    System.currentTimeMillis();

            while (running) {

                int read =
                        audioRecord.read(
                                buffer,
                                0,
                                buffer.length
                        );

                if (read <= 0) {
                    continue;
                }

                chunk.write(
                        buffer,
                        0,
                        read
                );

                long elapsed =
                        System.currentTimeMillis()
                                - chunkStart;

                /*
                 * Примерно 2.2 секунды аудио.
                 */
                if (elapsed >= 2200) {

                    byte[] pcm =
                            chunk.toByteArray();

                    chunk.reset();

                    chunkStart =
                            System.currentTimeMillis();

                    File wavFile =
                            createWavFile(pcm);

                    if (wavFile != null) {
                        processAudio(wavFile);
                    }
                }
            }

        } catch (Exception e) {

            e.printStackTrace();

        } finally {

            try {

                if (audioRecord != null) {
                    audioRecord.stop();
                }

            } catch (Exception ignored) {
            }
        }
    }

    private File createWavFile(byte[] pcm) {

        try {

            File file =
                    new File(
                            getCacheDir(),
                            "subrima_" +
                                    System.currentTimeMillis() +
                                    ".wav"
                    );

            FileOutputStream out =
                    new FileOutputStream(file);

            int byteRate =
                    SAMPLE_RATE * 2;

            int dataLength =
                    pcm.length;

            int totalLength =
                    dataLength + 36;

            out.write(new byte[]{
                    'R', 'I', 'F', 'F'
            });

            writeInt(
                    out,
                    totalLength
            );

            out.write(new byte[]{
                    'W', 'A', 'V', 'E'
            });

            out.write(new byte[]{
                    'f', 'm', 't', ' '
            });

            writeInt(out, 16);

            writeShort(
                    out,
                    (short) 1
            );

            writeShort(
                    out,
                    (short) 1
            );

            writeInt(
                    out,
                    SAMPLE_RATE
            );

            writeInt(
                    out,
                    byteRate
            );

            writeShort(
                    out,
                    (short) 2
            );

            writeShort(
                    out,
                    (short) 16
            );

            out.write(new byte[]{
                    'd', 'a', 't', 'a'
            });

            writeInt(
                    out,
                    dataLength
            );

            out.write(pcm);

            out.flush();
            out.close();

            return file;

        } catch (Exception e) {

            e.printStackTrace();

            return null;
        }
    }

    private void writeInt(
            FileOutputStream out,
            int value) throws Exception {

        out.write(value & 0xff);
        out.write((value >> 8) & 0xff);
        out.write((value >> 16) & 0xff);
        out.write((value >> 24) & 0xff);
    }

    private void writeShort(
            FileOutputStream out,
            short value) throws Exception {

        out.write(value & 0xff);
        out.write((value >> 8) & 0xff);
    }

    private void processAudio(
            File wavFile) {

        /*
         * Если предыдущий фрагмент ещё обрабатывается,
         * этот фрагмент пропускаем.
         *
         * Это предотвращает накопление огромной очереди
         * при медленном интернете/API.
         */
        if (!processing.compareAndSet(
                false,
                true)) {

            deleteFile(wavFile);

            return;
        }

        processingExecutor.execute(() -> {

            try {

                if (gptClient == null) {

                    updateOverlay(
                            "SUBRIMA\n● LIVE\n" +
                                    "Аудио: ON\n" +
                                    "OpenAI: API KEY"
                    );

                    return;
                }

                /*
                 * 1. Speech → Text
                 */
                String transcript =
                        gptClient.transcribe(
                                wavFile
                        );

                if (transcript == null ||
                        transcript.trim().isEmpty()) {

                    return;
                }

                updateOverlay(
                        "SUBRIMA\n" +
                                "Речь:\n" +
                                transcript
                );

                /*
                 * 2. Text → Russian
                 */
                String translation =
                        gptClient.translate(
                                transcript,
                                "auto",
                                "Russian"
                        );

                if (translation == null ||
                        translation.trim().isEmpty()) {

                    return;
                }

                /*
                 * 3. Показываем перевод
                 */
                updateOverlay(
                        "SUBRIMA\n● LIVE\n\n" +
                                translation
                );

                /*
                 * TTS подключим следующим этапом.
                 */

            } catch (Exception e) {

                e.printStackTrace();

                String message =
                        e.getMessage();

                if (message == null ||
                        message.trim().isEmpty()) {

                    message =
                            "Ошибка обработки";
                }

                if (message.length() > 120) {
                    message =
                            message.substring(
                                    0,
                                    120
                            );
                }

                updateOverlay(
                        "SUBRIMA\n" +
                                "API ERROR\n" +
                                message
                );

            } finally {

                deleteFile(wavFile);

                processing.set(false);
            }
        });
    }

    private void deleteFile(File file) {

        try {

            if (file != null &&
                    file.exists()) {

                file.delete();
            }

        } catch (Exception ignored) {
        }
    }

    private void updateOverlay(
            String text) {

        android.os.Handler handler =
                new android.os.Handler(
                        android.os.Looper.getMainLooper()
                );

        handler.post(() -> {

            if (overlayText != null) {
                overlayText.setText(text);
            }
        });
    }

    private void showOverlay(
            String text) {

        if (Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.M) {

            if (!Settings.canDrawOverlays(this)) {
                return;
            }
        }

        android.os.Handler handler =
                new android.os.Handler(
                        android.os.Looper.getMainLooper()
                );

        handler.post(() -> {

            try {

                if (windowManager != null &&
                        overlayText != null) {

                    overlayText.setText(text);

                    return;
                }

                windowManager =
                        (WindowManager)
                                getSystemService(
                                        WINDOW_SERVICE
                                );

                if (windowManager == null) {
                    return;
                }

                overlayText =
                        new TextView(this);

                overlayText.setText(text);

                overlayText.setTextColor(
                        android.graphics.Color.WHITE
                );

                overlayText.setTextSize(16);

                overlayText.setPadding(
                        20,
                        12,
                        20,
                        12
                );

                overlayText.setBackgroundColor(
                        0xCC000000
                );

                int type;

                if (Build.VERSION.SDK_INT >= 26) {

                    type =
                            WindowManager.LayoutParams
                                    .TYPE_APPLICATION_OVERLAY;

                } else {

                    type =
                            WindowManager.LayoutParams
                                    .TYPE_PHONE;
                }

                WindowManager.LayoutParams params =
                        new WindowManager.LayoutParams(
                                WindowManager.LayoutParams
                                        .WRAP_CONTENT,
                                WindowManager.LayoutParams
                                        .WRAP_CONTENT,
                                type,
                                WindowManager.LayoutParams
                                        .FLAG_NOT_FOCUSABLE |
                                        WindowManager.LayoutParams
                                                .FLAG_NOT_TOUCHABLE,
                                PixelFormat.TRANSLUCENT
                        );

                params.gravity =
                        Gravity.TOP |
                                Gravity.CENTER_HORIZONTAL;

                params.y = 120;

                windowManager.addView(
                        overlayText,
                        params
                );

            } catch (Exception e) {

                e.printStackTrace();
            }
        });
    }

    private void removeOverlay() {

        android.os.Handler handler =
                new android.os.Handler(
                        android.os.Looper.getMainLooper()
                );

        handler.post(() -> {

            try {

                if (windowManager != null &&
                        overlayText != null) {

                    windowManager.removeView(
                            overlayText
                    );

                    overlayText = null;
                }

            } catch (Exception ignored) {
            }
        });
    }

    private Notification createNotification() {

        return new NotificationCompat.Builder(
                this,
                CHANNEL_ID
        )
                .setContentTitle(
                        "SUBRIMA LIVE TRANSLATOR"
                )
                .setContentText(
                        "Захват и перевод аудио"
                )
                .setSmallIcon(
                        android.R.drawable
                                .ic_media_play
                )
                .setOngoing(true)
                .build();
    }

    private void createNotificationChannel() {

        if (Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.O) {

            NotificationChannel channel =
                    new NotificationChannel(
                            CHANNEL_ID,
                            "SUBRIMA Translator",
                            NotificationManager
                                    .IMPORTANCE_LOW
                    );

            NotificationManager manager =
                    getSystemService(
                            NotificationManager.class
                    );

            if (manager != null) {

                manager.createNotificationChannel(
                        channel
                );
            }
        }
    }

    @Override
    public void onDestroy() {

        running = false;

        try {

            if (audioRecord != null) {

                audioRecord.stop();
                audioRecord.release();

                audioRecord = null;
            }

        } catch (Exception ignored) {
        }

        try {

            if (mediaProjection != null) {

                mediaProjection.stop();
                mediaProjection = null;
            }

        } catch (Exception ignored) {
        }

        removeOverlay();

        if (captureExecutor != null) {
            captureExecutor.shutdownNow();
        }

        if (processingExecutor != null) {
            processingExecutor.shutdownNow();
        }

        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
