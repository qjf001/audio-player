package com.musicplayer.app;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.BroadcastReceiver;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaMetadataRetriever;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.Settings;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.musicplayer.app.adapter.SongAdapter;
import com.musicplayer.app.model.LrcLine;
import com.musicplayer.app.model.Song;
import com.musicplayer.app.model.Tag;
import com.musicplayer.app.player.MusicPlayer;
import com.musicplayer.app.player.MusicPlayerService;
import com.musicplayer.app.player.TrueRandomShuffler;
import com.musicplayer.app.storage.MusicDirectoryManager;
import com.musicplayer.app.storage.TagManager;

import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.TagField;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends AppCompatActivity {
    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final int REQUEST_FOLDER_ACCESS = 101;
    private static final int REQUEST_TAG_PLAYER = 102;
    
    private RecyclerView recyclerSongs;
    private SongAdapter songAdapter;
    private Spinner spinnerTags;
    private Button btnTagPlayer, btnShuffle, btnGoWeb;
    private ImageButton btnPlayPause, btnPrev, btnNext, btnSettings, btnCollapse, btnFullShuffle, btnList, btnWebHome, btnMiniPrev, btnMiniPlayPause, btnMiniNext, btnSearch;
    private EditText editSearch, editWebUrl;
    private SeekBar seekProgress, seekVolume;
    private ProgressBar seekMiniProgress, webProgress;
    private TextView textSongInfo, textCurrentDir, textMiniStatus, textFullTitle, textFullArtist, textFullPlayingBarTitle, textCurrentTime, textTotalTime;
    
    private View layoutHome, layoutDiscovery, bottomSheetPlayer, miniPlayer, fullPlayer;
    private androidx.swiperefreshlayout.widget.SwipeRefreshLayout swipeRefresh;
    private android.widget.LinearLayout layoutCategories;
    private android.os.FileObserver directoryObserver;
    private BottomSheetBehavior<View> sheetBehavior;
    private com.google.android.material.bottomnavigation.BottomNavigationView bottomNavigation;
    private WebView webView;
    private com.musicplayer.app.adapter.LrcAdapter lrcAdapter;
    private RecyclerView recyclerLyrics;
    
    private List<Song> allSongs = new ArrayList<>();
    private List<Song> currentSongList = new ArrayList<>();
    private int currentSongIndex = -1;
    private int consecutiveFailures = 0;
    private MusicPlayer musicPlayer;
    private MusicPlayerService musicService;
    private boolean serviceBound = false;
    private TagManager tagManager;
    private MusicDirectoryManager directoryManager;
    private AudioManager audioManager;
    private MediaSession mediaSession;
    private boolean isPlaying = false;
    private boolean isSearchVisible = false;
    
    // 监听 Service 的通知栏按钮和 Service 主动暂停事件
    private final MusicPlayerService.ServiceCallback serviceCallback = action -> runOnUiThread(() -> {
        switch (action) {
            case MusicPlayerService.ACTION_PLAY_PAUSE:
                togglePlayPause();
                break;
            case MusicPlayerService.ACTION_NEXT:
                consecutiveFailures = 0;
                playNext();
                break;
            case MusicPlayerService.ACTION_PREV:
                playPrevious();
                break;
            case MusicPlayerService.ACTION_STOP:
                if (musicPlayer != null) musicPlayer.stop();
                isPlaying = false;
                updatePlayPauseUI();
                updateMediaSessionState();
                break;
            case MusicPlayerService.ACTION_PAUSED_BY_SERVICE:
                // 耳机断开等情况，Service 已暂停播放，此处只更新 UI
                isPlaying = false;
                updatePlayPauseUI();
                updateMediaSessionState();
                break;
        }
    });

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            MusicPlayerService.MusicBinder binder = (MusicPlayerService.MusicBinder) service;
            musicService = binder.getService();
            musicPlayer = musicService.getMusicPlayer();
            mediaSession = musicService.getMediaSession();
            serviceBound = true;

            musicService.setCallback(serviceCallback);

            // 初始化播放器和加载歌曲（依赖 musicPlayer / mediaSession）
            initPlayer();
            initMediaSession();
            loadSongs();

            // 如果 Service 正在播放，同步 UI
            if (musicPlayer.isPlaying()) {
                isPlaying = true;
                updatePlayPauseUI();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            serviceBound = false;
            musicService = null;
        }
    };

    private boolean isTagFiltered = false;
    private String selectedFilterType = "none"; // "none", "category", "tag"
    private String selectedCategory = "";
    private Song lastPlayedSong = null;
    private boolean shouldResumeFromSavedPosition = false;
    private int pendingSeekPosition = 0;

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.applyLocale(newBase));
    }
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            getWindow().setStatusBarColor(ContextCompat.getColor(this, R.color.colorPrimary));
            getWindow().getDecorView().setSystemUiVisibility(getWindow().getDecorView().getSystemUiVisibility() & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }

        setContentView(R.layout.activity_main);
        
        tagManager = new TagManager(this);
        directoryManager = new MusicDirectoryManager(this);
        // musicPlayer 将在 Service 绑定后获取

        // 首次启动：自动将扫描目录设为系统 Music 目录
        handleFirstLaunch();

        initViews();

        // 注册耳机断开广播监听（已迁移到 Service，Activity 不再注册）
        checkPermissions();
        setupDirectoryObserver();

        // 启动并绑定音乐播放服务
        Intent serviceIntent = new Intent(this, MusicPlayerService.class);
        startService(serviceIntent);
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);

        checkBatteryOptimizations();
    }
    
    private void checkBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            String packageName = getPackageName();
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                new AlertDialog.Builder(this)
                        .setTitle(R.string.battery_optimization_title)
                        .setMessage(R.string.battery_optimization_message)
                        .setPositiveButton(R.string.go_set, (dialog, which) -> {
                            try {
                                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                                intent.setData(Uri.parse("package:" + packageName));
                                startActivity(intent);
                            } catch (Exception e) {
                                Intent intent = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                                startActivity(intent);
                            }
                        })
                        .setNegativeButton(R.string.cancel, null)
                        .show();
            }
        }
    }
    
    private void initViews() {
        recyclerSongs = findViewById(R.id.recycler_songs);
        spinnerTags = findViewById(R.id.spinner_tags);
        btnTagPlayer = findViewById(R.id.btn_tag_player);
        btnShuffle = findViewById(R.id.btn_shuffle);
        editSearch = findViewById(R.id.edit_search);
        btnPlayPause = findViewById(R.id.btn_play_pause);
        btnPrev = findViewById(R.id.btn_prev);
        btnNext = findViewById(R.id.btn_next);
        seekProgress = findViewById(R.id.seek_progress);
        seekVolume = findViewById(R.id.seek_volume);
        textSongInfo = findViewById(R.id.text_song_info);
        textCurrentDir = findViewById(R.id.text_current_dir);
        btnSearch = findViewById(R.id.btn_search);
        btnSettings = findViewById(R.id.btn_settings);
        
        layoutHome = findViewById(R.id.layout_home);
        layoutDiscovery = findViewById(R.id.layout_discovery);
        layoutCategories = findViewById(R.id.layout_categories);
        bottomNavigation = findViewById(R.id.bottom_navigation);
        swipeRefresh = findViewById(R.id.swipe_refresh);
        swipeRefresh.setColorSchemeColors(ContextCompat.getColor(this, R.color.colorPrimary));
        swipeRefresh.setOnRefreshListener(this::refreshSongList);
        webView = findViewById(R.id.webview);
        editWebUrl = findViewById(R.id.edit_web_url);
        btnGoWeb = findViewById(R.id.btn_go_web);
        btnWebHome = findViewById(R.id.btn_web_home);
        webProgress = findViewById(R.id.web_progress);
        
        updateDirectoryDisplay();
        initBottomSheet();
        initDiscovery();
        
        bottomNavigation.setOnItemSelectedListener(item -> {
            int itemId = item.getItemId();
            if (itemId == R.id.nav_home) {
                layoutHome.setVisibility(View.VISIBLE);
                layoutDiscovery.setVisibility(View.GONE);
                sheetBehavior.setHideable(false);
                sheetBehavior.setState(BottomSheetBehavior.STATE_COLLAPSED);
                return true;
            } else if (itemId == R.id.nav_discovery) {
                layoutHome.setVisibility(View.GONE);
                layoutDiscovery.setVisibility(View.VISIBLE);
                sheetBehavior.setHideable(true);
                sheetBehavior.setState(BottomSheetBehavior.STATE_HIDDEN);
                return true;
            }
            return false;
        });
        
        recyclerSongs.setLayoutManager(new LinearLayoutManager(this));
        songAdapter = new SongAdapter(currentSongList);
        recyclerSongs.setAdapter(songAdapter);
        
        songAdapter.setOnSongClickListener((song, position) -> playSongManually(song, position));
        songAdapter.setOnSongLongClickListener((song, position) -> showSongOptionsDialog(song));
        
        btnTagPlayer.setOnClickListener(v -> openTagPlayer());
        btnShuffle.setOnClickListener(v -> shuffleSongs());
        btnPlayPause.setOnClickListener(v -> togglePlayPause());
        btnPrev.setOnClickListener(v -> playPrevious());
        btnNext.setOnClickListener(v -> { consecutiveFailures = 0; playNext(); });
        btnSettings.setOnClickListener(v -> showSettingsDialog());
        
        // 搜索按钮切换
        btnSearch.setOnClickListener(v -> {
            isSearchVisible = !isSearchVisible;
            editSearch.setVisibility(isSearchVisible ? View.VISIBLE : View.GONE);
            if (!isSearchVisible) {
                editSearch.setText("");
                filterSongs("");
            } else {
                editSearch.requestFocus();
            }
        });
        
        editSearch.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) { filterSongs(s.toString()); }
            @Override
            public void afterTextChanged(android.text.Editable s) {}
        });
        
        seekProgress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) { if (fromUser && musicPlayer != null) musicPlayer.seekTo(progress); }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        seekVolume.setMax(audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        seekVolume.setProgress(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC));
        seekVolume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0); }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });
    }
    
    private void initBottomSheet() {
        bottomSheetPlayer = findViewById(R.id.bottom_sheet_player);
        sheetBehavior = BottomSheetBehavior.from(bottomSheetPlayer);
        miniPlayer = findViewById(R.id.mini_player);
        fullPlayer = findViewById(R.id.full_player);
        btnMiniPlayPause = findViewById(R.id.btn_mini_play_pause);
        btnMiniPrev = findViewById(R.id.btn_mini_prev);
        btnMiniNext = findViewById(R.id.btn_mini_next);
        seekMiniProgress = findViewById(R.id.seek_mini_progress);
        textMiniStatus = findViewById(R.id.text_mini_status);
        btnCollapse = findViewById(R.id.btn_collapse);
        textFullTitle = findViewById(R.id.text_full_title);
        textFullArtist = findViewById(R.id.text_full_artist);
        textFullPlayingBarTitle = findViewById(R.id.text_full_playing_bar_title);
        textCurrentTime = findViewById(R.id.text_current_time);
        textTotalTime = findViewById(R.id.text_total_time);
        btnFullShuffle = findViewById(R.id.btn_full_shuffle);
        btnList = findViewById(R.id.btn_list);
        recyclerLyrics = findViewById(R.id.recycler_lyrics);
        lrcAdapter = new com.musicplayer.app.adapter.LrcAdapter();
        recyclerLyrics.setLayoutManager(new LinearLayoutManager(this));
        recyclerLyrics.setAdapter(lrcAdapter);

        miniPlayer.setOnClickListener(v -> sheetBehavior.setState(BottomSheetBehavior.STATE_EXPANDED));
        btnCollapse.setOnClickListener(v -> sheetBehavior.setState(BottomSheetBehavior.STATE_COLLAPSED));
        btnMiniPlayPause.setOnClickListener(v -> togglePlayPause());
        btnMiniPrev.setOnClickListener(v -> { playPrevious(); });
        btnMiniNext.setOnClickListener(v -> { consecutiveFailures = 0; playNext(); });
        btnFullShuffle.setOnClickListener(v -> shuffleSongs());
        btnList.setOnClickListener(v -> {
            sheetBehavior.setState(BottomSheetBehavior.STATE_COLLAPSED);
            recyclerSongs.smoothScrollToPosition(Math.max(0, currentSongIndex));
        });

        sheetBehavior.addBottomSheetCallback(new BottomSheetBehavior.BottomSheetCallback() {
            @Override
            public void onStateChanged(@NonNull View bottomSheet, int newState) {
                miniPlayer.setVisibility(newState == BottomSheetBehavior.STATE_EXPANDED ? View.GONE : View.VISIBLE);
                fullPlayer.setVisibility(newState == BottomSheetBehavior.STATE_COLLAPSED ? View.GONE : View.VISIBLE);
            }
            @Override
            public void onSlide(@NonNull View bottomSheet, float slideOffset) {
                miniPlayer.setAlpha(1 - slideOffset);
                fullPlayer.setAlpha(slideOffset);
                if (slideOffset > 0) fullPlayer.setVisibility(View.VISIBLE);
                if (slideOffset < 1) miniPlayer.setVisibility(View.VISIBLE);
            }
        });
    }

    private void initDiscovery() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setTextZoom(100);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setUserAgentString("Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");
        
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) { v.loadUrl(r.getUrl().toString()); return true; }
            @Override
            @SuppressWarnings("deprecation")
            public boolean shouldOverrideUrlLoading(WebView v, String u) { v.loadUrl(u); return true; }
            @Override
            public void onPageFinished(WebView v, String u) {
                super.onPageFinished(v, u);
                v.loadUrl("javascript:(function() { " +
                        "var m = document.querySelector('meta[name=\"viewport\"]');" +
                        "if (!m) { m = document.createElement('meta'); m.name = 'viewport'; document.getElementsByTagName('head')[0].appendChild(m); }" +
                        "m.setAttribute('content', 'width=1024, initial-scale=' + (window.innerWidth / 1024) + ', user-scalable=yes');" +
                        "})()");
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView v, int p) {
                webProgress.setVisibility(p == 100 ? View.GONE : View.VISIBLE);
                webProgress.setProgress(p);
            }
        });
        webView.setDownloadListener((u, ua, c, m, cl) -> {
            try {
                android.app.DownloadManager.Request r = new android.app.DownloadManager.Request(Uri.parse(u));
                r.setMimeType(m);
                r.allowScanningByMediaScanner();
                r.setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                String f = android.webkit.URLUtil.guessFileName(u, c, m);
                r.setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, f);
                ((android.app.DownloadManager) getSystemService(DOWNLOAD_SERVICE)).enqueue(r);
                Toast.makeText(MainActivity.this, getString(R.string.start_download, f), Toast.LENGTH_SHORT).show();
            } catch (Exception e) { Toast.makeText(MainActivity.this, R.string.download_failed, Toast.LENGTH_SHORT).show(); }
        });
        btnGoWeb.setOnClickListener(v -> {
            String input = editWebUrl.getText().toString().trim();
            if (!input.isEmpty()) {
                String finalUrl;
                if (input.startsWith("http")) {
                    finalUrl = input;
                } else {
                    finalUrl = "https://" + input;
                }
                webView.loadUrl(finalUrl);
                ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(editWebUrl.getWindowToken(), 0);
            }
        });
        btnWebHome.setOnClickListener(v -> { webView.loadUrl("https://music.gdstudio.org/"); editWebUrl.setText("https://music.gdstudio.org/"); });
        webView.loadUrl("https://music.gdstudio.org/");
    }

    private void initPlayer() {
        musicPlayer.setOnPlaybackListener(new MusicPlayer.OnPlaybackListener() {
            @Override
            public void onPrepared() {
                runOnUiThread(() -> {
                    int d = musicPlayer.getDuration();
                    seekProgress.setMax(d);
                    seekMiniProgress.setMax(d);
                    textTotalTime.setText(formatTime(d));
                    consecutiveFailures = 0;
                    updateMediaSessionState();
                    // 处理断点续播：在 MediaPlayer 准备就绪后才执行 seekTo
                    if (pendingSeekPosition > 0) {
                        musicPlayer.seekTo(pendingSeekPosition);
                        pendingSeekPosition = 0;
                    }
                });
            }
            @Override
            public void onCompletion() {
                runOnUiThread(() -> {
                    isPlaying = false;
                    
                    // 歌曲播完，重置进度为0（所有分类）
                    if (lastPlayedSong != null) {
                        String cat = lastPlayedSong.getCategory();
                        if (cat != null && !cat.equals("音乐")) {
                            tagManager.updateLastPosition(lastPlayedSong.getId(), 0);
                        }
                        lastPlayedSong.setLastPosition(0);
                        lastPlayedSong = null;
                    }
            
                    updateMediaSessionState();
                    updatePlayPauseUI();
                    if (musicService != null) musicService.notifyPlaybackPaused();
                    consecutiveFailures = 0;
                    playNext();
                });
            }
            @Override
            public void onError(String e) { runOnUiThread(() -> Toast.makeText(MainActivity.this, e, Toast.LENGTH_SHORT).show()); }
            @Override
            public void onShouldSkip() {
                runOnUiThread(() -> {
                    consecutiveFailures++;
                    if (consecutiveFailures >= currentSongList.size()) {
                        Toast.makeText(MainActivity.this, R.string.all_songs_failed, Toast.LENGTH_SHORT).show();
                        isPlaying = false;
                        updatePlayPauseUI();
                    } else {
                        playNext();
                    }
                });
            }
            @Override
            public void onProgress(int c, int d) {
                runOnUiThread(() -> {
                    seekProgress.setProgress(c);
                    seekMiniProgress.setProgress(c);
                    textCurrentTime.setText(formatTime(c));
                    int l = lrcAdapter.updateCurrentLine(c);
                    if (l != -1) recyclerLyrics.smoothScrollToPosition(l);
                });
            }
        });
    }

    /** 更新播放/暂停按钮图标（抽取公共方法） */
    private void updatePlayPauseUI() {
        btnPlayPause.setImageResource(isPlaying ? R.drawable.ic_pause_vector : R.drawable.ic_play_vector);
        btnMiniPlayPause.setImageResource(isPlaying ? R.drawable.ic_pause_vector : R.drawable.ic_play_vector);
    }

    private void updateDirectoryDisplay() {
        String d = directoryManager.getMusicDirectory();
        textCurrentDir.setText(d != null && !d.isEmpty() ? getString(R.string.current_directory) + d : getString(R.string.no_directory));
    }

    /**
     * 首次启动优化：如果用户未设置过目录，自动将扫描目录设为系统 Music 目录，
     * 避免扫描全盘。用户之后可以在设置中修改。
     */
    private void handleFirstLaunch() {
        android.content.SharedPreferences prefs = getSharedPreferences("app_prefs", MODE_PRIVATE);
        boolean isFirstLaunch = !prefs.getBoolean("first_launch_done", false);
        if (isFirstLaunch && !directoryManager.hasCustomDirectory()) {
            // 默认使用系统 Music 目录
            File musicDir = android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_MUSIC);
            if (musicDir != null && musicDir.exists()) {
                directoryManager.setMusicDirectory(musicDir.getAbsolutePath());
            }
        }
        // 标记首次启动已完成
        prefs.edit().putBoolean("first_launch_done", true).apply();
    }

    private void checkPermissions() {
        List<String> p = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED) p.add(Manifest.permission.READ_MEDIA_AUDIO);
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) p.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        }
        if (!p.isEmpty()) ActivityCompat.requestPermissions(this, p.toArray(new String[0]), PERMISSION_REQUEST_CODE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            // 权限授予后，自动触发扫描
            refreshSongList();
        }
    }

    private void loadSongs() {
        allSongs.clear();
        allSongs.addAll(tagManager.getAllSongs());
        updateSongList();
        updateTagSpinner();
        restorePlaybackState();
    }

    private void savePlaybackState() {
        if (lastPlayedSong != null) {
            int pos = musicPlayer.getCurrentPosition();
            // 使用 commit() 同步写入，确保进程被杀时数据不丢失
            getSharedPreferences("player_prefs", MODE_PRIVATE).edit()
                .putLong("last_song_id", lastPlayedSong.getId())
                .putInt("last_position", pos)
                .commit();
            // 非音乐类同时保存到数据库（用于列表进度条显示）
            String cat = lastPlayedSong.getCategory();
            if (cat != null && !cat.equals("音乐")) {
                tagManager.updateLastPosition(lastPlayedSong.getId(), pos);
                lastPlayedSong.setLastPosition(pos);
            }
        }
    }

    private void restorePlaybackState() {
        android.content.SharedPreferences prefs = getSharedPreferences("player_prefs", MODE_PRIVATE);
        long lastId = prefs.getLong("last_song_id", -1);
        int lastPos = prefs.getInt("last_position", 0);

        if (lastId != -1) {
            Song restoredSong = null;
            // 在 allSongs 中查找歌曲
            for (Song s : allSongs) {
                if (s.getId() == lastId) {
                    restoredSong = s;
                    break;
                }
            }
            
            if (restoredSong != null) {
                lastPlayedSong = restoredSong;
                restoredSong.setLastPosition(lastPos);
                shouldResumeFromSavedPosition = true;
                
                // 在 currentSongList 中查找正确索引（不是 allSongs 的索引）
                currentSongIndex = -1;
                for (int i = 0; i < currentSongList.size(); i++) {
                    if (currentSongList.get(i).getId() == lastId) {
                        currentSongIndex = i;
                        break;
                    }
                }
                // 若不在当前过滤列表中，回退到 allSongs 索引
                if (currentSongIndex == -1) {
                    for (int i = 0; i < allSongs.size(); i++) {
                        if (allSongs.get(i).getId() == lastId) {
                            currentSongIndex = i;
                            break;
                        }
                    }
                }
                
                String art = restoredSong.getArtist();
                if (art == null || art.isEmpty() || art.contains("<unknown>")) art = "";
                textSongInfo.setText(restoredSong.getTitle() + (art.isEmpty() ? "" : " - " + art));
                textFullTitle.setText(restoredSong.getTitle());
                textFullArtist.setText(art);
                textFullArtist.setVisibility(art.isEmpty() ? View.INVISIBLE : View.VISIBLE);
                textFullPlayingBarTitle.setText(restoredSong.getTitle() + (art.isEmpty() ? "" : " - " + art));
                
                seekProgress.setProgress(lastPos);
                seekMiniProgress.setProgress(lastPos);
                textCurrentTime.setText(formatTime(lastPos));
                
                songAdapter.setSelectedPosition(currentSongIndex);
            }
        }
    }

    private void loadSongsFromDevice() {
        String d = directoryManager.getMusicDirectory();
        if (d != null && !d.isEmpty()) loadSongsFromDirectory(d); else loadSongsFromMediaStore();
    }

    private void loadSongsFromMediaStore() {
        Uri uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        String[] proj = { MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DURATION };
        Cursor c = getContentResolver().query(uri, proj, MediaStore.Audio.Media.IS_MUSIC + " != 0", null, null);
        if (c != null) {
            while (c.moveToNext()) {
                String path = c.getString(0);
                Song s = new Song();
                s.setPath(path);
                s.setTitle(Song.formatTitleFromPath(path));
                String art = c.getString(1);
                String alb = c.getString(2);
                if (art == null || art.contains("<unknown>")) updateMetadataFromFile(s);
                else { s.setArtist(art); s.setAlbum(alb); }
                s.setDuration(c.getLong(3));
                s.setId(tagManager.addSong(s));
                allSongs.add(s);
            }
            c.close();
        }
    }

    private void loadSongsFromDirectory(String path) {
        File dir = new File(path);
        if (!dir.exists() || !dir.isDirectory()) return;
        List<String> existingPaths = new ArrayList<>();
        for (Song s : allSongs) existingPaths.add(s.getPath());
        // 只扫描根目录下的音频文件（不递归），子目录属于分类
        File[] files = dir.listFiles();
        if (files != null) {
            for (File entry : files) {
                if (!entry.isFile()) continue;
                String n = entry.getName().toLowerCase();
                if (!n.endsWith(".mp3") && !n.endsWith(".flac") && !n.endsWith(".wav") && !n.endsWith(".aac") && !n.endsWith(".m4a") && !n.endsWith(".ogg")) continue;
                Song s = new Song();
                s.setPath(entry.getAbsolutePath());
                s.setTitle(Song.formatTitleFromPath(entry.getAbsolutePath()));
                updateMetadataFromFile(s);
                s.setCategory("音乐");
                s.setId(tagManager.addSong(s));
                allSongs.add(s);
            }
        }
        // 扫描子目录中的音频文件，以子目录名称为分类
        File[] subdirs = dir.listFiles(File::isDirectory);
        if (subdirs != null) {
            for (File subdir : subdirs) {
                if (subdir.getName().startsWith(".")) continue;
                loadCategorySongs(subdir, subdir.getName());
            }
        }
    }

    private void loadCategorySongs(File categoryDir, String categoryName) {
        File[] entries = categoryDir.listFiles();
        if (entries == null) return;
        for (File entry : entries) {
            if (entry.isDirectory()) {
                if (entry.getName().startsWith(".")) continue;
                loadCategorySongs(entry, categoryName);
            } else if (entry.isFile()) {
                String n = entry.getName().toLowerCase();
                if (!n.endsWith(".mp3") && !n.endsWith(".flac") && !n.endsWith(".wav") && !n.endsWith(".aac") && !n.endsWith(".m4a") && !n.endsWith(".ogg")) continue;
                Song s = new Song();
                s.setPath(entry.getAbsolutePath());
                s.setTitle(Song.formatTitleFromPath(entry.getAbsolutePath()));
                updateMetadataFromFile(s);
                s.setCategory(categoryName);
                s.setId(tagManager.addSong(s));
                tagManager.addCategoryTag(categoryName);
                allSongs.add(s);
            }
        }
    }

    private void filterSongs(String q) {
        String lq = q.toLowerCase().trim();
        List<Song> base = new ArrayList<>();
        
        try {
            if ("category".equals(selectedFilterType)) {
                // 按目录分类过滤：使用歌曲的 category 字段
                for (Song s : allSongs) {
                    if (selectedCategory.equals(s.getCategory())) {
                        base.add(s);
                    }
                }
            } else if ("tag".equals(selectedFilterType)) {
                // 按用户标签过滤：通过 song_tags 关联表
                long tagId = tagManager.addTag(selectedCategory);
                List<Long> tagSongIds = tagManager.getSongIdsForTag(tagId);
                for (Song s : allSongs) {
                    if (tagSongIds.contains(s.getId())) {
                        base.add(s);
                    }
                }
            } else {
                base.addAll(allSongs);
            }
        } catch (Exception e) {
            base.clear();
            base.addAll(allSongs);
        }

        currentSongList.clear();
        for (Song s : base) {
            if (lq.isEmpty() || (s.getTitle() != null && s.getTitle().toLowerCase().contains(lq)) || 
                (s.getArtist() != null && s.getArtist().toLowerCase().contains(lq))) {
                currentSongList.add(s);
            }
        }
        songAdapter.updateSongs(currentSongList);
    }

    private void updateSongList() { 
        filterSongs(editSearch.getText().toString()); 
    }

    private void updateCategoryBar() {
        if (layoutCategories == null) return;
        try {
            layoutCategories.removeAllViews();
                
            // 1. 添加 "全部音乐"
            addFilterChip(layoutCategories, getString(R.string.all_music), true, "none");
                
            // 2. 仅在设置了自定义加载目录时，扫描一级子目录作为分类
            String dirPath = directoryManager.getMusicDirectory();
            if (dirPath != null && !dirPath.isEmpty()) {
                File dir = new File(dirPath);
                File[] subdirs = dir.listFiles(File::isDirectory);
                if (subdirs != null) {
                    java.util.Arrays.sort(subdirs, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
                    for (File subdir : subdirs) {
                        // 跳过以点开头的隐藏目录（系统目录）
                        if (subdir.getName().startsWith(".")) continue;
                        addFilterChip(layoutCategories, subdir.getName(), false, "category");
                    }
                }
            }
            // MediaStore 模式不创建子分类，只显示"全部音乐"
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    private void updateTagSpinner() {
        updateCategoryBar();
        updateTagPlayerButtonState();
    }

    /** 根据是否存在用户标签，启用/禁用标签播放按钮 */
    private void updateTagPlayerButtonState() {
        if (btnTagPlayer == null) return;
        boolean hasTags = tagManager.getUserTags().size() > 0;
        btnTagPlayer.setEnabled(hasTags);
        btnTagPlayer.setAlpha(hasTags ? 1.0f : 0.4f);
    }
    
    private void addFilterChip(android.widget.LinearLayout container, String name, boolean isAll, String filterType) {
        Button btn = new Button(new android.view.ContextThemeWrapper(this, com.google.android.material.R.style.Widget_MaterialComponents_Button_TextButton), null, 0);
            
        // 统计数量
        int count = 0;
        if (isAll) {
            count = tagManager.getTotalSongCount();
        } else if ("category".equals(filterType)) {
            count = tagManager.getSongCountByCategory(name);
        } else if ("tag".equals(filterType)) {
            count = tagManager.getSongCountForTag(tagManager.addTag(name));
        }
            
        btn.setText(name + " (" + count + ")");
        btn.setTextSize(11);
        btn.setAllCaps(false);
        btn.setPadding(20, 0, 20, 0);
        btn.setMinWidth(0);
        btn.setMinimumWidth(0);
            
        int gray = ContextCompat.getColor(this, android.R.color.darker_gray);
        int red = ContextCompat.getColor(this, R.color.colorPrimary);
        boolean isSelected = isAll ? "none".equals(selectedFilterType) : (selectedCategory.equals(name) && selectedFilterType.equals(filterType));
        btn.setTextColor(isSelected ? red : gray);
            
        btn.setOnClickListener(v -> {
            selectedCategory = name;
            selectedFilterType = filterType;
            isTagFiltered = !isAll;
            updateTagSpinner();
            filterSongs(editSearch.getText().toString());
        });
            
        container.addView(btn);
    }

    private void showSettingsDialog() {
        int dp16 = (int) (16 * getResources().getDisplayMetrics().density);
        int dp1  = (int) (1  * getResources().getDisplayMetrics().density);
        int textColor = isDarkMode() ? 0xFFFFFFFF : 0xFF000000;

        android.widget.LinearLayout listLayout = new android.widget.LinearLayout(this);
        listLayout.setOrientation(android.widget.LinearLayout.VERTICAL);
        listLayout.setPadding(0, dp16, 0, 0);

        // 辅助：添加水平分割线
        java.util.function.Supplier<View> makeDivider = () -> {
            View d = new View(this);
            android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT, dp1);
            lp.setMargins(dp16 * 2, 0, dp16 * 2, 0);
            d.setLayoutParams(lp);
            d.setBackgroundColor(0x30000000);
            return d;
        };

        // 辅助：创建可点击的文字项
        java.util.function.BiFunction<String, Runnable, TextView> makeItem = (text, action) -> {
            TextView tv = new TextView(this);
            tv.setText(text);
            tv.setTextSize(16);
            tv.setTextColor(textColor);
            tv.setPadding(dp16, dp16, dp16, dp16);
            if (action != null) {
                tv.setOnClickListener(v -> action.run());
            }
            return tv;
        };

        // 当前目录（若已设置）
        String cur = directoryManager.getMusicDirectory();
        if (cur != null && !cur.isEmpty()) {
            TextView dirItem = new TextView(this);
            dirItem.setText(getString(R.string.current_directory) + cur);
            dirItem.setTextSize(14);
            dirItem.setTextColor(0xFF888888);
            dirItem.setPadding(dp16 * 2, dp16, dp16 * 2, dp16);
            listLayout.addView(dirItem);
            listLayout.addView(makeDivider.get());
        }

        // 同一行：选择音乐目录 | 扫描/加载
        android.widget.LinearLayout rowFolderScan = new android.widget.LinearLayout(this);
        rowFolderScan.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        TextView itemFolder = makeItem.apply(getString(R.string.select_folder), () -> selectMusicFolder());
        TextView itemScan  = makeItem.apply(getString(R.string.refresh),       () -> refreshSongList());
        android.widget.LinearLayout.LayoutParams lpHalf = new android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        itemFolder.setLayoutParams(lpHalf);
        itemScan.setLayoutParams(lpHalf);
        itemFolder.setGravity(android.view.Gravity.CENTER);
        itemScan.setGravity(android.view.Gravity.CENTER);
        rowFolderScan.addView(itemFolder);
        // 中间垂直分割线
        View vDiv = new View(this);
        vDiv.setLayoutParams(new android.widget.LinearLayout.LayoutParams(dp1, android.widget.LinearLayout.LayoutParams.MATCH_PARENT));
        vDiv.setBackgroundColor(0x30000000);
        rowFolderScan.addView(vDiv);
        rowFolderScan.addView(itemScan);
        listLayout.addView(rowFolderScan);
        listLayout.addView(makeDivider.get());

        // 管理标签
        listLayout.addView(makeItem.apply(getString(R.string.manage_tags), () -> showManageTagsDialog()));
        listLayout.addView(makeDivider.get());

        // 语言切换（显示当前选中的语言）
        String langDisplay = getLanguageDisplayName(LocaleHelper.getSavedLanguage(this));
        listLayout.addView(makeItem.apply(getString(R.string.language) + "：" + langDisplay, () -> showLanguageDialog()));
        listLayout.addView(makeDivider.get());

        // 清空列表
        listLayout.addView(makeItem.apply(getString(R.string.clear_list), () -> showClearListConfirmation()));
        listLayout.addView(makeDivider.get());

        // 关于（含打赏）
        listLayout.addView(makeItem.apply(getString(R.string.about_menu), () -> {
            startActivity(new Intent(MainActivity.this, AboutActivity.class));
        }));

        android.widget.ScrollView scrollView = new android.widget.ScrollView(this);
        scrollView.addView(listLayout);

        new AlertDialog.Builder(this).setTitle(R.string.settings).setView(scrollView)
                .setNegativeButton(R.string.cancel, null).show();
    }

    private boolean isDarkMode() {
        int nightModeFlags = getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return nightModeFlags == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    private String getLanguageDisplayName(String langCode) {
        if (LocaleHelper.LANG_ZH.equals(langCode))      return "简体中文";
        if (LocaleHelper.LANG_ZH_TW.equals(langCode))   return "繁體中文";
        if (LocaleHelper.LANG_EN.equals(langCode))      return "English";
        return getString(R.string.lang_system);
    }

    private void showLanguageDialog() {
        String currentLang = LocaleHelper.getSavedLanguage(this);
        String[] names = {
                getString(R.string.lang_system),
                "简体中文",
                "繁體中文",
                "English"
        };
        String[] codes = {
                LocaleHelper.LANG_SYSTEM,
                LocaleHelper.LANG_ZH,
                LocaleHelper.LANG_ZH_TW,
                LocaleHelper.LANG_EN
        };
        int checkedIndex = 0;
        for (int i = 0; i < codes.length; i++) {
            if (codes[i].equals(currentLang)) { checkedIndex = i; break; }
        }
        final int[] selected = {checkedIndex};
        new AlertDialog.Builder(this)
                .setTitle(R.string.language)
                .setSingleChoiceItems(names, checkedIndex, (dialog, which) -> selected[0] = which)
                .setPositiveButton(R.string.confirm_btn, (dialog, which) -> {
                    String newLang = codes[selected[0]];
                    if (!newLang.equals(currentLang)) {
                        LocaleHelper.setLocale(this, newLang);
                        recreate();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void showClearListConfirmation() {
        new AlertDialog.Builder(this).setTitle(R.string.clear_list).setMessage(R.string.confirm_clear_list).setPositiveButton(R.string.confirm_delete, (dialog, which) -> {
            tagManager.clearAllSongs();
            allSongs.clear(); currentSongList.clear(); currentSongIndex = -1; isPlaying = false; musicPlayer.stop();
            songAdapter.updateSongs(currentSongList);
            textSongInfo.setText(R.string.no_song_playing);
            updatePlayPauseUI();
            if (musicService != null) musicService.notifyPlaybackStopped();
            updateTagSpinner(); updateDirectoryDisplay();
        }).setNegativeButton(R.string.cancel, null).show();
    }

    private void selectMusicFolder() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(intent, REQUEST_FOLDER_ACCESS);
        } catch (Exception e) { Toast.makeText(this, R.string.cannot_open_selector, Toast.LENGTH_SHORT).show(); }
    }

    private void refreshSongList() {
        // 第一步：清理已不存在的文件（增量更新，绝对不破坏现有顺序）
        List<Song> toRemove = new ArrayList<>();
        for (Song s : allSongs) {
            if (!new File(s.getPath()).exists()) {
                tagManager.deleteSong(s.getId());
                toRemove.add(s);
            }
        }
        
        // 从内存列表中同步移除，保留剩余项的相对位置
        allSongs.removeAll(toRemove);
        currentSongList.removeAll(toRemove);

        // 第二步：异步扫描新歌
        scanForNewSongsAsync();
        
        if (!toRemove.isEmpty()) {
            Toast.makeText(this, getString(R.string.removed_invalid_songs, toRemove.size()), Toast.LENGTH_SHORT).show();
        }
    }
    private void scanForNewSongsAsync() {
        new Thread(() -> {
            List<Song> newSongs = scanForNewSongs();
            if (newSongs.isEmpty()) {
                runOnUiThread(() -> {
                    swipeRefresh.setRefreshing(false);
                    Toast.makeText(this, R.string.no_new_songs, Toast.LENGTH_SHORT).show();
                });
                return;
            }

            allSongs.addAll(newSongs);
            String query = editSearch.getText().toString().toLowerCase().trim();
            
            runOnUiThread(() -> {
                for (Song s : newSongs) {
                    if (query.isEmpty() || s.getTitle().toLowerCase().contains(query)) {
                        currentSongList.add(s);
                    }
                }
                songAdapter.updateSongs(currentSongList);
                updateTagSpinner();
                swipeRefresh.setRefreshing(false);
                Toast.makeText(this, getString(R.string.found_new_songs, newSongs.size()), Toast.LENGTH_SHORT).show();
            });
        }).start();
    }

    private List<Song> scanForNewSongs() {
        List<Song> found = new ArrayList<>();
        List<String> existingPaths = new ArrayList<>();
        for (Song s : allSongs) existingPaths.add(s.getPath());

        String d = directoryManager.getMusicDirectory();
        if (d != null && !d.isEmpty()) {
            // 有自定义目录：只扫描根目录下的音频文件（不递归），子目录属于分类
            File dir = new File(d);
            File[] files = dir.listFiles();
            if (files != null) {
                for (File entry : files) {
                    if (!entry.isFile()) continue;
                    String ln = entry.getName().toLowerCase();
                    if (!ln.endsWith(".mp3") && !ln.endsWith(".flac") && !ln.endsWith(".wav") && !ln.endsWith(".aac") && !ln.endsWith(".m4a") && !ln.endsWith(".ogg")) continue;
                    String p = entry.getAbsolutePath();
                    if (existingPaths.contains(p)) continue;
                    Song s = new Song();
                    s.setPath(p);
                    s.setTitle(Song.formatTitleFromPath(p));
                    updateMetadataFromFile(s);
                    s.setCategory("音乐");
                    s.setId(tagManager.addSong(s));
                    found.add(s);
                }
            }
            // 同时扫描子目录中的音频文件，以子目录名称为分类
            File[] subdirs = dir.listFiles(File::isDirectory);
            if (subdirs != null) {
                for (File subdir : subdirs) {
                    if (subdir.getName().startsWith(".")) continue;
                    scanCategoryDirectory(subdir, subdir.getName(), found, existingPaths);
                }
            }
        } else {
            // 无自定义目录：从 MediaStore 递归扫描所有音频文件
            Uri uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
            Cursor c = getContentResolver().query(uri, new String[]{MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.DURATION}, MediaStore.Audio.Media.IS_MUSIC + " != 0", null, null);
            if (c != null) {
                while (c.moveToNext()) {
                    String p = c.getString(0);
                    if (!existingPaths.contains(p)) {
                        Song s = new Song(); s.setPath(p); s.setTitle(Song.formatTitleFromPath(p));
                        updateMetadataFromFile(s);
                        s.setCategory("音乐");
                        s.setDuration(c.getLong(1));
                        s.setId(tagManager.addSong(s));

                        found.add(s);
                    }
                }
                c.close();
            }
        }
        return found;
    }

    /** 扫描某个分类子目录下的所有音频文件（递归该子目录内部） */
    private void scanCategoryDirectory(File categoryDir, String categoryName, List<Song> found, List<String> existingPaths) {
        File[] entries = categoryDir.listFiles();
        if (entries == null) return;
        for (File entry : entries) {
            if (entry.isDirectory()) {
                if (entry.getName().startsWith(".")) continue;
                scanCategoryDirectory(entry, categoryName, found, existingPaths);
            } else if (entry.isFile()) {
                String ln = entry.getName().toLowerCase();
                if (!ln.endsWith(".mp3") && !ln.endsWith(".flac") && !ln.endsWith(".wav") && !ln.endsWith(".aac") && !ln.endsWith(".m4a") && !ln.endsWith(".ogg")) continue;
                String p = entry.getAbsolutePath();
                if (existingPaths.contains(p)) continue;
                Song s = new Song();
                s.setPath(p);
                s.setTitle(Song.formatTitleFromPath(p));
                updateMetadataFromFile(s);
                s.setCategory(categoryName);
                s.setId(tagManager.addSong(s));
                tagManager.addCategoryTag(categoryName);
                found.add(s);
            }
        }
    }

    private void updateMetadataFromFile(Song s) {
        try {
            AudioFile af = AudioFileIO.read(new File(s.getPath()));
            org.jaudiotagger.tag.Tag t = af.getTag();
            if (t != null) {
                String art = t.getFirst(FieldKey.ARTIST);
                s.setArtist(art != null && !art.contains("<unknown>") ? art : "");
                s.setAlbum(t.getFirst(FieldKey.ALBUM));
                
                // 扫描时同步载入内嵌歌词到数据库缓存
                String lrc = t.getFirst(FieldKey.LYRICS);
                if (lrc != null && !lrc.trim().isEmpty()) {
                    s.setLyrics(lrc);
                }
            }
            
            // 分类由扫描逻辑根据目录结构确定，此处只读取元数据
            s.setCategory("音乐");
        } catch (Exception e) { 
            s.setArtist(""); 
            s.setAlbum(""); 
            s.setCategory("音乐"); 
        }
    }

    private void playSongManually(Song s, int p) {
        consecutiveFailures = 0;
        currentSongIndex = p;
        shouldResumeFromSavedPosition = false;
        playSong(s);
    }

    /** 完整播放：更新 UI + 启动播放 + 触发预加载 */
    private void playSong(Song s) {
        if (s == null) return;
        updateSongUI(s);
        startPlayback(s);
    }

    /** 更新所有与当前歌曲相关的 UI 状态（不触发播放） */
    private void updateSongUI(Song s) {
        // 保存上一次播放歌曲的进度（仅非音乐类写入数据库）
        if (lastPlayedSong != null && musicPlayer.isPrepared()) {
            int pos = musicPlayer.getCurrentPosition();
            String lastCat = lastPlayedSong.getCategory();
            if (lastCat != null && !lastCat.equals("音乐")) {
                tagManager.updateLastPosition(lastPlayedSong.getId(), pos);
            }
            lastPlayedSong.setLastPosition(pos);
        }
        lastPlayedSong = s;

        songAdapter.setSelectedPosition(currentSongIndex);
        recyclerSongs.scrollToPosition(currentSongIndex);

        // 断点续播
        String category = s.getCategory();
        if (category != null && !category.equals("音乐") && s.getLastPosition() > 0) {
            pendingSeekPosition = s.getLastPosition();
            Toast.makeText(this, R.string.resumed_position, Toast.LENGTH_SHORT).show();
        } else if (shouldResumeFromSavedPosition && s.getLastPosition() > 0) {
            pendingSeekPosition = s.getLastPosition();
        }
        shouldResumeFromSavedPosition = false;

        String art = s.getArtist();
        if (art == null || art.isEmpty() || art.contains("<unknown>")) art = "";
        textSongInfo.setText(s.getTitle() + (art.isEmpty() ? "" : " - " + art));
        textFullTitle.setText(s.getTitle());
        textFullArtist.setText(art);
        textFullArtist.setVisibility(art.isEmpty() ? View.INVISIBLE : View.VISIBLE);
        textFullPlayingBarTitle.setText(s.getTitle() + (art.isEmpty() ? "" : " - " + art));

        // 歌词加载耗时较长（大 FLAC 文件可达数秒），放后台执行，避免阻塞 UI 更新
        final String lrcPath = s.getPath();
        new Thread(() -> {
            final List<LrcLine> lines = loadLyricsSync(lrcPath);
            runOnUiThread(() -> lrcAdapter.setLrcLines(lines));
        }).start();

        isPlaying = true;
        updateMediaSessionMetadata(s);
        updateMediaSessionState();
        updatePlayPauseUI();
        // 通知 Service 更新前台通知
        if (musicService != null) {
            musicService.notifyPlaybackStarted(s.getTitle(), art);
        }
    }

    /** 同步加载歌词（可在后台线程调用） */
    private List<LrcLine> loadLyricsSync(String path) {
        // 0. 优先加载内存/数据库中的歌词缓存
        if (currentSongIndex >= 0 && currentSongIndex < currentSongList.size()) {
            Song s = currentSongList.get(currentSongIndex);
            if (s.getLyrics() != null && !s.getLyrics().trim().isEmpty()) {
                return parseLrcContent(s.getLyrics());
            }
        }
        String embed = extractLyricsFromMetadata(path);
        if (embed != null && !embed.trim().isEmpty()) return parseLrcContent(embed);
        int dot = path.lastIndexOf(".");
        if (dot != -1) {
            File lf = new File(path.substring(0, dot) + ".lrc");
            if (lf.exists()) return parseLrcFile(lf);
        }
        return new ArrayList<>();
    }

    /** 启动播放并触发下一首预加载 */
    private void startPlayback(Song s) {
        musicPlayer.play(this, s.getPath());
        triggerPreloadNext();
    }

    /** 预加载列表中的下一首歌曲（当前列表顺序） */
    private void triggerPreloadNext() {
        if (currentSongIndex >= 0 && currentSongIndex < currentSongList.size() - 1) {
            Song next = currentSongList.get(currentSongIndex + 1);
            musicPlayer.preloadNext(next.getPath());
        }
    }

    private void loadLyrics(String path) {
        List<LrcLine> lines = loadLyricsSync(path);
        lrcAdapter.setLrcLines(lines);
    }

    private String extractLyricsFromMetadata(String path) {
        try {
            AudioFile af = AudioFileIO.read(new File(path));
            org.jaudiotagger.tag.Tag t = af.getTag();
            if (t != null) {
                String l = t.getFirst(FieldKey.LYRICS);
                if (l != null && !l.trim().isEmpty()) return l;
                Iterator<TagField> it = t.getFields();
                while (it.hasNext()) {
                    TagField f = it.next();
                    String raw = f.toString();
                    if (raw.toUpperCase().contains("LYRIC") || raw.toUpperCase().contains("LRC")) {
                        int start = raw.indexOf("Text=\"");
                        if (start != -1) {
                            int end = raw.lastIndexOf("\"");
                            if (end > start + 6) {
                                String val = raw.substring(start + 6, end);
                                if (val.contains("[") && val.contains("]")) return val;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {}
        return null;
    }

    private List<LrcLine> parseLrcFile(File f) {
        try {
            StringBuilder sb = new StringBuilder();
            BufferedReader br = new BufferedReader(new FileReader(f));
            String l;
            while ((l = br.readLine()) != null) sb.append(l).append("\n");
            br.close();
            return parseLrcContent(sb.toString());
        } catch (Exception e) { return new ArrayList<>(); }
    }

    private List<LrcLine> parseLrcContent(String c) {
        List<LrcLine> lines = new ArrayList<>();
        if (c == null || c.isEmpty()) return lines;
        Pattern p = Pattern.compile("\\[(\\d+):(\\d+)(?:[.:](\\d+))?](.*)");
        for (String line : c.split("\n")) {
            Matcher m = p.matcher(line);
            if (m.find()) {
                long min = Long.parseLong(m.group(1));
                long sec = Long.parseLong(m.group(2));
                long ms = 0;
                if (m.group(3) != null) { ms = Long.parseLong(m.group(3)); if (m.group(3).length() == 2) ms *= 10; }
                String txt = (m.group(4) != null) ? m.group(4).trim() : "";
                if (!txt.isEmpty()) lines.add(new LrcLine((min * 60 + sec) * 1000 + ms, txt));
            }
        }
        Collections.sort(lines);
        return lines;
    }

    private String formatTime(int ms) {
        int s = (ms / 1000) % 60; int m = (ms / (1000 * 60)) % 60;
        return String.format(Locale.CHINA, "%02d:%02d", m, s);
    }

    private void togglePlayPause() {
        if (isPlaying) { 
            musicPlayer.pause(); 
            isPlaying = false; 
            updatePlayPauseUI(); 
            if (musicService != null) musicService.notifyPlaybackPaused();
        }
        else {
            if (currentSongIndex >= 0 && musicPlayer.isPrepared()) { 
                musicPlayer.resume(); 
                isPlaying = true; 
                updatePlayPauseUI(); 
                if (musicService != null) musicService.notifyPlaybackResumed();
            }
            else if (!currentSongList.isEmpty()) playSong(currentSongList.get(Math.max(0, currentSongIndex)));
        }
        updateMediaSessionState();
    }

    private void playPrevious() {
        if (!currentSongList.isEmpty()) {
            musicPlayer.cancelPreload();
            consecutiveFailures = 0;
            currentSongIndex = (currentSongIndex - 1 + currentSongList.size()) % currentSongList.size();
            playSong(currentSongList.get(currentSongIndex));
        }
    }
    private void playNext() { 
        if (currentSongList.isEmpty()) {
            isPlaying = false; 
            updatePlayPauseUI();
            if (musicService != null) musicService.notifyPlaybackStopped();
            return;
        }

        // 智能逻辑：
        // 如果是评书/相声，强制执行列表顺序播放（已经在 filterSongs 中按名称排过序了）
        // 如果是音乐类，也按当前列表顺序播放（如果点过随机，列表已经是随机序了）
        currentSongIndex = (currentSongIndex + 1) % currentSongList.size();
        Song next = currentSongList.get(currentSongIndex);

        // 优先尝试 swap 预加载（零等待）
        int swapDuration = musicPlayer.trySwapToPreloaded(next.getPath());
        if (swapDuration >= 0) {
            // swap 成功：更新 UI（不经过 onPrepared，避免冗余操作）
            updateSongUI(next);
            seekProgress.setMax(swapDuration);
            seekMiniProgress.setMax(swapDuration);
            textTotalTime.setText(formatTime(swapDuration));
            consecutiveFailures = 0;
            updateMediaSessionState();
            triggerPreloadNext(); // 预加载下下首
        } else {
            playSong(next); // 常规流程：UI + play + preload
        }
    }

    private void openTagPlayer() { startActivityForResult(new Intent(this, TagPlayerActivity.class), REQUEST_TAG_PLAYER); }
    private void shuffleSongs() {
        if (!allSongs.isEmpty()) {
            // 关键修复：直接打乱真相源 allSongs
            TrueRandomShuffler.shuffle(allSongs);
            
            // 持久化排序结果到数据库
            tagManager.updateSongsOrder(allSongs);
            
            // 立即重新执行当前过滤逻辑（无论是全部还是某个标签），保持打乱后的新顺序
            filterSongs(editSearch.getText().toString());
            
            // 播放新顺序下的第一首
            if (!currentSongList.isEmpty()) {
                currentSongIndex = 0;
                playSong(currentSongList.get(0));
            }
            Toast.makeText(this, R.string.shuffle_saved, Toast.LENGTH_SHORT).show();
        }
    }

    private void showManageTagsDialog() {
        List<Tag> tags = tagManager.getUserTags();
        String[] tagNames = new String[tags.size()];
        for (int i = 0; i < tags.size(); i++) tagNames[i] = tags.get(i).getName();
        new AlertDialog.Builder(this).setTitle(R.string.manage_tags).setItems(tagNames, (dialog, which) -> showTagOptionsDialog(tags.get(which))).setNeutralButton(R.string.add_tag, (dialog, which) -> showCreateTagDialog()).setNegativeButton(R.string.cancel, null).show();
    }

    private void showCreateTagDialog() {
        final EditText et = new EditText(this);
        et.setHint(R.string.tag_name_hint);
        new AlertDialog.Builder(this).setTitle(R.string.add_tag).setView(et).setPositiveButton(R.string.confirm_btn, (dialog, which) -> {
            String n = et.getText().toString().trim();
            if (!n.isEmpty()) { tagManager.addTag(n); updateTagSpinner(); }
        }).setNegativeButton(R.string.cancel, null).show();
    }

    private void showTagOptionsDialog(Tag t) {
        String[] opts = { getString(R.string.shuffle_tag) + " \"" + t.getName() + "\"", getString(R.string.delete_tag) };
        new AlertDialog.Builder(this).setTitle(getString(R.string.tag_label, t.getName())).setItems(opts, (dialog, which) -> {
            if (which == 0) { isTagFiltered = true; showSongsForTag(t.getId()); if (!currentSongList.isEmpty()) shuffleSongs(); }
            else if (which == 1) showDeleteTagDialog(t);
        }).setNegativeButton(R.string.cancel, null).show();
    }

    private void showDeleteTagDialog(Tag t) {
        new AlertDialog.Builder(this).setTitle(R.string.confirm_delete).setMessage(R.string.confirm_delete_tag).setPositiveButton(R.string.confirm_delete, (dialog, which) -> {
            tagManager.deleteTag(t.getId()); updateTagSpinner(); updateSongList();
        }).setNegativeButton(R.string.cancel, null).show();
    }

    private void showSongOptionsDialog(Song s) {
        String ext = "";
        int dot = s.getPath().lastIndexOf(".");
        if (dot != -1) ext = s.getPath().substring(dot + 1).toUpperCase();
        
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        android.content.Context dialogContext = builder.getContext();
        float density = dialogContext.getResources().getDisplayMetrics().density;
        
        android.widget.LinearLayout header = new android.widget.LinearLayout(dialogContext);
        header.setOrientation(android.widget.LinearLayout.VERTICAL);
        header.setPadding((int)(24 * density), (int)(20 * density), (int)(24 * density), (int)(8 * density));
        
        TextView title = new TextView(dialogContext);
        title.setText(s.getTitle());
        title.setTextSize(18);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        
        // 彻底解决颜色对比度问题：使用更稳健的主题属性读取逻辑
        android.util.TypedValue tv = new android.util.TypedValue();
        int titleColor;
        if (dialogContext.getTheme().resolveAttribute(android.R.attr.textColorPrimary, tv, true)) {
            titleColor = (tv.resourceId != 0) ? ContextCompat.getColor(dialogContext, tv.resourceId) : tv.data;
        } else {
            titleColor = android.graphics.Color.WHITE; // 针对深色模式的保底
        }
        title.setTextColor(titleColor);
        
        TextView info = new TextView(dialogContext);
        String art = s.getArtist();
        if (art == null || art.isEmpty() || art.contains("<unknown>")) art = getString(R.string.unknown_artist);
        info.setText(art + " • " + ext);
        info.setTextSize(13);
        info.setPadding(0, (int)(4 * density), 0, 0);
        
        int infoColor;
        if (dialogContext.getTheme().resolveAttribute(android.R.attr.textColorSecondary, tv, true)) {
            infoColor = (tv.resourceId != 0) ? ContextCompat.getColor(dialogContext, tv.resourceId) : tv.data;
        } else {
            infoColor = android.graphics.Color.LTGRAY;
        }
        info.setTextColor(infoColor);
        
        header.addView(title);
        header.addView(info);

        String[] opts = { getString(R.string.edit_info), getString(R.string.edit_tags), getString(R.string.delete_song) };
        builder.setCustomTitle(header)
            .setItems(opts, (dialog, which) -> {
                if (which == 0) showEditSongInfoDialog(s);
                else if (which == 1) showTagSongDialog(s);
                else if (which == 2) showDeleteSongDialog(s);
            })
            .setNegativeButton(R.string.cancel, null)
            .show();
    }

    private void showEditSongInfoDialog(Song s) {
        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        layout.setPadding(50, 40, 50, 10);

        final EditText editArtist = new EditText(this);
        editArtist.setHint(R.string.artist_hint);
        editArtist.setText(s.getArtist());
        
        TextView labelLyrics = new TextView(this);
        labelLyrics.setText("\n" + getString(R.string.lyrics_label));
        labelLyrics.setTextSize(14);
        
        final EditText editLyrics = new EditText(this);
        editLyrics.setHint(R.string.lyrics_hint);
        editLyrics.setText(s.getLyrics());
        editLyrics.setMinLines(5);
        editLyrics.setGravity(android.view.Gravity.TOP);

        layout.addView(new TextView(this){{setText(R.string.artist_label);}});
        layout.addView(editArtist);
        layout.addView(labelLyrics);
        layout.addView(editLyrics);

        new AlertDialog.Builder(this)
            .setTitle(R.string.edit_info)
            .setView(layout)
            .setPositiveButton(R.string.sync_to_file, (dialog, which) -> {
                // Android 11+ 权限检查
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    if (!android.os.Environment.isExternalStorageManager()) {
                        showManageStoragePermissionDialog();
                        return;
                    }
                }

                String newArtist = editArtist.getText().toString().trim();
                String newLyrics = editLyrics.getText().toString().trim();
                
                // 先更新内存和数据库（保证数据不丢失）
                s.setArtist(newArtist);
                s.setLyrics(newLyrics);
                tagManager.updateSongMetadata(s.getId(), newArtist, newLyrics);
                
                // 如果当前正在播放这首歌，刷新歌词显示
                if (lastPlayedSong != null && lastPlayedSong.getId() == s.getId()) {
                    loadLyrics(s.getPath());
                }
                
                updateSongList();
                
                // 尝试安全写入音频文件
                saveMetadataToFile(s, newArtist, newLyrics);
            })
            .setNeutralButton(R.string.save_only, (dialog, which) -> {
                String newArtist = editArtist.getText().toString().trim();
                String newLyrics = editLyrics.getText().toString().trim();
                
                s.setArtist(newArtist);
                s.setLyrics(newLyrics);
                tagManager.updateSongMetadata(s.getId(), newArtist, newLyrics);
                
                if (lastPlayedSong != null && lastPlayedSong.getId() == s.getId()) {
                    loadLyrics(s.getPath());
                }
                
                updateSongList();
                Toast.makeText(this, R.string.info_saved, Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton(R.string.cancel, null)
            .show();
    }

    private void showManageStoragePermissionDialog() {
        new AlertDialog.Builder(this)
            .setTitle(R.string.need_file_permission)
            .setMessage(R.string.file_permission_message)
            .setPositiveButton(R.string.go_enable, (dialog, which) -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    try {
                        Intent permIntent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                        permIntent.addCategory("android.intent.category.DEFAULT");
                        permIntent.setData(Uri.parse(String.format("package:%s", getPackageName())));
                        startActivity(permIntent);
                    } catch (Exception e) {
                        Intent permIntent = new Intent();
                        permIntent.setAction(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                        startActivity(permIntent);
                    }
                }
            })
            .setNegativeButton(R.string.later, null)
            .show();
    }

    private void saveMetadataToFile(Song s, String artist, String lyrics) {
        // 先停止播放器（不释放！stop()只做 reset，MediaPlayer 仍可复用）
        if (musicPlayer != null) {
            musicPlayer.stop();
        }

        new Thread(() -> {
            try {
                // 等待文件句柄释放
                Thread.sleep(500);

                // 安全设置：setAndroid 帮助处理 Android 文件系统兼容性
                // 默认不使用 setNoBackup，让 jaudiotagger 创建备份文件，防止写入中断时损坏
                org.jaudiotagger.tag.TagOptionSingleton.getInstance().setAndroid(true);
                
                File file = new File(s.getPath());
                AudioFile af = AudioFileIO.read(file);
                
                org.jaudiotagger.tag.Tag t = af.getTag();
                if (t == null) {
                    t = af.createDefaultTag();
                    af.setTag(t);
                }
                
                t.deleteField(FieldKey.ARTIST);
                t.setField(FieldKey.ARTIST, artist);
                
                if (lyrics != null && !lyrics.trim().isEmpty()) {
                    t.deleteField(FieldKey.LYRICS);
                    t.setField(FieldKey.LYRICS, lyrics);
                } else {
                    t.deleteField(FieldKey.LYRICS);
                }

                // 写入文件（jaudiotagger 会先创建备份，再覆写原文件）
                af.commit();
                
                // 写入后验证文件完整性
                try {
                    AudioFile verify = AudioFileIO.read(file);
                    if (verify != null && verify.getAudioHeader() != null) {
                        runOnUiThread(() -> Toast.makeText(this, R.string.info_synced, Toast.LENGTH_SHORT).show());
                    } else {
                        runOnUiThread(() -> Toast.makeText(this, R.string.file_verify_failed_msg, Toast.LENGTH_LONG).show());
                    }
                } catch (Exception ve) {
                    runOnUiThread(() -> new AlertDialog.Builder(this)
                        .setTitle(R.string.file_verify_failed)
                        .setMessage(R.string.file_corrupted_msg)
                        .setPositiveButton(R.string.confirm_btn, null)
                        .show());
                }
                
            } catch (Exception e) {
                final String msg = e.getMessage();
                runOnUiThread(() -> new AlertDialog.Builder(this)
                    .setTitle(R.string.file_sync_failed)
                    .setMessage(getString(R.string.file_sync_failed_msg, msg != null ? msg : "unknown"))
                    .setPositiveButton(R.string.confirm_btn, null)
                    .show());
            }
        }).start();
    }

    private void showTagSongDialog(Song s) {
        List<Tag> all = tagManager.getUserTags();
        if (all.isEmpty()) { Toast.makeText(this, R.string.create_tag_first, Toast.LENGTH_SHORT).show(); return; }
        List<String> cur = tagManager.getTagsForSong(s.getId());
        String[] ns = new String[all.size()];
        boolean[] ck = new boolean[all.size()];
        for (int i = 0; i < all.size(); i++) { ns[i] = all.get(i).getName(); ck[i] = cur.contains(all.get(i).getName()); }
        new AlertDialog.Builder(this).setTitle(R.string.select_tags).setMultiChoiceItems(ns, ck, (dialog, which, isChecked) -> ck[which] = isChecked).setPositiveButton(R.string.confirm_btn, (dialog, which) -> {
            for (int i = 0; i < all.size(); i++) { if (ck[i]) tagManager.addTagToSong(s.getId(), all.get(i).getId()); else tagManager.removeTagFromSong(s.getId(), all.get(i).getId()); }
            // 实时更新当前歌曲的内存标签列表，确保过滤逻辑准确
            s.setTags(tagManager.getTagsForSong(s.getId()));
            updateSongList(); // 立即重新执行当前过滤
            updateTagSpinner(); // 立即刷新下拉框中的数字
        }).setNeutralButton(R.string.add_tag, (dialog, which) -> showCreateTagDialog()).setNegativeButton(R.string.cancel, null).show();
    }

    private void showDeleteSongDialog(Song s) {
        new AlertDialog.Builder(this).setTitle(R.string.confirm_delete).setMessage(getString(R.string.delete_message) + "\n\n歌曲: " + s.getTitle()).setPositiveButton(R.string.confirm_delete, (dialog, which) -> {
            tagManager.deleteSong(s.getId()); allSongs.remove(s); currentSongList.remove(s); 
            songAdapter.updateSongs(currentSongList);
            updateTagSpinner();
        }).setNegativeButton(R.string.cancel, null).show();
    }

    private void showSongsForTag(long id) {
        currentSongList.clear();
        currentSongList.addAll(tagManager.getSongsForTag(id));
        songAdapter.updateSongs(currentSongList);
        selectTagInSpinner(id);
    }

    @SuppressWarnings("unchecked")
    private void selectTagInSpinner(long id) {
        List<Tag> allTags = tagManager.getAllTags();
        String targetName = "";
        String targetType = "none";
        if (id != -1) {
            for (Tag t : allTags) {
                if (t.getId() == id) {
                    targetName = t.getName();
                    // 判断是分类还是标签
                    if ("category".equals(t.getType())) {
                        targetType = "category";
                    } else {
                        targetType = "tag";
                    }
                    break;
                }
            }
        }
        
        selectedCategory = targetName;
        selectedFilterType = targetType;
        isTagFiltered = !"none".equals(targetType);
        updateTagSpinner();
    }

    @Override
    protected void onActivityResult(int request, int result, @Nullable Intent data) {
        super.onActivityResult(request, result, data);
        if (request == REQUEST_FOLDER_ACCESS && result == RESULT_OK && data != null) {
            String path = getPathFromUri(data.getData());
            if (path != null) { directoryManager.setMusicDirectory(path); updateDirectoryDisplay(); setupDirectoryObserver(); refreshSongList(); }
        } else if (request == REQUEST_TAG_PLAYER) {
            if (result == TagPlayerActivity.RESULT_DISCOVER) {
                // 从标签播放页返回并切换到发现页
                bottomNavigation.setSelectedItemId(R.id.nav_discovery);
            } else if (result == RESULT_OK && data != null) {
                long id = data.getLongExtra("tagId", -1);
                if (id != -1) { selectTagInSpinner(id); if (!currentSongList.isEmpty()) shuffleSongs(); }
            }
        }
    }

    private String getPathFromUri(Uri uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && DocumentsContract.isTreeUri(uri)) {
            String id = DocumentsContract.getTreeDocumentId(uri);
            if (id.startsWith("primary:")) return "/storage/emulated/0/" + id.substring(8);
            if (id.contains(":")) { String[] p = id.split(":"); if (p.length >= 2) return "/storage/" + p[0] + "/" + p[1]; }
        }
        return null;
    }

    private void initMediaSession() {
        if (mediaSession == null) return;
        mediaSession.setCallback(new MediaSession.Callback() {
                @Override
                public void onPlay() { togglePlayPause(); }
                @Override
                public void onPause() { togglePlayPause(); }
                @Override
                public void onSkipToNext() { consecutiveFailures = 0; playNext(); }
                @Override
                public void onSkipToPrevious() { playPrevious(); }
                @Override
                public void onStop() { musicPlayer.stop(); isPlaying = false; updateMediaSessionState(); }
                @Override
                public void onSeekTo(long pos) { musicPlayer.seekTo((int)pos); }
            });
        mediaSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mediaSession.setActive(true);
    }

    private void updateMediaSessionMetadata(Song s) {
        if (mediaSession == null || s == null) return;
        try {
            MediaMetadata.Builder builder = new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, s.getTitle() != null ? s.getTitle() : getString(R.string.unknown_song))
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, s.getArtist() != null ? s.getArtist() : "")
                    .putString(MediaMetadata.METADATA_KEY_ALBUM, s.getAlbum() != null ? s.getAlbum() : "")
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, s.getDuration());
            
            // 安全读取封面
            try {
                AudioFile af = AudioFileIO.read(new File(s.getPath()));
                org.jaudiotagger.tag.Tag t = af.getTag();
                if (t != null && t.getFirstArtwork() != null) {
                    byte[] data = t.getFirstArtwork().getBinaryData();
                    if (data != null && data.length > 0) {
                        Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length);
                        if (bitmap != null) {
                            builder.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, bitmap);
                        }
                    }
                }
            } catch (Exception e) {
                // 封面读取失败不应导致闪退
            }
            mediaSession.setMetadata(builder.build());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void updateMediaSessionState() {
        if (mediaSession == null) return;
        try {
            int state = isPlaying ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED;
            long position = 0;
            if (musicPlayer != null && musicPlayer.isPrepared()) {
                position = musicPlayer.getCurrentPosition();
            }
            
            PlaybackState.Builder stateBuilder = new PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE |
                            PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS |
                            PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_STOP | PlaybackState.ACTION_SEEK_TO)
                    .setState(state, position, 1.0f);
            mediaSession.setPlaybackState(stateBuilder.build());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        savePlaybackState();
    }

    @Override
    protected void onStop() {
        super.onStop();
        savePlaybackState();
    }

    @Override
    protected void onDestroy() { 
        savePlaybackState();

        // 解绑 Service（不释放 MusicPlayer，它属于 Service）
        if (serviceBound) {
            if (musicService != null) musicService.setCallback(null);
            try { unbindService(serviceConnection); } catch (Exception ignored) {}
            serviceBound = false;
        }
        // 如果未在播放，通知 Service 可以停止
        if (musicService != null && !isPlaying) {
            musicService.notifyPlaybackStopped();
            musicService.stopSelf();
        }
        musicService = null;

        super.onDestroy(); 
        if (directoryObserver != null) {
            directoryObserver.stopWatching();
            directoryObserver = null;
        }
        // 耳机断开监听已迁移到 Service，此处不再需要注销
        if (mediaSession != null) {
            // MediaSession 属于 Service，Activity 不再释放
        }
        // MusicPlayer 属于 Service，Activity 不再释放
    }

    private void setupDirectoryObserver() {
        // 先停止旧的监听器
        if (directoryObserver != null) {
            directoryObserver.stopWatching();
            directoryObserver = null;
        }
        
        String dirPath = directoryManager.getMusicDirectory();
        if (dirPath == null || dirPath.isEmpty()) return;
        
        final File watchDir = new File(dirPath);
        if (!watchDir.exists() || !watchDir.isDirectory()) return;
        
        directoryObserver = new android.os.FileObserver(watchDir.getAbsolutePath(),
                android.os.FileObserver.CREATE | android.os.FileObserver.DELETE |
                android.os.FileObserver.MOVED_FROM | android.os.FileObserver.MOVED_TO) {
            @Override
            public void onEvent(int event, String name) {
                if (name == null) return;
                // 只关注目录的创建/删除/移动事件
                int mask = event & android.os.FileObserver.ALL_EVENTS;
                if (mask == android.os.FileObserver.CREATE || mask == android.os.FileObserver.DELETE ||
                    mask == android.os.FileObserver.MOVED_FROM || mask == android.os.FileObserver.MOVED_TO) {
                    File changed = new File(watchDir, name);
                    if (changed.isDirectory() && !name.startsWith(".")) {
                        runOnUiThread(() -> {
                            updateCategoryBar();
                        });
                    }
                }
            }
        };
        directoryObserver.startWatching();
    }
}
