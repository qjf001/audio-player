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

/**
 * 前台服务：持有两个独立的播放器实例（首页和标签页），并管理全局播放状态和通知。
 */
public class MusicPlayerService extends Service {

    private static final String CHANNEL_ID = "music_playback_channel";
    public static final int NOTIFICATION_ID = 1;

    public static final String ACTION_PLAY_PAUSE = "com.musicplayer.app.PLAY_PAUSE";
    public static final String ACTION_NEXT = "com.musicplayer.app.NEXT";
    public static final String ACTION_PREV = "com.musicplayer.app.PREV";
    public static final String ACTION_STOP = "com.musicplayer.app.STOP";
    public static final String ACTION_PAUSED_BY_SERVICE = "com.musicplayer.app.PAUSED_BY_SERVICE";

    private MusicPlayer homePlayer;
    private MusicPlayer tagPlayer;
    private MusicPlayer lastActivePlayer;

    private MediaSession mediaSession;
    private NotificationManager notificationManager;
    private AudioManager audioManager;
    private boolean isForeground = false;

    private String currentTitle = "";
    private String currentArtist = "";
    private boolean isPlaying = false;
    
    private String lastPlayedPath = "";
    private String lastPlayedTitle = "";
    private String lastPlayedArtist = "";

    private MusicPlayer.OnPlaybackListener homeActivityListener;
    private MusicPlayer.OnPlaybackListener tagActivityListener;

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

    @Override
    public void onCreate() {
        super.onCreate();
        homePlayer = new MusicPlayer();
        tagPlayer = new MusicPlayer();
        lastActivePlayer = homePlayer;

        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        
        loadPlaybackState();
        createNotificationChannel();
        initMediaSession();
        setupPlaybackListeners();
        registerNotificationReceiver();
        registerNoisyReceiver();

        if (!lastPlayedPath.isEmpty() && mediaSession != null) {
            mediaSession.setActive(true);
            updatePlaybackState();
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
        homeActivityListener = null;
        tagActivityListener = null;
        try { unregisterReceiver(notificationReceiver); } catch (Exception ignored) {}
        try { unregisterReceiver(noisyReceiver); } catch (Exception ignored) {}
        if (mediaSession != null) mediaSession.release();
        if (homePlayer != null) homePlayer.release();
        if (tagPlayer != null) tagPlayer.release();
        stopForeground(true);
        super.onDestroy();
    }

    // ======================== Public API ========================

    public MusicPlayer getHomePlayer() { return homePlayer; }
    public MusicPlayer getTagPlayer() { return tagPlayer; }
    
    public MusicPlayer getActivePlayer() {
        if (homePlayer.isPlaying()) return homePlayer;
        if (tagPlayer.isPlaying()) return tagPlayer;
        return lastActivePlayer;
    }

    public MediaSession getMediaSession() { return mediaSession; }
    public void setCallback(ServiceCallback cb) { this.callback = cb; }

    public void setHomePlaybackListener(@Nullable MusicPlayer.OnPlaybackListener listener) {
        this.homeActivityListener = listener;
    }

    public void setTagPlaybackListener(@Nullable MusicPlayer.OnPlaybackListener listener) {
        this.tagActivityListener = listener;
    }

    public void notifyPlaybackStarted(MusicPlayer player, String title, String artist) {
        if (requestAudioFocus() != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return;
        
        if (player == homePlayer) {
            tagPlayer.stop();
        } else if (player == tagPlayer) {
            homePlayer.stop();
        }
        
        lastActivePlayer = player;
        currentTitle = title;
        currentArtist = artist;
        isPlaying = true;
        
        updateLastPlayedPath(player.getCurrentPath(), title, artist, 0);
        
        if (mediaSession != null) {
            mediaSession.setActive(true);
            updatePlaybackState();
            updateMediaMetadata(title, artist, player.getDuration());
        }
        
        Notification notification = buildNotification();
        if (!isForeground) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
            isForeground = true;
        } else {
            notificationManager.notify(NOTIFICATION_ID, notification);
        }
    }

    public void notifyPlaybackPaused() {
        isPlaying = false;
        updatePlaybackState();
        if (isForeground) {
            notificationManager.notify(NOTIFICATION_ID, buildNotification());
        }
    }

    public void notifyPlaybackResumed() {
        if (requestAudioFocus() != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return;
        isPlaying = true;
        updatePlaybackState();
        if (isForeground) {
            notificationManager.notify(NOTIFICATION_ID, buildNotification());
        }
    }

    public void notifyPlaybackStopped() {
        isPlaying = false;
        updatePlaybackState();
        abandonAudioFocus();
        if (mediaSession != null) mediaSession.setActive(false);
        if (isForeground) {
            stopForeground(true);
            isForeground = false;
        }
    }

    // ======================== Internal Implementation ========================

    private void setupPlaybackListeners() {
        homePlayer.setOnPlaybackListener(createPlayerProxy(true));
        tagPlayer.setOnPlaybackListener(createPlayerProxy(false));
    }

    private MusicPlayer.OnPlaybackListener createPlayerProxy(boolean isHome) {
        return new MusicPlayer.OnPlaybackListener() {
            @Override
            public void onPrepared() {
                MusicPlayer p = isHome ? homePlayer : tagPlayer;
                if (p.isPlaying()) {
                    isPlaying = true;
                    updatePlaybackState();
                    updateMediaMetadata(currentTitle, currentArtist, p.getDuration());
                    if (isForeground) notificationManager.notify(NOTIFICATION_ID, buildNotification());
                }
                MusicPlayer.OnPlaybackListener listener = isHome ? homeActivityListener : tagActivityListener;
                if (listener != null) listener.onPrepared();
            }

            @Override
            public void onCompletion() {
                MusicPlayer.OnPlaybackListener listener = isHome ? homeActivityListener : tagActivityListener;
                if (listener != null) listener.onCompletion();
                else {
                    isPlaying = false;
                    if (isForeground) notificationManager.notify(NOTIFICATION_ID, buildNotification());
                }
            }

            @Override
            public void onError(String error) {
                MusicPlayer.OnPlaybackListener listener = isHome ? homeActivityListener : tagActivityListener;
                if (listener != null) listener.onError(error);
            }

            @Override
            public void onShouldSkip() {
                MusicPlayer.OnPlaybackListener listener = isHome ? homeActivityListener : tagActivityListener;
                if (listener != null) listener.onShouldSkip();
            }

            @Override
            public void onProgress(int c, int d) {
                MusicPlayer.OnPlaybackListener listener = isHome ? homeActivityListener : tagActivityListener;
                if (listener != null) listener.onProgress(c, d);
            }
        };
    }

    private void initMediaSession() {
        mediaSession = new MediaSession(this, "MusicPlayerService");
        mediaSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mediaSession.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { handleAction(ACTION_PLAY_PAUSE); }
            @Override public void onPause() { handleAction(ACTION_PLAY_PAUSE); }
            @Override public void onSkipToNext() { handleAction(ACTION_NEXT); }
            @Override public void onSkipToPrevious() { handleAction(ACTION_PREV); }
            @Override public void onSeekTo(long pos) { getActivePlayer().seekTo((int) pos); updatePlaybackState(); }
        });
        mediaSession.setActive(false);
        updatePlaybackState();
    }

