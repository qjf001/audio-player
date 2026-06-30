package com.musicplayer.app.player;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.concurrent.atomic.AtomicInteger;

public class MusicPlayer {
    private MediaPlayer mediaPlayer;
    private OnPlaybackListener listener;
    private volatile boolean isPrepared;
    private String currentPath;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AtomicInteger playSession = new AtomicInteger(0);
    private static final long PROGRESS_INTERVAL_MS = 200;

    // Remove static activeInstance to let Service handle mutual exclusion

    // 预加载下一首
    private MediaPlayer nextPlayer;
    private String preloadedPath;
    private volatile boolean preloadReady;

    public interface OnPlaybackListener {
        void onPrepared();
        void onCompletion();
        void onError(String error);
        void onShouldSkip();
        void onProgress(int currentPosition, int duration);
    }

    public MusicPlayer() {
        mediaPlayer = createNewMediaPlayer();
    }

    private static MediaPlayer createNewMediaPlayer() {
        MediaPlayer mp = new MediaPlayer();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            AudioAttributes attributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build();
            mp.setAudioAttributes(attributes);
        }
        return mp;
    }

    public void setOnPlaybackListener(OnPlaybackListener listener) {
        this.listener = listener;
    }

    public void play(Context context, String path) {
        try {
            if (path == null || path.isEmpty()) throw new IOException("无效路径");
            this.currentPath = path;

            final int session = playSession.incrementAndGet();
            cancelPreload();
            stopProgressUpdates();

            final MediaPlayer oldPlayer = mediaPlayer;
            isPrepared = false;

            mediaPlayer = createNewMediaPlayer();
            final MediaPlayer newPlayer = mediaPlayer;
            newPlayer.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK);

            if (oldPlayer != null) {
                new Thread(() -> {
                    try {
                        oldPlayer.reset();
                        oldPlayer.release();
                    } catch (Exception ignored) {}
                }).start();
            }

            File file = new File(path);
            RandomAccessFile raf = new RandomAccessFile(file, "r");
            try {
                newPlayer.setDataSource(raf.getFD(), 0, file.length());
            } finally {
                raf.close();
            }
            newPlayer.prepareAsync();

            newPlayer.setOnPreparedListener(mp -> {
                if (playSession.get() != session) return;
                isPrepared = true;
                try {
                    mp.start();
                    if (listener != null) listener.onPrepared();
                    startProgressUpdates(session);
                } catch (Exception ignored) {}
            });

            newPlayer.setOnCompletionListener(mp -> {
                if (playSession.get() != session) return;
                if (listener != null) listener.onCompletion();
            });

            newPlayer.setOnErrorListener((mp, what, extra) -> {
                isPrepared = false;
                if (playSession.get() != session) return true;
                if (listener != null) {
                    listener.onError("播放失败 (" + what + ")");
                    listener.onShouldSkip();
                }
                return true;
            });
        } catch (Exception e) {
            isPrepared = false;
            if (listener != null) listener.onError("启动失败: " + e.getMessage());
        }
    }

    public void preloadNext(String path) {
        if (path == null || path.equals(preloadedPath)) return;
        cancelPreload();
        try {
            MediaPlayer np = createNewMediaPlayer();
            nextPlayer = np;
            preloadedPath = path;
            preloadReady = false;
            File file = new File(path);
            RandomAccessFile raf = new RandomAccessFile(file, "r");
            try {
                np.setDataSource(raf.getFD(), 0, file.length());
            } finally {
                raf.close();
            }
            np.prepareAsync();
            np.setOnPreparedListener(mp -> preloadReady = true);
            np.setOnErrorListener((mp, w, e) -> {
                preloadReady = false;
                return true;
            });
        } catch (Exception e) {
            preloadReady = false;
        }
    }

    public int trySwapToPreloaded(String path) {
        if (!preloadReady || nextPlayer == null || !path.equals(preloadedPath)) return -1;
        this.currentPath = path;
        final int session = playSession.incrementAndGet();
        stopProgressUpdates();

        final MediaPlayer oldPlayer = mediaPlayer;
        isPrepared = false;

        mediaPlayer = nextPlayer;
        nextPlayer = null;
        preloadedPath = null;
        preloadReady = false;

        if (oldPlayer != null) {
            new Thread(() -> {
                try { oldPlayer.reset(); oldPlayer.release(); } catch (Exception ignored) {}
            }).start();
        }

        final MediaPlayer promoted = mediaPlayer;
        promoted.setOnPreparedListener(null);
        promoted.setOnCompletionListener(null);
        promoted.setOnErrorListener(null);

        isPrepared = true;
        int duration = 0;
        try {
            promoted.start();
            duration = promoted.getDuration();
            startProgressUpdates(session);
        } catch (Exception e) {
            isPrepared = false;
            return -1;
        }

        promoted.setOnCompletionListener(mp -> {
            if (playSession.get() != session) return;
            if (listener != null) listener.onCompletion();
        });
        promoted.setOnErrorListener((mp, what, extra) -> {
            isPrepared = false;
            if (playSession.get() != session) return true;
            if (listener != null) {
                listener.onError("播放失败 (" + what + ")");
                listener.onShouldSkip();
            }
            return true;
        });
        return duration;
    }

    public void cancelPreload() {
        if (nextPlayer != null) {
            final MediaPlayer np = nextPlayer;
            nextPlayer = null;
            new Thread(() -> {
                try { np.reset(); np.release(); } catch (Exception ignored) {}
            }).start();
        }
        preloadedPath = null;
        preloadReady = false;
    }

    public boolean isPrepared() { return isPrepared; }

    public void pause() {
        if (isPrepared && mediaPlayer != null) {
            try {
                mediaPlayer.pause();
                stopProgressUpdates();
            } catch (Exception ignored) {}
        }
    }

    public void resume() {
        if (isPrepared && mediaPlayer != null) {
            try {
                mediaPlayer.start();
                startProgressUpdates(playSession.get());
            } catch (Exception ignored) {}
        }
    }

    public void stop() {
        isPrepared = false;
        stopProgressUpdates();
        if (mediaPlayer != null) {
            try {
                mediaPlayer.stop();
                mediaPlayer.reset();
            } catch (Exception ignored) {}
        }
    }

    public void seekTo(int position) {
        if (isPrepared && mediaPlayer != null) {
            try {
                mediaPlayer.seekTo(position);
            } catch (Exception ignored) {}
        }
    }

    public boolean isPlaying() {
        if (isPrepared && mediaPlayer != null) {
            try { return mediaPlayer.isPlaying(); } catch (Exception e) { return false; }
        }
        return false;
    }

    public int getCurrentPosition() {
        if (isPrepared && mediaPlayer != null) {
            try { return mediaPlayer.getCurrentPosition(); } catch (Exception ignored) {}
        }
        return 0;
    }

    public int getDuration() {
        if (isPrepared && mediaPlayer != null) {
            try { return mediaPlayer.getDuration(); } catch (Exception ignored) {}
        }
        return 0;
    }

    public String getCurrentPath() { return currentPath; }

    public void release() {
        isPrepared = false;
        stopProgressUpdates();
        cancelPreload();
        if (mediaPlayer != null) {
            try {
                mediaPlayer.release();
                mediaPlayer = null;
            } catch (Exception ignored) {}
        }
    }

    private final Runnable progressRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isPrepared || mediaPlayer == null) return;
            try {
                if (!mediaPlayer.isPlaying()) return;
                int current = getCurrentPosition();
                int duration = getDuration();
                if (listener != null && isPrepared) {
                    listener.onProgress(current, duration);
                }
                handler.postDelayed(this, PROGRESS_INTERVAL_MS);
            } catch (Exception ignored) {}
        }
    };

    private void startProgressUpdates(int session) {
        handler.removeCallbacks(progressRunnable);
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (playSession.get() != session) return;
                progressRunnable.run();
            }
        });
    }

    private void stopProgressUpdates() {
        handler.removeCallbacks(progressRunnable);
    }
}
