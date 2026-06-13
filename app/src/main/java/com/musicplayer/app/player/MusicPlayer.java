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
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AtomicInteger playSession = new AtomicInteger(0);
    private static final long PROGRESS_INTERVAL_MS = 200;

    // 全局唯一播放追踪：确保同一时间只有一个 MusicPlayer 实例在播放
    private static volatile MusicPlayer activeInstance;

    // 预加载下一首：在当前歌曲播放期间后台 prepare，切歌时直接 swap，零等待
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

    /** 创建新的 MediaPlayer 实例（不阻塞） */
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

            // 停止其他 MusicPlayer 实例的播放（全局唯一播放保证）
            if (activeInstance != null && activeInstance != this) {
                activeInstance.stop();
            }
            activeInstance = this;

            // 新播放会话，使旧回调自动失效
            final int session = playSession.incrementAndGet();

            // 取消任何进行中的预加载
            cancelPreload();
            stopProgressUpdates();

            // 旧 player 在后台线程释放，不阻塞主线程
            final MediaPlayer oldPlayer = mediaPlayer;
            isPrepared = false;

            mediaPlayer = createNewMediaPlayer();
            final MediaPlayer newPlayer = mediaPlayer;
            
            // 设置唤醒模式，保证后台播放时 CPU 不进入休眠
            newPlayer.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK);

            if (oldPlayer != null) {
                new Thread(() -> {
                    try {
                        oldPlayer.reset();
                        oldPlayer.release();
                    } catch (Exception ignored) {}
                }).start();
            }

            // 使用 FileDescriptor 加载本地文件，比 setDataSource(String) 快
            File file = new File(path);
            RandomAccessFile raf = new RandomAccessFile(file, "r");
            try {
                newPlayer.setDataSource(raf.getFD(), 0, file.length());
            } finally {
                raf.close();
            }
            newPlayer.prepareAsync();

            newPlayer.setOnPreparedListener(mp -> {
                if (playSession.get() != session) return; // 已被新播放覆盖
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

    /**
     * 后台预加载下一首歌曲（调用 prepareAsync，不播放）。
     * 在当前歌曲开始播放后调用，为下一次切歌做准备。
     */
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

    /**
     * 若下一首已预加载完成且路径匹配，立即 swap 开始播放。
     * 返回歌曲时长（ms），失败返回 -1。
     * 注意：不调用 onPrepared 回调，由调用方自行更新 UI。
     */
    public int trySwapToPreloaded(String path) {
        if (!preloadReady || nextPlayer == null || !path.equals(preloadedPath)) return -1;

        final int session = playSession.incrementAndGet();
        stopProgressUpdates();

        // 释放当前正在播放的 player（后台）
        final MediaPlayer oldPlayer = mediaPlayer;
        isPrepared = false;

        // 提升预加载 player 为主 player
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
        promoted.setOnPreparedListener(null); // 清除预加载阶段的 listener
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

    /** 取消预加载，释放 nextPlayer */
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

    public boolean isPrepared() {
        return isPrepared;
    }

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
            try {
                return mediaPlayer.isPlaying();
            } catch (Exception e) {
                return false;
            }
        }
        return false;
    }

    public int getCurrentPosition() {
        if (isPrepared && mediaPlayer != null) {
            try {
                return mediaPlayer.getCurrentPosition();
            } catch (Exception ignored) {}
        }
        return 0;
    }

    public int getDuration() {
        if (isPrepared && mediaPlayer != null) {
            try {
                return mediaPlayer.getDuration();
            } catch (Exception ignored) {}
        }
        return 0;
    }

    public void release() {
        isPrepared = false;
        stopProgressUpdates();
        cancelPreload();
        if (activeInstance == this) {
            activeInstance = null;
        }
        if (mediaPlayer != null) {
            try {
                mediaPlayer.release();
                mediaPlayer = null;
            } catch (Exception ignored) {}
        }
    }

    /** 使用 Handler 替代线程轮询，更省电、更精确 */
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
