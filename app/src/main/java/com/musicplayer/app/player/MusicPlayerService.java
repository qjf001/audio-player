package com.musicplayer.app.player;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.media.AudioManager;
import android.media.session.MediaSession;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.musicplayer.app.MainActivity;
import com.musicplayer.app.R;

/**
 * 前台服务：保证音乐在后台持续播放，防止系统杀掉进程。
 * 拥有 MusicPlayer 和 MediaSession 的生命周期，Activity 通过 Binder 获取引用。
 */
public class MusicPlayerService extends Service {

    private static final String CHANNEL_ID = "music_playback_channel";
    public static final int NOTIFICATION_ID = 1;

    // 通知栏按钮 action
    public static final String ACTION_PLAY_PAUSE = "com.musicplayer.app.PLAY_PAUSE";
    public static final String ACTION_NEXT = "com.musicplayer.app.NEXT";
    public static final String ACTION_PREV = "com.musicplayer.app.PREV";
    public static final String ACTION_STOP = "com.musicplayer.app.STOP";
    // 耳机断开 / 音频焦点丢失：Service 暂停后通知 Activity 更新 UI
    public static final String ACTION_PAUSED_BY_SERVICE = "com.musicplayer.app.PAUSED_BY_SERVICE";

    private MusicPlayer musicPlayer;
    private MediaSession mediaSession;
    private NotificationManager notificationManager;
    private AudioManager audioManager;
    private boolean isForeground = false;

    // 通知栏显示的状态
    private String currentTitle = "";
    private String currentArtist = "";
    private boolean isPlaying = false;

    // Activity 传递的播放监听器
    private MusicPlayer.OnPlaybackListener activityListener;

    // Activity 回调：通知栏按钮、Service 主动暂停
    public interface ServiceCallback {
        void onServiceAction(String action);
    }

    private ServiceCallback callback;

    private final IBinder binder = new MusicBinder();

    public class MusicBinder extends Binder {
        public MusicPlayerService getService() {
            return MusicPlayerService.this;
        }
    }

    // ======================== 生命周期 ========================