    public void updatePlaybackState() {
        if (mediaSession == null) return;
        int state = isPlaying ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED;
        long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | 
                       PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT | 
                       PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_STOP |
                       PlaybackState.ACTION_SEEK_TO;
        mediaSession.setPlaybackState(new PlaybackState.Builder()
                .setState(state, getActivePlayer().getCurrentPosition(), 1.0f)
                .setActions(actions).build());
    }

    public void updateMediaMetadata(String title, String artist, long duration) {
        if (mediaSession == null) return;
        mediaSession.setMetadata(new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, duration).build());
    }

    private void handleAction(String action) {
        if (action == null) return;
        if (callback != null) {
            callback.onServiceAction(action);
            return;
        }
        MusicPlayer active = getActivePlayer();
        switch (action) {
            case ACTION_PLAY_PAUSE:
                if (active.isPlaying()) {
                    active.pause();
                    isPlaying = false;
                } else if (active.isPrepared()) {
                    active.resume();
                    isPlaying = true;
                } else if (!lastPlayedPath.isEmpty()) {
                    try { active.play(getApplicationContext(), lastPlayedPath); isPlaying = true; } catch (Exception ignored) {}
                }
                updatePlaybackState();
                if (isForeground) notificationManager.notify(NOTIFICATION_ID, buildNotification());
                break;
            case ACTION_STOP:
                active.stop();
                isPlaying = false;
                notifyPlaybackStopped();
                stopSelf();
                break;
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel_name), NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            notificationManager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        Intent contentIntent = new Intent(this, MainActivity.class);
        contentIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentPI = PendingIntent.getActivity(this, 0, contentIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent prevPI = PendingIntent.getBroadcast(this, 1, new Intent(ACTION_PREV), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent ppPI = PendingIntent.getBroadcast(this, 2, new Intent(ACTION_PLAY_PAUSE), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent nextPI = PendingIntent.getBroadcast(this, 3, new Intent(ACTION_NEXT), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stopPI = PendingIntent.getBroadcast(this, 4, new Intent(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(currentTitle).setContentText(currentArtist)
                .setSmallIcon(R.drawable.ic_play_vector).setContentIntent(contentPI).setDeleteIntent(stopPI)
                .addAction(R.drawable.ic_prev_vector, getString(R.string.prev), prevPI)
                .addAction(isPlaying ? R.drawable.ic_pause_vector : R.drawable.ic_play_vector, isPlaying ? getString(R.string.pause) : getString(R.string.play), ppPI)
                .addAction(R.drawable.ic_next_vector, getString(R.string.next), nextPI)
                .setOnlyAlertOnce(true).setOngoing(isPlaying);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            builder.setStyle(new androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(android.support.v4.media.session.MediaSessionCompat.Token.fromToken(mediaSession.getSessionToken()))
                    .setShowActionsInCompactView(0, 1, 2));
        }

        return builder.build();
    }

    private final AudioManager.OnAudioFocusChangeListener afChangeListener = focusChange -> {
        MusicPlayer active = getActivePlayer();
        switch (focusChange) {
            case AudioManager.AUDIOFOCUS_LOSS:
                handleAction(ACTION_PAUSED_BY_SERVICE);
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                if (active.isPlaying()) {
                    active.pause();
                    isPlaying = false;
                    updatePlaybackState();
                    if (isForeground) notificationManager.notify(NOTIFICATION_ID, buildNotification());
                    if (callback != null) callback.onServiceAction(ACTION_PAUSED_BY_SERVICE);
                }
                break;
            case AudioManager.AUDIOFOCUS_GAIN:
                break;
        }
    };

    private int requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return audioManager.requestAudioFocus(new android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(new android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAcceptsDelayedFocusGain(true).setOnAudioFocusChangeListener(afChangeListener).build());
        }
        return audioManager.requestAudioFocus(afChangeListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
    }

    private void abandonAudioFocus() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) audioManager.abandonAudioFocus(afChangeListener);
    }

    private final BroadcastReceiver notificationReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { handleAction(intent.getAction()); }
    };

    private void registerNotificationReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_PLAY_PAUSE); filter.addAction(ACTION_NEXT); filter.addAction(ACTION_PREV); filter.addAction(ACTION_STOP);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) registerReceiver(notificationReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(notificationReceiver, filter);
    }

    private final BroadcastReceiver noisyReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) {
                MusicPlayer active = getActivePlayer();
                if (active.isPlaying()) {
                    active.pause(); isPlaying = false; updatePlaybackState();
                    if (isForeground) notificationManager.notify(NOTIFICATION_ID, buildNotification());
                    if (callback != null) callback.onServiceAction(ACTION_PAUSED_BY_SERVICE);
                }
            }
        }
    };

    private void registerNoisyReceiver() {
        IntentFilter filter = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) registerReceiver(noisyReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(noisyReceiver, filter);
    }

    public void updateLastPlayedPath(String path, String title, String artist, int position) {
        if (path == null || path.isEmpty()) return;
        lastPlayedPath = path; lastPlayedTitle = title; lastPlayedArtist = artist;
        getSharedPreferences("service_playback_prefs", MODE_PRIVATE).edit()
            .putString("last_played_path", path).putString("last_played_title", title)
            .putString("last_played_artist", artist).putInt("last_played_position", position).apply();
    }
    
    private void loadPlaybackState() {
        android.content.SharedPreferences prefs = getSharedPreferences("service_playback_prefs", MODE_PRIVATE);
        lastPlayedPath = prefs.getString("last_played_path", "");
        lastPlayedTitle = prefs.getString("last_played_title", "");
        lastPlayedArtist = prefs.getString("last_played_artist", "");
        if (!lastPlayedPath.isEmpty()) { currentTitle = lastPlayedTitle; currentArtist = lastPlayedArtist; }
    }
}
