package com.musicplayer.app;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioManager;
import android.media.MediaMetadata;
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
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.SeekBar;
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
    private Button btnTagPlayer, btnShuffle, btnGoWeb;
    private ImageButton btnPlayPause, btnPrev, btnNext, btnSettings, btnCollapse, btnFullShuffle, btnList, btnWebHome, btnMiniPrev, btnMiniPlayPause, btnMiniNext, btnSearch;
    private EditText editSearch, editWebUrl;
    private SeekBar seekProgress, seekVolume;
    private ProgressBar seekMiniProgress, webProgress;
    private TextView textSongInfo, textCurrentDir, textFullTitle, textFullArtist, textFullPlayingBarTitle, textCurrentTime, textTotalTime;
    
    private View layoutHome, layoutDiscovery, miniPlayer, fullPlayer;
    private androidx.swiperefreshlayout.widget.SwipeRefreshLayout swipeRefresh;
    private android.widget.LinearLayout layoutCategories;
    private android.os.FileObserver directoryObserver;
    private BottomSheetBehavior<View> sheetBehavior;
    private com.google.android.material.bottomnavigation.BottomNavigationView bottomNavigation;
    private WebView webView;
    private com.musicplayer.app.adapter.LrcAdapter lrcAdapter;
    private RecyclerView recyclerLyrics;
    
    private final List<Song> allSongs = new ArrayList<>();
    private final List<Song> currentSongList = new ArrayList<>();
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
    private boolean isHomePlayerActive = false;
    
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
            musicPlayer = musicService.getHomePlayer();
            mediaSession = musicService.getMediaSession();
            serviceBound = true;

            musicService.setCallback(serviceCallback);

            initPlayer();
            initMediaSession();
            loadSongs();
            syncPlayerUI();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            serviceBound = false;
            musicService = null;
        }
    };

    private String selectedFilterType = "none";
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

        handleFirstLaunch();
        initViews();
        checkPermissions();
        setupDirectoryObserver();

        Intent serviceIntent = new Intent(this, MusicPlayerService.class);
        startService(serviceIntent);
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);

        checkBatteryOptimizations();
    }
    
    private void checkBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
            if (!pm.isIgnoringBatteryOptimizations(getPackageName())) {
                new AlertDialog.Builder(this)
                        .setTitle(R.string.battery_optimization_title)
                        .setMessage(R.string.battery_optimization_message)
                        .setPositiveButton(R.string.go_set, (dialog, which) -> {
                            try {
                                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                                intent.setData(Uri.parse("package:" + getPackageName()));
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
        
        songAdapter.setOnSongClickListener(this::playSongManually);
        songAdapter.setOnSongLongClickListener((song, position) -> showSongOptionsDialog(song));
        
        btnTagPlayer.setOnClickListener(v -> openTagPlayer());
        btnShuffle.setOnClickListener(v -> shuffleSongs());
        btnPlayPause.setOnClickListener(v -> togglePlayPause());
        btnPrev.setOnClickListener(v -> playPrevious());
        btnNext.setOnClickListener(v -> { consecutiveFailures = 0; playNext(); });
        btnSettings.setOnClickListener(v -> showSettingsDialog());
        
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
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { filterSongs(s.toString()); }
            @Override public void afterTextChanged(android.text.Editable s) {}
        });
        
        seekProgress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) { if (fromUser && musicPlayer != null) musicPlayer.seekTo(progress); }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        seekVolume.setMax(audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        seekVolume.setProgress(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC));
        seekVolume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0); }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
    }
    
    private void initBottomSheet() {
        View bottomSheetPlayer = findViewById(R.id.bottom_sheet_player);
        sheetBehavior = BottomSheetBehavior.from(bottomSheetPlayer);
        miniPlayer = findViewById(R.id.mini_player);
        fullPlayer = findViewById(R.id.full_player);
        btnMiniPlayPause = findViewById(R.id.btn_mini_play_pause);
        btnMiniPrev = findViewById(R.id.btn_mini_prev);
        btnMiniNext = findViewById(R.id.btn_mini_next);
        seekMiniProgress = findViewById(R.id.seek_mini_progress);
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
        btnMiniPrev.setOnClickListener(v -> playPrevious());
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
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) { v.loadUrl(r.getUrl().toString()); return true; }
            @Override public void onPageFinished(WebView v, String u) {
                super.onPageFinished(v, u);
                v.loadUrl("javascript:(function() { " +
                        "var m = document.querySelector('meta[name=\"viewport\"]');" +
                        "if (!m) { m = document.createElement('meta'); m.name = 'viewport'; document.getElementsByTagName('head')[0].appendChild(m); }" +
                        "m.setAttribute('content', 'width=1024, initial-scale=' + (window.innerWidth / 1024) + ', user-scalable=yes');" +
                        "})()");
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView v, int p) {
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
                String finalUrl = input.startsWith("http") ? input : "https://" + input;
                webView.loadUrl(finalUrl);
                ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(editWebUrl.getWindowToken(), 0);
            }
        });
        btnWebHome.setOnClickListener(v -> { webView.loadUrl("https://music.gdstudio.org/"); editWebUrl.setText("https://music.gdstudio.org/"); });
        webView.loadUrl("https://music.gdstudio.org/");
    }

    private void initPlayer() {
        if (musicService != null) {
            musicService.setHomePlaybackListener(new MusicPlayer.OnPlaybackListener() {
                @Override
                public void onPrepared() {
                    runOnUiThread(() -> {
                        int d = musicPlayer.getDuration();
                        seekProgress.setMax(d);
                        seekMiniProgress.setMax(d);
                        textTotalTime.setText(formatTime(d));
                        consecutiveFailures = 0;
                        updateMediaSessionState();
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
                        if (lastPlayedSong != null) {
                            String cat = lastPlayedSong.getCategory();
                            if (cat != null && !cat.equals("音乐")) tagManager.updateLastPosition(lastPlayedSong.getId(), 0);
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
                @Override public void onError(String e) { runOnUiThread(() -> Toast.makeText(MainActivity.this, e, Toast.LENGTH_SHORT).show()); }
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
    }

    private void updatePlayPauseUI() {
        btnPlayPause.setImageResource(isPlaying ? R.drawable.ic_pause_vector : R.drawable.ic_play_vector);
        btnMiniPlayPause.setImageResource(isPlaying ? R.drawable.ic_pause_vector : R.drawable.ic_play_vector);
    }

    private void updateDirectoryDisplay() {
        String d = directoryManager.getMusicDirectory();
        textCurrentDir.setText(d != null && !d.isEmpty() ? getString(R.string.current_directory) + d : getString(R.string.no_directory));
    }

    private void handleFirstLaunch() {
        android.content.SharedPreferences prefs = getSharedPreferences("app_prefs", MODE_PRIVATE);
        if (!prefs.getBoolean("first_launch_done", false) && !directoryManager.hasCustomDirectory()) {
            File musicDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MUSIC);
            if (musicDir != null && musicDir.exists()) directoryManager.setMusicDirectory(musicDir.getAbsolutePath());
        }
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
        if (requestCode == PERMISSION_REQUEST_CODE && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) refreshSongList();
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
            int currentPos = (musicPlayer != null && musicPlayer.isPrepared()) ? musicPlayer.getCurrentPosition() : 0;
            getSharedPreferences("player_prefs", MODE_PRIVATE).edit()
                .putLong("last_song_id", lastPlayedSong.getId())
                .putInt("last_position", currentPos)
                .apply();
            
            if (musicService != null) {
                String artStr = lastPlayedSong.getArtist();
                if (artStr == null || artStr.isEmpty() || artStr.contains("<unknown>")) artStr = "";
                musicService.updateLastPlayedPath(lastPlayedSong.getPath(), lastPlayedSong.getTitle(), artStr, currentPos);
            }

            String catVal = lastPlayedSong.getCategory();
            if (catVal != null && !catVal.equals("音乐")) {
                tagManager.updateLastPosition(lastPlayedSong.getId(), currentPos);
                lastPlayedSong.setLastPosition(currentPos);
            }
        }
    }

    private void restorePlaybackState() {
        android.content.SharedPreferences prefs = getSharedPreferences("player_prefs", MODE_PRIVATE);
        long lastId = prefs.getLong("last_song_id", -1);
        int lastPos = prefs.getInt("last_position", 0);

        if (lastId != -1) {
            for (Song s : allSongs) {
                if (s.getId() == lastId) {
                    lastPlayedSong = s;
                    break;
                }
            }
            
            if (lastPlayedSong != null) {
                lastPlayedSong.setLastPosition(lastPos);
                shouldResumeFromSavedPosition = true;
                
                currentSongIndex = -1;
                for (int i = 0; i < currentSongList.size(); i++) {
                    if (currentSongList.get(i).getId() == lastId) {
                        currentSongIndex = i;
                        break;
                    }
                }
                
                if (currentSongIndex == -1) {
                    for (int j = 0; j < allSongs.size(); j++) {
                        if (allSongs.get(j).getId() == lastId) {
                            currentSongIndex = j;
                            break;
                        }
                    }
                }
                
                String artistName = lastPlayedSong.getArtist();
                if (artistName == null || artistName.isEmpty() || artistName.contains("<unknown>")) artistName = "";
                textSongInfo.setText(getString(R.string.song_info_format, lastPlayedSong.getTitle(), artistName));
                textFullTitle.setText(lastPlayedSong.getTitle());
                textFullArtist.setText(artistName);
                textFullArtist.setVisibility(artistName.isEmpty() ? View.INVISIBLE : View.VISIBLE);
                textFullPlayingBarTitle.setText(getString(R.string.song_info_format, lastPlayedSong.getTitle(), artistName));
                
                seekProgress.setProgress(lastPos);
                seekMiniProgress.setProgress(lastPos);
                textCurrentTime.setText(formatTime(lastPos));
                
                songAdapter.setSelectedPosition(currentSongIndex);
            }
        }
    }

    private void filterSongs(String q) {
        String lq = q.toLowerCase().trim();
        List<Song> base = new ArrayList<>();
        try {
            if ("category".equals(selectedFilterType)) {
                for (Song s : allSongs) if (selectedCategory.equals(s.getCategory())) base.add(s);
            } else if ("tag".equals(selectedFilterType)) {
                long tid = tagManager.addTag(selectedCategory);
                List<Long> ids = tagManager.getSongIdsForTag(tid);
                for (Song s : allSongs) if (ids.contains(s.getId())) base.add(s);
            } else base.addAll(allSongs);
        } catch (Exception e) { base.addAll(allSongs); }
        currentSongList.clear();
        for (Song s : base) {
            if (lq.isEmpty() || (s.getTitle() != null && s.getTitle().toLowerCase().contains(lq)) || (s.getArtist() != null && s.getArtist().toLowerCase().contains(lq))) currentSongList.add(s);
        }
        songAdapter.updateSongs(currentSongList);
    }

    private void updateSongList() { filterSongs(editSearch.getText().toString()); }

    private void updateCategoryBar() {
        if (layoutCategories == null) return;
        try {
            layoutCategories.removeAllViews();
            addFilterChip(layoutCategories, getString(R.string.all_music), true, "none");
            String d = directoryManager.getMusicDirectory();
            if (d != null && !d.isEmpty()) {
                File[] sub = new File(d).listFiles(File::isDirectory);
                if (sub != null) {
                    java.util.Arrays.sort(sub, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
                    for (File s : sub) {
                        if (s.getName().startsWith(".")) continue;
                        addFilterChip(layoutCategories, s.getName(), false, "category");
                    }
                }
            }
        } catch (Exception e) {}
    }
    
    private void updateTagSpinner() {
        updateCategoryBar();
        if (btnTagPlayer != null) {
            boolean hasTags = !tagManager.getUserTags().isEmpty();
            btnTagPlayer.setEnabled(hasTags);
            btnTagPlayer.setAlpha(hasTags ? 1.0f : 0.4f);
        }
    }
    
    private void addFilterChip(android.widget.LinearLayout container, String name, boolean isAll, String filterType) {
        Button btn = new Button(new android.view.ContextThemeWrapper(this, com.google.android.material.R.style.Widget_MaterialComponents_Button_TextButton), null, 0);
        int count = isAll ? tagManager.getTotalSongCount() : ("category".equals(filterType) ? tagManager.getSongCountByCategory(name) : tagManager.getSongCountForTag(tagManager.addTag(name)));
        btn.setText(getString(R.string.filter_chip_format, name, count));
        btn.setTextSize(11);
        btn.setAllCaps(false);
        btn.setPadding(20, 0, 20, 0);
        btn.setMinWidth(0);
        btn.setMinimumWidth(0);
        int gray = ContextCompat.getColor(this, android.R.color.darker_gray), red = ContextCompat.getColor(this, R.color.colorPrimary);
        boolean isSelected = isAll ? "none".equals(selectedFilterType) : (selectedCategory.equals(name) && selectedFilterType.equals(filterType));
        btn.setTextColor(isSelected ? red : gray);
        btn.setOnClickListener(v -> {
            selectedCategory = name;
            selectedFilterType = filterType;
            updateTagSpinner();
            filterSongs(editSearch.getText().toString());
        });
        container.addView(btn);
    }

    private void showSettingsDialog() {
        int dp16 = (int)(16 * getResources().getDisplayMetrics().density), dp1 = (int)(1 * getResources().getDisplayMetrics().density), textColor = isDarkMode() ? 0xFFFFFFFF : 0xFF000000;
        android.widget.LinearLayout l = new android.widget.LinearLayout(this);
        l.setOrientation(android.widget.LinearLayout.VERTICAL);
        l.setPadding(0, dp16, 0, 0);
        java.util.function.Supplier<View> div = () -> {
            View v = new View(this);
            android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(-1, dp1);
            lp.setMargins(dp16 * 2, 0, dp16 * 2, 0);
            v.setLayoutParams(lp); v.setBackgroundColor(0x30000000); return v;
        };
        java.util.function.BiFunction<String, Runnable, TextView> item = (t, a) -> {
            TextView tv = new TextView(this); tv.setText(t); tv.setTextSize(16); tv.setTextColor(textColor);
            tv.setPadding(dp16, dp16, dp16, dp16); if (a != null) tv.setOnClickListener(v -> a.run()); return tv;
        };
        String cur = directoryManager.getMusicDirectory();
        if (cur != null && !cur.isEmpty()) {
            TextView tv = new TextView(this); tv.setText(getString(R.string.current_directory_prefix, cur));
            tv.setTextSize(14); tv.setTextColor(0xFF888888); tv.setPadding(dp16 * 2, dp16, dp16 * 2, dp16);
            l.addView(tv); l.addView(div.get());
        }
        android.widget.LinearLayout r = new android.widget.LinearLayout(this);
        TextView iF = item.apply(getString(R.string.select_folder), this::selectMusicFolder), iS = item.apply(getString(R.string.refresh), this::refreshSongList);
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(0, -2, 1f);
        iF.setLayoutParams(lp); iS.setLayoutParams(lp); iF.setGravity(17); iS.setGravity(17);
        r.addView(iF); View v = new View(this); v.setLayoutParams(new android.widget.LinearLayout.LayoutParams(dp1, -1));
        v.setBackgroundColor(0x30000000); r.addView(v); r.addView(iS); l.addView(r); l.addView(div.get());
        l.addView(item.apply(getString(R.string.manage_tags), this::showManageTagsDialog)); l.addView(div.get());
        l.addView(item.apply(getString(R.string.language_menu_item, getLanguageDisplayName(LocaleHelper.getSavedLanguage(this))), this::showLanguageDialog)); l.addView(div.get());
        l.addView(item.apply(getString(R.string.clear_list), this::showClearListConfirmation)); l.addView(div.get());
        l.addView(item.apply(getString(R.string.about_menu), () -> startActivity(new Intent(MainActivity.this, AboutActivity.class))));
        android.widget.ScrollView s = new android.widget.ScrollView(this); s.addView(l);
        new AlertDialog.Builder(this).setTitle(R.string.settings).setView(s).setNegativeButton(R.string.cancel, null).show();
    }

    private boolean isDarkMode() {
        return (getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }
    
    private String getLanguageDisplayName(String c) {
        if (LocaleHelper.LANG_ZH.equals(c)) return "简体中文";
        if (LocaleHelper.LANG_ZH_TW.equals(c)) return "繁體中文";
        if (LocaleHelper.LANG_EN.equals(c)) return "English";
        return getString(R.string.lang_system);
    }

    private void showLanguageDialog() {
        String cur = LocaleHelper.getSavedLanguage(this);
        String[] names = {getString(R.string.lang_system), "简体中文", "繁體中文", "English"};
        String[] codes = {LocaleHelper.LANG_SYSTEM, LocaleHelper.LANG_ZH, LocaleHelper.LANG_ZH_TW, LocaleHelper.LANG_EN};
        int idx = 0; for (int i = 0; i < codes.length; i++) if (codes[i].equals(cur)) idx = i;
        final int[] sel = {idx};
        new AlertDialog.Builder(this).setTitle(R.string.language).setSingleChoiceItems(names, idx, (d, w) -> sel[0] = w)
                .setPositiveButton(R.string.confirm_btn, (d, w) -> { if (!codes[sel[0]].equals(cur)) { LocaleHelper.setLocale(this, codes[sel[0]]); recreate(); } })
                .setNegativeButton(R.string.cancel, null).show();
    }

    private void showClearListConfirmation() {
        new AlertDialog.Builder(this).setTitle(R.string.clear_list).setMessage(R.string.confirm_clear_list)
                .setPositiveButton(R.string.confirm_delete, (d, w) -> {
                    tagManager.clearAllSongs(); allSongs.clear(); currentSongList.clear(); currentSongIndex = -1; isPlaying = false; 
                    if (musicPlayer != null) musicPlayer.stop();
                    songAdapter.updateSongs(currentSongList); textSongInfo.setText(R.string.no_song_playing);
                    updatePlayPauseUI(); if (musicService != null) musicService.notifyPlaybackStopped();
                    updateTagSpinner(); updateDirectoryDisplay();
                }).setNegativeButton(R.string.cancel, null).show();
    }

    private void selectMusicFolder() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(i, REQUEST_FOLDER_ACCESS);
        } catch (Exception e) { Toast.makeText(this, R.string.cannot_open_selector, Toast.LENGTH_SHORT).show(); }
    }

    private void refreshSongList() {
        List<Song> toRem = new ArrayList<>();
        for (Song s : allSongs) if (!new File(s.getPath()).exists()) { tagManager.deleteSong(s.getId()); toRem.add(s); }
        allSongs.removeAll(toRem); currentSongList.removeAll(toRem);
        scanForNewSongsAsync();
        if (!toRem.isEmpty()) Toast.makeText(this, getString(R.string.removed_invalid_songs, toRem.size()), Toast.LENGTH_SHORT).show();
    }

    private void scanForNewSongsAsync() {
        new Thread(() -> {
            List<Song> newS = scanForNewSongs();
            if (newS.isEmpty()) {
                runOnUiThread(() -> { swipeRefresh.setRefreshing(false); Toast.makeText(this, R.string.no_new_songs, Toast.LENGTH_SHORT).show(); });
                return;
            }
            allSongs.addAll(newS);
            String q = editSearch.getText().toString().toLowerCase().trim();
            runOnUiThread(() -> {
                for (Song s : newS) if (q.isEmpty() || s.getTitle().toLowerCase().contains(q)) currentSongList.add(s);
                songAdapter.updateSongs(currentSongList); updateTagSpinner(); swipeRefresh.setRefreshing(false);
                Toast.makeText(this, getString(R.string.found_new_songs, newS.size()), Toast.LENGTH_SHORT).show();
            });
        }).start();
    }

    private List<Song> scanForNewSongs() {
        List<Song> found = new ArrayList<>();
        List<String> paths = new ArrayList<>();
        for (Song s : allSongs) paths.add(s.getPath());
        String d = directoryManager.getMusicDirectory();
        if (d != null && !d.isEmpty()) {
            File dir = new File(d);
            File[] fs = dir.listFiles();
            if (fs != null) {
                for (File f : fs) {
                    if (!f.isFile()) continue;
                    String ln = f.getName().toLowerCase();
                    if (!ln.endsWith(".mp3") && !ln.endsWith(".flac") && !ln.endsWith(".wav") && !ln.endsWith(".aac") && !ln.endsWith(".m4a") && !ln.endsWith(".ogg")) continue;
                    String p = f.getAbsolutePath();
                    if (!paths.contains(p)) {
                        Song s = new Song(); s.setPath(p); s.setTitle(Song.formatTitleFromPath(p));
                        updateMetadataFromFile(s); s.setCategory("音乐"); s.setId(tagManager.addSong(s)); found.add(s);
                    }
                }
            }
            File[] subs = dir.listFiles(File::isDirectory);
            if (subs != null) for (File sub : subs) if (!sub.getName().startsWith(".")) scanCategoryDirectory(sub, sub.getName(), found, paths);
        } else {
            Uri uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
            Cursor c = getContentResolver().query(uri, new String[]{MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.DURATION}, MediaStore.Audio.Media.IS_MUSIC + " != 0", null, null);
            if (c != null) {
                while (c.moveToNext()) {
                    String p = c.getString(0);
                    if (!paths.contains(p)) {
                        Song s = new Song(); s.setPath(p); s.setTitle(Song.formatTitleFromPath(p));
                        updateMetadataFromFile(s); s.setCategory("音乐"); s.setDuration(c.getLong(1));
                        s.setId(tagManager.addSong(s)); found.add(s);
                    }
                }
                c.close();
            }
        }
        return found;
    }

    private void scanCategoryDirectory(File d, String n, List<Song> f, List<String> p) {
        File[] es = d.listFiles(); if (es == null) return;
        for (File entry : es) {
            if (entry.isDirectory()) { if (!entry.getName().startsWith(".")) scanCategoryDirectory(entry, n, f, p); }
            else if (entry.isFile()) {
                String ln = entry.getName().toLowerCase();
                if (!ln.endsWith(".mp3") && !ln.endsWith(".flac") && !ln.endsWith(".wav") && !ln.endsWith(".aac") && !ln.endsWith(".m4a") && !ln.endsWith(".ogg")) continue;
                String path = entry.getAbsolutePath();
                if (!p.contains(path)) {
                    Song s = new Song(); s.setPath(path); s.setTitle(Song.formatTitleFromPath(path));
                    updateMetadataFromFile(s); s.setCategory(n); s.setId(tagManager.addSong(s));
                    tagManager.addCategoryTag(n); f.add(s);
                }
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
                String lrc = t.getFirst(FieldKey.LYRICS);
                if (lrc != null && !lrc.trim().isEmpty()) s.setLyrics(lrc);
            }
            s.setCategory("音乐");
        } catch (Exception e) { s.setArtist(""); s.setAlbum(""); s.setCategory("音乐"); }
    }

    private void syncPlayerUI() {
        if (musicService == null) return;
        MusicPlayer active = musicService.getActivePlayer();
        isPlaying = active.isPlaying();
        updatePlayPauseUI();
        
        isHomePlayerActive = (active == musicPlayer);
        
        String currentPath = active.getCurrentPath();
        if (currentPath != null && !currentPath.isEmpty()) {
            boolean found = false;
            for (int i = 0; i < currentSongList.size(); i++) {
                if (currentPath.equals(currentSongList.get(i).getPath())) {
                    currentSongIndex = i; songAdapter.setSelectedPosition(i); found = true;
                    updateSongUI(currentSongList.get(i), false); break;
                }
            }
            if (!found) for (Song s : allSongs) if (currentPath.equals(s.getPath())) { updateSongUI(s, false); found = true; break; }
            if (!found) textSongInfo.setText(R.string.now_playing);
        }
    }

    private void playSongManually(Song s, int p) { isHomePlayerActive = true; consecutiveFailures = 0; currentSongIndex = p; shouldResumeFromSavedPosition = false; playSong(s); }
    private void playSong(Song s) { if (s == null) return; updateSongUI(s, true); startPlayback(s); }

    private void updateSongUI(Song s, boolean notifyService) {
        if (lastPlayedSong != null && musicPlayer != null && musicPlayer.isPrepared()) {
            int pos = musicPlayer.getCurrentPosition();
            String lastCat = lastPlayedSong.getCategory();
            if (lastCat != null && !lastCat.equals("音乐")) tagManager.updateLastPosition(lastPlayedSong.getId(), pos);
            lastPlayedSong.setLastPosition(pos);
        }
        lastPlayedSong = s;
        songAdapter.setSelectedPosition(currentSongIndex);
        
        String cat = s.getCategory();
        if (notifyService) {
            if (cat != null && !cat.equals("音乐") && s.getLastPosition() > 0) {
                pendingSeekPosition = s.getLastPosition();
                Toast.makeText(this, R.string.resumed_position, Toast.LENGTH_SHORT).show();
            } else if (shouldResumeFromSavedPosition && s.getLastPosition() > 0) pendingSeekPosition = s.getLastPosition();
        }
        
        shouldResumeFromSavedPosition = false;
        String art = s.getArtist(); if (art == null || art.isEmpty() || art.contains("<unknown>")) art = "";
        textSongInfo.setText(getString(R.string.song_info_format, s.getTitle(), art));
        textFullTitle.setText(s.getTitle());
        textFullArtist.setText(art);
        textFullArtist.setVisibility(art.isEmpty() ? View.INVISIBLE : View.VISIBLE);
        textFullPlayingBarTitle.setText(getString(R.string.song_info_format, s.getTitle(), art));
        
        final String lrcPath = s.getPath();
        new Thread(() -> {
            final List<LrcLine> lines = loadLyricsSync(lrcPath);
            runOnUiThread(() -> lrcAdapter.setLrcLines(lines));
        }).start();
        
        updateMediaSessionMetadata(s);
        updateMediaSessionState();
        updatePlayPauseUI();
        if (notifyService && musicService != null) musicService.notifyPlaybackStarted(musicPlayer, s.getTitle(), art);
    }

    private List<LrcLine> loadLyricsSync(String path) {
        if (currentSongIndex >= 0 && currentSongIndex < currentSongList.size()) {
            Song s = currentSongList.get(currentSongIndex);
            if (s.getLyrics() != null && !s.getLyrics().trim().isEmpty()) return parseLrcContent(s.getLyrics());
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

    private void startPlayback(Song s) {
        musicPlayer.play(this, s.getPath());
        if (musicService != null) {
            String art = s.getArtist(); if (art == null || art.isEmpty() || art.contains("<unknown>")) art = "";
            musicService.updateLastPlayedPath(s.getPath(), s.getTitle(), art, 0);
        }
        triggerPreloadNext();
    }

    private void triggerPreloadNext() {
        if (currentSongIndex >= 0 && currentSongIndex < currentSongList.size() - 1)
            musicPlayer.preloadNext(currentSongList.get(currentSongIndex + 1).getPath());
    }

    private void loadLyrics(String path) { lrcAdapter.setLrcLines(loadLyricsSync(path)); }

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
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            StringBuilder sb = new StringBuilder();
            String l; while ((l = br.readLine()) != null) sb.append(l).append("\n");
            return parseLrcContent(sb.toString());
        } catch (Exception e) { return new ArrayList<>(); }
    }

    private List<LrcLine> parseLrcContent(String c) {
        List<LrcLine> lines = new ArrayList<>(); if (c == null || c.isEmpty()) return lines;
        Pattern p = Pattern.compile("\\[(\\d+):(\\d+)(?:[.:](\\d+))?](.*)");
        for (String line : c.split("\n")) {
            Matcher m = p.matcher(line);
            if (m.find()) {
                String g1 = m.group(1), g2 = m.group(2), g3 = m.group(3), g4 = m.group(4);
                if (g1 == null || g2 == null) continue;
                long min = Long.parseLong(g1), sec = Long.parseLong(g2), ms = 0;
                if (g3 != null) { ms = Long.parseLong(g3); if (g3.length() == 2) ms *= 10; }
                String txt = (g4 != null) ? g4.trim() : "";
                if (!txt.isEmpty()) lines.add(new LrcLine((min * 60 + sec) * 1000 + ms, txt));
            }
        }
        Collections.sort(lines); return lines;
    }

    private String formatTime(int ms) { return String.format(Locale.CHINA, "%02d:%02d", (ms / 60000) % 60, (ms / 1000) % 60); }

    private void togglePlayPause() {
        if (musicService == null) return;
        MusicPlayer active = musicService.getActivePlayer();
        if (active.isPlaying()) {
            active.pause(); isPlaying = false; updatePlayPauseUI();
            musicService.notifyPlaybackPaused();
        } else {
            if (isHomePlayerActive && musicPlayer.isPrepared()) {
                musicPlayer.resume(); isPlaying = true; updatePlayPauseUI();
                musicService.notifyPlaybackResumed();
            } else if (!currentSongList.isEmpty()) {
                isHomePlayerActive = true;
                playSong(currentSongList.get(Math.max(0, currentSongIndex)));
            }
        }
        updateMediaSessionState();
    }

    private void playPrevious() {
        if (currentSongList.isEmpty()) return;
        isHomePlayerActive = true;
        musicPlayer.cancelPreload(); consecutiveFailures = 0;
        currentSongIndex = (currentSongIndex - 1 + currentSongList.size()) % currentSongList.size();
        playSong(currentSongList.get(currentSongIndex));
    }

    private void playNext() {
        if (currentSongList.isEmpty()) { isPlaying = false; updatePlayPauseUI(); if (musicService != null) musicService.notifyPlaybackStopped(); return; }
        isHomePlayerActive = true;
        currentSongIndex = (currentSongIndex + 1) % currentSongList.size();
        playSong(currentSongList.get(currentSongIndex));
    }

    private void openTagPlayer() { startActivityForResult(new Intent(this, TagPlayerActivity.class), REQUEST_TAG_PLAYER); }
    private void shuffleSongs() {
        if (!allSongs.isEmpty()) {
            TrueRandomShuffler.shuffle(allSongs); tagManager.updateSongsOrder(allSongs);
            filterSongs(editSearch.getText().toString());
            if (!currentSongList.isEmpty()) { currentSongIndex = 0; playSong(currentSongList.get(0)); }
            Toast.makeText(this, R.string.shuffle_saved, Toast.LENGTH_SHORT).show();
        }
    }

    private void showManageTagsDialog() {
        List<Tag> tags = tagManager.getUserTags(); String[] names = new String[tags.size()];
        for (int i = 0; i < tags.size(); i++) names[i] = tags.get(i).getName();
        new AlertDialog.Builder(this).setTitle(R.string.manage_tags).setItems(names, (d, w) -> showTagOptionsDialog(tags.get(w))).setNeutralButton(R.string.add_tag, (d, w) -> showCreateTagDialog()).setNegativeButton(R.string.cancel, null).show();
    }

    private void showCreateTagDialog() {
        final EditText et = new EditText(this); et.setHint(R.string.tag_name_hint);
        new AlertDialog.Builder(this).setTitle(R.string.add_tag).setView(et).setPositiveButton(R.string.confirm_btn, (d, w) -> {
            String n = et.getText().toString().trim(); if (!n.isEmpty()) { tagManager.addTag(n); updateTagSpinner(); }
        }).setNegativeButton(R.string.cancel, null).show();
    }

    private void showTagOptionsDialog(Tag t) {
        String[] opts = { getString(R.string.shuffle_tag_format, t.getName()), getString(R.string.delete_tag) };
        new AlertDialog.Builder(this).setTitle(getString(R.string.tag_label, t.getName())).setItems(opts, (d, w) -> {
            if (w == 0) { showSongsForTag(t.getId()); if (!currentSongList.isEmpty()) shuffleSongs(); }
            else if (w == 1) showDeleteTagDialog(t);
        }).setNegativeButton(R.string.cancel, null).show();
    }

    private void showDeleteTagDialog(Tag t) {
        new AlertDialog.Builder(this).setTitle(R.string.confirm_delete).setMessage(R.string.confirm_delete_tag).setPositiveButton(R.string.confirm_delete, (d, w) -> {
            tagManager.deleteTag(t.getId()); updateTagSpinner(); updateSongList();
        }).setNegativeButton(R.string.cancel, null).show();
    }

    private void showSongOptionsDialog(Song s) {
        String ext = ""; int dot = s.getPath().lastIndexOf("."); if (dot != -1) ext = s.getPath().substring(dot + 1).toUpperCase();
        AlertDialog.Builder b = new AlertDialog.Builder(this); Context dc = b.getContext(); float den = dc.getResources().getDisplayMetrics().density;
        android.widget.LinearLayout h = new android.widget.LinearLayout(dc); h.setOrientation(android.widget.LinearLayout.VERTICAL); h.setPadding((int)(24*den), (int)(20*den), (int)(24*den), (int)(8*den));
        TextView title = new TextView(dc); title.setText(s.getTitle()); title.setTextSize(18); title.setTypeface(null, android.graphics.Typeface.BOLD);
        android.util.TypedValue tv = new android.util.TypedValue();
        title.setTextColor(dc.getTheme().resolveAttribute(android.R.attr.textColorPrimary, tv, true) ? ((tv.resourceId != 0) ? ContextCompat.getColor(dc, tv.resourceId) : tv.data) : -1);
        TextView info = new TextView(dc); String art = s.getArtist(); if (art == null || art.isEmpty() || art.contains("<unknown>")) art = getString(R.string.unknown_artist);
        info.setText(getString(R.string.song_options_info_format, art, ext)); info.setTextSize(13); info.setPadding(0, (int)(4*den), 0, 0);
        info.setTextColor(dc.getTheme().resolveAttribute(android.R.attr.textColorSecondary, tv, true) ? ((tv.resourceId != 0) ? ContextCompat.getColor(dc, tv.resourceId) : tv.data) : -3355444);
        h.addView(title); h.addView(info);
        String[] opts = { getString(R.string.edit_info), getString(R.string.edit_tags), getString(R.string.delete_song) };
        b.setCustomTitle(h).setItems(opts, (d, w) -> {
            if (w == 0) showEditSongInfoDialog(s); else if (w == 1) showTagSongDialog(s); else if (w == 2) showDeleteSongDialog(s);
        }).setNegativeButton(R.string.cancel, null).show();
    }

    private void showEditSongInfoDialog(Song s) {
        android.widget.LinearLayout l = new android.widget.LinearLayout(this); l.setOrientation(android.widget.LinearLayout.VERTICAL); l.setPadding(50, 40, 50, 10);
        final EditText eA = new EditText(this); eA.setHint(R.string.artist_hint); eA.setText(s.getArtist());
        TextView lL = new TextView(this); lL.setText(getString(R.string.lyrics_label_with_newline)); lL.setTextSize(14);
        final EditText eL = new EditText(this); eL.setHint(R.string.lyrics_hint); eL.setText(s.getLyrics()); eL.setMinLines(5); eL.setGravity(android.view.Gravity.TOP);
        l.addView(new androidx.appcompat.widget.AppCompatTextView(this){{setText(R.string.artist_label);}}); l.addView(eA); l.addView(lL); l.addView(eL);
        new AlertDialog.Builder(this).setTitle(R.string.edit_info).setView(l).setPositiveButton(R.string.sync_to_file, (d, w) -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !android.os.Environment.isExternalStorageManager()) { showManageStoragePermissionDialog(); return; }
            String nA = eA.getText().toString().trim(), nL = eL.getText().toString().trim();
            s.setArtist(nA); s.setLyrics(nL); tagManager.updateSongMetadata(s.getId(), nA, nL);
            if (lastPlayedSong != null && lastPlayedSong.getId() == s.getId()) loadLyrics(s.getPath());
            updateSongList(); saveMetadataToFile(s, nA, nL);
        }).setNeutralButton(R.string.save_only, (d, w) -> {
            String nA = eA.getText().toString().trim(), nL = eL.getText().toString().trim();
            s.setArtist(nA); s.setLyrics(nL); tagManager.updateSongMetadata(s.getId(), nA, nL);
            if (lastPlayedSong != null && lastPlayedSong.getId() == s.getId()) loadLyrics(s.getPath());
            updateSongList(); Toast.makeText(this, R.string.info_saved, Toast.LENGTH_SHORT).show();
        }).setNegativeButton(R.string.cancel, null).show();
    }

    private void showManageStoragePermissionDialog() {
        new AlertDialog.Builder(this).setTitle(R.string.need_file_permission).setMessage(R.string.file_permission_message).setPositiveButton(R.string.go_enable, (d, w) -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) { try { startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + getPackageName()))); } catch (Exception e) { startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)); } }
        }).setNegativeButton(R.string.later, null).show();
    }

    private void saveMetadataToFile(Song s, String artist, String lyrics) {
        if (musicPlayer != null) musicPlayer.stop();
        new Thread(() -> {
            try {
                Thread.sleep(500); org.jaudiotagger.tag.TagOptionSingleton.getInstance().setAndroid(true);
                AudioFile af = AudioFileIO.read(new File(s.getPath())); org.jaudiotagger.tag.Tag t = af.getTag();
                if (t == null) { t = af.createDefaultTag(); af.setTag(t); }
                t.deleteField(FieldKey.ARTIST); t.setField(FieldKey.ARTIST, artist);
                if (lyrics != null && !lyrics.trim().isEmpty()) { t.deleteField(FieldKey.LYRICS); t.setField(FieldKey.LYRICS, lyrics); } else t.deleteField(FieldKey.LYRICS);
                af.commit();
                runOnUiThread(() -> Toast.makeText(this, R.string.info_synced, Toast.LENGTH_SHORT).show());
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, R.string.file_sync_failed, Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private void showTagSongDialog(Song s) {
        List<Tag> all = tagManager.getUserTags(); if (all.isEmpty()) { Toast.makeText(this, R.string.create_tag_first, Toast.LENGTH_SHORT).show(); return; }
        List<String> cur = tagManager.getTagsForSong(s.getId()); String[] ns = new String[all.size()]; boolean[] ck = new boolean[all.size()];
        for (int i = 0; i < all.size(); i++) { ns[i] = all.get(i).getName(); ck[i] = cur.contains(all.get(i).getName()); }
        new AlertDialog.Builder(this).setTitle(R.string.select_tags).setMultiChoiceItems(ns, ck, (d, w, isC) -> ck[w] = isC).setPositiveButton(R.string.confirm_btn, (d, w) -> {
            for (int i = 0; i < all.size(); i++) { if (ck[i]) tagManager.addTagToSong(s.getId(), all.get(i).getId()); else tagManager.removeTagFromSong(s.getId(), all.get(i).getId()); }
            s.setTags(tagManager.getTagsForSong(s.getId())); updateSongList(); updateTagSpinner();
        }).setNeutralButton(R.string.add_tag, (d, w) -> showCreateTagDialog()).setNegativeButton(R.string.cancel, null).show();
    }

    private void showDeleteSongDialog(Song s) {
        new AlertDialog.Builder(this).setTitle(R.string.confirm_delete).setMessage(getString(R.string.delete_message_format, s.getTitle()))
                .setPositiveButton(R.string.confirm_delete, (d, w) -> { tagManager.deleteSong(s.getId()); allSongs.remove(s); currentSongList.remove(s); songAdapter.updateSongs(currentSongList); updateTagSpinner(); })
                .setNegativeButton(R.string.cancel, null).show();
    }

    private void showSongsForTag(long id) { currentSongList.clear(); currentSongList.addAll(tagManager.getSongsForTag(id)); songAdapter.updateSongs(currentSongList); selectTagInSpinner(id); }

    private void selectTagInSpinner(long id) {
        List<Tag> allT = tagManager.getAllTags(); String tN = ""; String tT = "none";
        if (id != -1) for (Tag t : allT) if (t.getId() == id) { tN = t.getName(); tT = "category".equals(t.getType()) ? "category" : "tag"; break; }
        selectedCategory = tN; selectedFilterType = tT; updateTagSpinner();
    }

    @Override
    protected void onActivityResult(int request, int result, @Nullable Intent data) {
        super.onActivityResult(request, result, data);
        if (request == REQUEST_FOLDER_ACCESS && result == RESULT_OK && data != null) {
            String p = getPathFromUri(data.getData()); if (p != null) { directoryManager.setMusicDirectory(p); updateDirectoryDisplay(); setupDirectoryObserver(); refreshSongList(); }
        } else if (request == REQUEST_TAG_PLAYER) {
            if (result == TagPlayerActivity.RESULT_DISCOVER) bottomNavigation.setSelectedItemId(R.id.nav_discovery);
            else if (result == RESULT_OK && data != null) {
                long id = data.getLongExtra("tagId", -1); if (id != -1) { selectTagInSpinner(id); if (!currentSongList.isEmpty()) shuffleSongs(); }
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

    private void initMediaSession() { if (mediaSession == null) return; mediaSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS); }

    private void updateMediaSessionMetadata(Song s) {
        if (mediaSession == null || s == null) return;
        try {
            MediaMetadata.Builder b = new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, s.getTitle() != null ? s.getTitle() : getString(R.string.unknown_song)).putString(MediaMetadata.METADATA_KEY_ARTIST, s.getArtist() != null ? s.getArtist() : "").putString(MediaMetadata.METADATA_KEY_ALBUM, s.getAlbum() != null ? s.getAlbum() : "").putLong(MediaMetadata.METADATA_KEY_DURATION, s.getDuration());
            try {
                AudioFile af = AudioFileIO.read(new File(s.getPath())); org.jaudiotagger.tag.Tag t = af.getTag();
                if (t != null && t.getFirstArtwork() != null) {
                    byte[] d = t.getFirstArtwork().getBinaryData();
                    if (d != null && d.length > 0) { Bitmap bm = BitmapFactory.decodeByteArray(d, 0, d.length); if (bm != null) b.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, bm); }
                }
            } catch (Exception ignored) {}
            mediaSession.setMetadata(b.build());
        } catch (Exception e) { e.printStackTrace(); }
    }

    private void updateMediaSessionState() {
        if (mediaSession == null) return;
        try {
            int state = isPlaying ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED;
            long pos = (musicPlayer != null && musicPlayer.isPrepared()) ? musicPlayer.getCurrentPosition() : 0;
            mediaSession.setPlaybackState(new PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_STOP | PlaybackState.ACTION_SEEK_TO).setState(state, pos, 1.0f).build());
        } catch (Exception e) { e.printStackTrace(); }
    }

    @Override protected void onPause() { super.onPause(); savePlaybackState(); }
    @Override protected void onResume() { super.onResume(); if (serviceBound && musicService != null) { musicService.setCallback(serviceCallback); syncPlayerUI(); } }
    @Override
    protected void onDestroy() {
        savePlaybackState();
        if (serviceBound) {
            if (musicService != null) { musicService.setCallback(null); musicService.setHomePlaybackListener(null); }
            try { unbindService(serviceConnection); } catch (Exception ignored) {}
            serviceBound = false;
        }
        musicService = null;
        super.onDestroy();
        if (directoryObserver != null) { directoryObserver.stopWatching(); directoryObserver = null; }
    }

    private void setupDirectoryObserver() {
        if (directoryObserver != null) { directoryObserver.stopWatching(); directoryObserver = null; }
        String dP = directoryManager.getMusicDirectory(); if (dP == null || dP.isEmpty()) return;
        final File wD = new File(dP); if (!wD.exists() || !wD.isDirectory()) return;
        directoryObserver = new android.os.FileObserver(wD.getAbsolutePath(), android.os.FileObserver.CREATE | android.os.FileObserver.DELETE | android.os.FileObserver.MOVED_FROM | android.os.FileObserver.MOVED_TO) {
            @Override public void onEvent(int event, String name) {
                if (name == null) return; int mask = event & android.os.FileObserver.ALL_EVENTS;
                if (mask == android.os.FileObserver.CREATE || mask == android.os.FileObserver.DELETE || mask == android.os.FileObserver.MOVED_FROM || mask == android.os.FileObserver.MOVED_TO) {
                    if (new File(wD, name).isDirectory() && !name.startsWith(".")) runOnUiThread(() -> updateCategoryBar());
                }
            }
        };
        directoryObserver.startWatching();
    }
}
