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
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.musicplayer.app.MainActivity;
import com.musicplayer.app.R;

import java.io.File;

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
    
    // 播放状态追踪：区分用户主动暂停和系统暂停(电话/音频焦点)
    // true=用户主动暂停(不应该自动恢复), false=系统暂停(可以恢复)
    private boolean isPausedByUser = false;
    // 记录失去焦点前是否在播放
    private boolean wasPlayingBeforeFocusLoss = false;
    
    // 保存最后播放的歌曲路径，用于app重启后恢复
    private String lastPlayedPath = "";
    private String lastPlayedTitle = "";
    private String lastPlayedArtist = "";
    private int lastPlayedPosition = 0;

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
        
        // 加载保存的播放状态
        loadPlaybackState();
        
        createNotificationChannel();
        initMediaSession();
        setupPlaybackListener();
        registerNotificationReceiver();
        registerNoisyReceiver();

        // 关键修复：如果有保存的播放记录，激活 MediaSession 并设置初始状态为 PAUSED
        // 这样蓝牙耳机才能识别到播放控制器，并允许点击“播放”按钮
        if (!lastPlayedPath.isEmpty() && mediaSession != null) {
            mediaSession.setActive(true);
            updatePlaybackState();
            android.util.Log.d("MusicPlayerService", "MediaSession activated on create with lastPath=" + lastPlayedPath);
        }
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
        isPausedByUser = false;
        
        // 激活 MediaSession，更新状态和元数据
        if (mediaSession != null) {
            mediaSession.setActive(true);
            updatePlaybackState();
            updateMediaMetadata(title, artist, musicPlayer.getDuration());
        }
        
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
    
    /** 保存播放状态到SharedPreferences */
    private void savePlaybackState() {
        if (lastPlayedPath == null || lastPlayedPath.isEmpty()) return;
        android.content.SharedPreferences prefs = getSharedPreferences("service_playback_prefs", MODE_PRIVATE);
        prefs.edit()
            .putString("last_played_path", lastPlayedPath)
            .putString("last_played_title", lastPlayedTitle)
            .putString("last_played_artist", lastPlayedArtist)
            .putInt("last_played_position", lastPlayedPosition)
            .apply();
    }
    
    /** 更新最后播放的歌曲信息（由 Activity 调用） */
    public void updateLastPlayedPath(String path, String title, String artist, int position) {
        if (path == null || path.isEmpty()) return;
        lastPlayedPath = path;
        lastPlayedTitle = title;
        lastPlayedArtist = artist;
        lastPlayedPosition = position;
        
        // 持久化到 SharedPreferences
        android.content.SharedPreferences prefs = getSharedPreferences("service_playback_prefs", MODE_PRIVATE);
        prefs.edit()
            .putString("last_played_path", path)
            .putString("last_played_title", title)
            .putString("last_played_artist", artist)
            .putInt("last_played_position", position)
            .apply();
    }
    
    /** 加载保存的播放状态 */
    private void loadPlaybackState() {
        android.content.SharedPreferences prefs = getSharedPreferences("service_playback_prefs", MODE_PRIVATE);
        lastPlayedPath = prefs.getString("last_played_path", "");
        lastPlayedTitle = prefs.getString("last_played_title", "");
        lastPlayedArtist = prefs.getString("last_played_artist", "");
        lastPlayedPosition = prefs.getInt("last_played_position", 0);
        
        if (!lastPlayedPath.isEmpty()) {
            currentTitle = lastPlayedTitle;
            currentArtist = lastPlayedArtist;
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
        updatePlaybackState();
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
        updatePlaybackState();
        if (isForeground) {
            notificationManager.notify(NOTIFICATION_ID, buildNotification());
        }
    }

    /** 播放停止：移除前台通知 */
    public void notifyPlaybackStopped() {
        isPlaying = false;
        isPausedByUser = true;
        updatePlaybackState();
        abandonAudioFocus();
        
        // 停用 MediaSession
        if (mediaSession != null) {
            mediaSession.setActive(false);
        }
        
        if (isForeground) {
            stopForeground(true);
            isForeground = false;
        }
    }

    /** 通用播放状态更新 */
    public void updatePlayState(boolean playing) {
        isPlaying = playing;
        updatePlaybackState();
        if (isForeground) {
            notificationManager.notify(NOTIFICATION_ID, buildNotification());
        }
    }

    public boolean isForegroundRunning() { return isForeground; }
    
    /** 通知 Service 用户主动暂停（用于区分系统暂停） */
    public void notifyUserPaused() {
        isPausedByUser = true;
    }
    
    /** 通知 Service 用户主动恢复播放 */
    public void notifyUserResumed() {
        isPausedByUser = false;
    }

    // ======================== 内部实现 ========================

    /** 包装 Activity 的监听器：Service 先处理通知更新，再转发给 Activity */
    private void setupPlaybackListener() {
        musicPlayer.setOnPlaybackListener(new MusicPlayer.OnPlaybackListener() {
            @Override
            public void onPrepared() {
                isPlaying = true;
                updatePlaybackState();
                updateMediaMetadata(currentTitle, currentArtist, musicPlayer.getDuration());
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
            
            // 关键修复：在Service中设置MediaSession Callback，完全独立处理蓝牙和通知按键
            mediaSession.setCallback(new MediaSession.Callback() {
                @Override
                public void onPlay() {
                    android.util.Log.d("MediaSession", "onPlay - isPrepared=" + musicPlayer.isPrepared() + ", isPlaying=" + musicPlayer.isPlaying());
                    
                    if (musicPlayer.isPlaying()) return;
                    
                    if (musicPlayer.isPrepared()) {
                        musicPlayer.resume();
                        isPlaying = true;
                        isPausedByUser = false;
                        updatePlaybackState();
                        if (isForeground) notificationManager.notify(NOTIFICATION_ID, buildNotification());
                        if (callback != null) callback.onServiceAction(ACTION_PLAY_PAUSE);
                    } else if (!lastPlayedPath.isEmpty()) {
                        // app重启后的场景：使用保存的路径直接开始播放
                        try {
                            File file = new File(lastPlayedPath);
                            if (file.exists()) {
                                currentTitle = lastPlayedTitle;
                                currentArtist = lastPlayedArtist;
                                musicPlayer.play(getApplicationContext(), lastPlayedPath);
                                isPlaying = true;
                                isPausedByUser = false;
                                updatePlaybackState();
                                updateMediaMetadata(currentTitle, currentArtist, 0);
                                if (isForeground) notificationManager.notify(NOTIFICATION_ID, buildNotification());
                                // 通知 Activity 同步 UI
                                if (callback != null) callback.onServiceAction(ACTION_PLAY_PAUSE);
                            }
                        } catch (Exception e) {
                            android.util.Log.e("MediaSession", "Error resuming saved song", e);
                        }
                    }
                }

                @Override
                public void onPause() {
                    android.util.Log.d("MediaSession", "onPause");
                    if (musicPlayer.isPlaying()) {
                        musicPlayer.pause();
                        isPlaying = false;
                        isPausedByUser = true;
                        updatePlaybackState();
                        if (isForeground) notificationManager.notify(NOTIFICATION_ID, buildNotification());
                        if (callback != null) callback.onServiceAction(ACTION_PLAY_PAUSE);
                    }
                }

                @Override
                public void onSkipToNext() {
                    if (callback != null) callback.onServiceAction(ACTION_NEXT);
                }

                @Override
                public void onSkipToPrevious() {
                    if (callback != null) callback.onServiceAction(ACTION_PREV);
                }

                @Override
                public void onStop() {
                    musicPlayer.stop();
                    isPlaying = false;
                    isPausedByUser = true;
                    updatePlaybackState();
                    if (isForeground) {
                        stopForeground(true);
                        isForeground = false;
                    }
                    if (callback != null) callback.onServiceAction(ACTION_STOP);
                }

                @Override
                public void onSeekTo(long pos) {
                    musicPlayer.seekTo((int) pos);
                    updatePlaybackState();
                }
            });
            
            mediaSession.setActive(false);
            updatePlaybackState();
        }
    }

    public void updatePlaybackState() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && mediaSession != null) {
            int state = isPlaying ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED;
            long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | 
                           PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT | 
                           PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_STOP |
                           PlaybackState.ACTION_SEEK_TO;
            
            mediaSession.setPlaybackState(new PlaybackState.Builder()
                    .setState(state, musicPlayer != null ? musicPlayer.getCurrentPosition() : 0, 1.0f)
                    .setActions(actions)
                    .build());
        }
    }

    public void updateMediaMetadata(String title, String artist, long duration) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && mediaSession != null) {
            mediaSession.setMetadata(new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, duration)
                    .build());
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
                wasPlayingBeforeFocusLoss = false;
                handleAction(ACTION_PAUSED_BY_SERVICE);
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                // 短暂失去焦点(电话/语音等)
                if (musicPlayer.isPlaying()) {
                    wasPlayingBeforeFocusLoss = true;
                    musicPlayer.pause();
                    isPlaying = false;
                    updatePlaybackState();
                    if (isForeground) notificationManager.notify(NOTIFICATION_ID, buildNotification());
                    if (callback != null) callback.onServiceAction(ACTION_PAUSED_BY_SERVICE);
                } else {
                    wasPlayingBeforeFocusLoss = false;
                }
                break;
            case AudioManager.AUDIOFOCUS_GAIN:
                // 重新获得焦点
                // 关键：根据用户反馈，停止接听后不应该自动播放，即使之前在播放
                // 如果需要自动恢复，则判断 wasPlayingBeforeFocusLoss
                // 这里我们选择不自动恢复播放，由用户手动点击或蓝牙按键控制
                wasPlayingBeforeFocusLoss = false;
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
        // 但如果 Activity callback 为 null（app重启后首次），Service 自己处理
        if (callback != null) {
            callback.onServiceAction(action);
            return;
        }
        
        // Activity 不可用时的兜底处理
        android.util.Log.d("MusicPlayerService", "handleAction (no callback): " + action);
        switch (action) {
            case ACTION_PLAY_PAUSE:
                if (musicPlayer.isPlaying()) {
                    musicPlayer.pause();
                    isPlaying = false;
                    isPausedByUser = true;
                } else if (musicPlayer.isPrepared()) {
                    musicPlayer.resume();
                    isPlaying = true;
                    isPausedByUser = false;
                } else if (!lastPlayedPath.isEmpty()) {
                    // app重启后的场景：使用保存的路径重新播放
                    try {
                        File file = new File(lastPlayedPath);
                        if (file.exists()) {
                            musicPlayer.play(getApplicationContext(), lastPlayedPath);
                            isPlaying = true;
                            isPausedByUser = false;
                            android.util.Log.d("MusicPlayerService", "handleAction: loaded saved song");
                        }
                    } catch (Exception e) {
                        android.util.Log.e("MusicPlayerService", "Error loading saved song", e);
                    }
                }
                if (isForeground) notificationManager.notify(NOTIFICATION_ID, buildNotification());
                break;
            case ACTION_STOP:
                musicPlayer.stop();
                isPlaying = false;
                isPausedByUser = true;
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
                    wasPlayingBeforeFocusLoss = true;
                    isPausedByUser = false; // 系统暂停(蓝牙断开)，不是用户主动暂停
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