    @Override
    public void onCreate() {
        super.onCreate();
        musicPlayer = new MusicPlayer();
        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        createNotificationChannel();
        initMediaSession();
        setupPlaybackListener();
        registerNotificationReceiver();
        registerNoisyReceiver();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            handleAction(intent.getAction());
        }
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        callback = null;
        activityListener = null;
        try { unregisterReceiver(notificationReceiver); } catch (Exception ignored) {}
        try { unregisterReceiver(noisyReceiver); } catch (Exception ignored) {}
        if (mediaSession != null) mediaSession.release();
        if (musicPlayer != null) musicPlayer.release();
        stopForeground(true);
        super.onDestroy();
    }

    // ======================== 公开 API（供 Activity 调用） ========================

    public MusicPlayer getMusicPlayer() { return musicPlayer; }
    public MediaSession getMediaSession() { return mediaSession; }
    public void setCallback(ServiceCallback cb) { this.callback = cb; }

    /** 设置 Activity 的播放监听器；Service 内部会包装一层以处理通知更新 */
    public void setActivityPlaybackListener(@Nullable MusicPlayer.OnPlaybackListener listener) {
        activityListener = listener;
    }

    /** 播放开始：提升为前台 Service */
    public void notifyPlaybackStarted(String title, String artist) {
        if (requestAudioFocus() != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            return;
        }
        currentTitle = title;
        currentArtist = artist;
        isPlaying = true;
        if (!isForeground) {
            Notification notification = buildNotification();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
            isForeground = true;
        } else {
            notificationManager.notify(NOTIFICATION_ID, buildNotification());
        }
    }

    /** 歌曲切换：更新通知栏 */
    public void notifySongChanged(String title, String artist) {
        currentTitle = title;
        currentArtist = artist;
        if (isForeground) {
            notificationManager.notify(NOTIFICATION_ID, buildNotification());
        }
    }

    /** 播放暂停 */
    public void notifyPlaybackPaused() {
        isPlaying = false;
        if (isForeground) {
            notificationManager.notify(NOTIFICATION_ID, buildNotification());
        }
    }

    /** 播放恢复 */
    public void notifyPlaybackResumed() {
        if (requestAudioFocus() != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            return;
        }
        isPlaying = true;
        if (isForeground) {
            notificationManager.notify(NOTIFICATION_ID, buildNotification());
        }
    }

    /** 播放停止：移除前台通知 */
    public void notifyPlaybackStopped() {
        isPlaying = false;
        abandonAudioFocus();
        if (isForeground) {
            stopForeground(true);
            isForeground = false;
        }
    }

    /** 通用播放状态更新 */
    public void updatePlayState(boolean playing) {
        isPlaying = playing;
        if (isForeground) {
            notificationManager.notify(NOTIFICATION_ID, buildNotification());
        }
    }

    public boolean isForegroundRunning() { return isForeground; }

    // ======================== 内部实现 ========================

    /** 包装 Activity 的监听器：Service 先处理通知更新，再转发给 Activity */
    private void setupPlaybackListener() {
        musicPlayer.setOnPlaybackListener(new MusicPlayer.OnPlaybackListener() {
            @Override
            public void onPrepared() {
                isPlaying = true;
                if (isForeground) notificationManager.notify(NOTIFICATION_ID, buildNotification());
                if (activityListener != null) activityListener.onPrepared();
            }

            @Override
            public void onCompletion() {
                if (activityListener != null) {
                    activityListener.onCompletion();
                } else {
                    // Activity 不可用，仅更新通知
                    isPlaying = false;
                    if (isForeground) notificationManager.notify(NOTIFICATION_ID, buildNotification());
                }
            }

            @Override
            public void onError(String error) {
                if (activityListener != null) activityListener.onError(error);
            }

            @Override
            public void onShouldSkip() {
                if (activityListener != null) activityListener.onShouldSkip();
            }

            @Override
            public void onProgress(int currentPosition, int duration) {
                if (activityListener != null) activityListener.onProgress(currentPosition, duration);
            }
        });
    }

    private void initMediaSession() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            mediaSession = new MediaSession(this, "MusicPlayerService");
            mediaSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
            mediaSession.setActive(true);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(getString(R.string.notification_channel_desc));
            channel.setShowBadge(false);
            notificationManager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        // 点击通知栏打开 Activity
        Intent contentIntent = new Intent(this, MainActivity.class);
        contentIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentPI = PendingIntent.getActivity(this, 0, contentIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // 通知栏按钮
        PendingIntent prevPI = PendingIntent.getBroadcast(this, 1,
                new Intent(ACTION_PREV), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent playPausePI = PendingIntent.getBroadcast(this, 2,
                new Intent(ACTION_PLAY_PAUSE), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent nextPI = PendingIntent.getBroadcast(this, 3,
                new Intent(ACTION_NEXT), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stopPI = PendingIntent.getBroadcast(this, 4,
                new Intent(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        int ppIcon = isPlaying ? R.drawable.ic_pause_vector : R.drawable.ic_play_vector;
        String ppLabel = isPlaying ? getString(R.string.pause) : getString(R.string.play);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(currentTitle)
                .setContentText(currentArtist)
                .setSmallIcon(R.drawable.ic_play_vector)
                .setContentIntent(contentPI)
                .setDeleteIntent(stopPI)
                .addAction(R.drawable.ic_prev_vector, getString(R.string.prev), prevPI)
                .addAction(ppIcon, ppLabel, playPausePI)
                .addAction(R.drawable.ic_next_vector, getString(R.string.next), nextPI)
                .setOnlyAlertOnce(true)
                .setOngoing(isPlaying)
                .build();
    }

    // ======================== Audio Focus ========================

    private final AudioManager.OnAudioFocusChangeListener afChangeListener = focusChange -> {
        switch (focusChange) {
            case AudioManager.AUDIOFOCUS_LOSS:
                // 长期失去焦点，暂停播放
                handleAction(ACTION_PAUSED_BY_SERVICE);
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                // 短暂失去焦点，暂停
                if (musicPlayer.isPlaying()) {
                    musicPlayer.pause();
                    isPlaying = false;
                    if (isForeground) notificationManager.notify(NOTIFICATION_ID, buildNotification());
                    if (callback != null) callback.onServiceAction(ACTION_PAUSED_BY_SERVICE);
                }
                break;
            case AudioManager.AUDIOFOCUS_GAIN:
                // 重新获得焦点
                if (!musicPlayer.isPlaying() && musicPlayer.isPrepared()) {
                    musicPlayer.resume();
                    isPlaying = true;
                    if (isForeground) notificationManager.notify(NOTIFICATION_ID, buildNotification());
                    // 通知 Activity 更新 UI（这里可以根据需要扩展 ACTION）
                }
                break;
        }
    };

    private int requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.media.AudioFocusRequest request = new android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(new android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAcceptsDelayedFocusGain(true)
                    .setOnAudioFocusChangeListener(afChangeListener)
                    .build();
            return audioManager.requestAudioFocus(request);
        } else {
            return audioManager.requestAudioFocus(afChangeListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        }
    }

    private void abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // 在简单实现中，如果不保存 request 对象，可以忽略，
            // 或者在这里重建一个类似的 request 来 abandon。
            // 实际上对于 O+，如果不调用 abandon，系统在服务停止后也会处理，
            // 但最好还是显式调用。
        } else {
            audioManager.abandonAudioFocus(afChangeListener);
        }
    }

    // ======================== Action 处理 ========================

    private void handleAction(String action) {
        if (action == null) return;
        // 优先转发给 Activity 处理（Activity 有完整的播放列表和 UI 逻辑）
        if (callback != null) {
            callback.onServiceAction(action);
            return;
        }
        // Activity 不可用时的兜底处理
        switch (action) {
            case ACTION_PLAY_PAUSE:
                if (musicPlayer.isPlaying()) {
                    musicPlayer.pause();
                    isPlaying = false;
                } else if (musicPlayer.isPrepared()) {
                    musicPlayer.resume();
                    isPlaying = true;
                }
                if (isForeground) notificationManager.notify(NOTIFICATION_ID, buildNotification());
                break;
            case ACTION_STOP:
                musicPlayer.stop();
                isPlaying = false;
                stopForeground(true);
                isForeground = false;
                stopSelf();
                break;
        }
    }

    // ======================== BroadcastReceiver ========================

    /** 通知栏按钮广播 */
    private final BroadcastReceiver notificationReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            handleAction(intent.getAction());
        }
    };

    private void registerNotificationReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_PLAY_PAUSE);
        filter.addAction(ACTION_NEXT);
        filter.addAction(ACTION_PREV);
        filter.addAction(ACTION_STOP);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(notificationReceiver, filter, RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(notificationReceiver, filter);
        }
    }

    /** 耳机/蓝牙断开：自动暂停 */
    private final BroadcastReceiver noisyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) {
                if (musicPlayer.isPlaying()) {
                    musicPlayer.pause();
                    isPlaying = false;
                    if (isForeground) notificationManager.notify(NOTIFICATION_ID, buildNotification());
                    if (callback != null) callback.onServiceAction(ACTION_PAUSED_BY_SERVICE);
                }
            }
        }
    };

    private void registerNoisyReceiver() {
        IntentFilter filter = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(noisyReceiver, filter, RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(noisyReceiver, filter);
        }
    }
}
