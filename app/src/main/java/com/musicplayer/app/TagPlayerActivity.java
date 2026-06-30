package com.musicplayer.app;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.musicplayer.app.adapter.LrcAdapter;
import com.musicplayer.app.adapter.SongAdapter;
import com.musicplayer.app.model.LrcLine;
import com.musicplayer.app.model.Song;
import com.musicplayer.app.model.Tag;
import com.musicplayer.app.player.MusicPlayer;
import com.musicplayer.app.player.MusicPlayerService;
import com.musicplayer.app.player.TrueRandomShuffler;
import com.musicplayer.app.storage.TagManager;

import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.TagField;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TagPlayerActivity extends AppCompatActivity {
    public static final int RESULT_DISCOVER = 1001;

    private android.widget.LinearLayout layoutTagChips;
    private RecyclerView recyclerTagSongs;
    private EditText editSearch;
    private ToggleButton btnShuffle;
    private ImageButton btnBack, btnSearch;
    private TextView textEmptyHint;

    private View bottomSheetPlayer, miniPlayer, fullPlayer;
    private BottomSheetBehavior<View> sheetBehavior;

    private TextView textSongInfo;
    private ImageButton btnMiniPrev, btnMiniPlayPause, btnMiniNext;
    private ProgressBar seekMiniProgress;

    private ImageButton btnPlayPause, btnPrev, btnNext, btnFullShuffle;
    private TextView textFullTitle, textFullArtist, textFullPlayingBarTitle, textCurrentTime, textTotalTime;
    private SeekBar seekProgress, seekVolume;
    private RecyclerView recyclerLyrics;
    private LrcAdapter lrcAdapter;

    private BottomNavigationView bottomNavigation;
    private androidx.swiperefreshlayout.widget.SwipeRefreshLayout swipeRefresh;

    private TagManager tagManager;
    private MusicPlayer musicPlayer;
    private SongAdapter songAdapter;
    private AudioManager audioManager;
    private final Handler progressHandler = new Handler(Looper.getMainLooper());
    
    private MusicPlayerService musicService;
    private boolean serviceBound = false;

    private List<Tag> userTags = new ArrayList<>();
    private final List<Song> originalTagSongs = new ArrayList<>();
    private final List<Song> playOrder = new ArrayList<>();
    private final List<Song> displaySongs = new ArrayList<>();
    private Tag selectedTag = null;
    private int currentSongIndex = -1;
    private boolean isShuffle = false;
    private boolean isSearchVisible = false;
    private boolean isTagPlayerActive = false;

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.applyLocale(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setStatusBarColor(ContextCompat.getColor(this, R.color.colorPrimary));
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            getWindow().getDecorView().setSystemUiVisibility(
                    getWindow().getDecorView().getSystemUiVisibility() & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            );
        }

        setContentView(R.layout.activity_tag_player);

        tagManager = new TagManager(this);

        initViews();
        initBottomSheet();
        
        Intent serviceIntent = new Intent(this, MusicPlayerService.class);
        startService(serviceIntent);
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    private void initViews() {
        layoutTagChips = findViewById(R.id.layout_tag_chips);
        recyclerTagSongs = findViewById(R.id.recycler_tag_songs);
        editSearch = findViewById(R.id.edit_tag_search);
        btnShuffle = findViewById(R.id.btn_shuffle);
        btnBack = findViewById(R.id.btn_back);
        btnSearch = findViewById(R.id.btn_search);
        textEmptyHint = findViewById(R.id.text_empty_hint);

        swipeRefresh = findViewById(R.id.swipe_refresh);
        swipeRefresh.setColorSchemeColors(ContextCompat.getColor(this, R.color.colorPrimary));
        swipeRefresh.setOnRefreshListener(this::refreshTagData);

        recyclerTagSongs.setLayoutManager(new LinearLayoutManager(this));
        songAdapter = new SongAdapter(displaySongs);
        recyclerTagSongs.setAdapter(songAdapter);

        btnBack.setOnClickListener(v -> finish());

        songAdapter.setOnSongClickListener((song, position) -> {
            int playIndex = playOrder.indexOf(song);
            if (playIndex >= 0) {
                playSong(playIndex);
            }
        });

        btnShuffle.setOnCheckedChangeListener((buttonView, isChecked) -> {
            isShuffle = isChecked;
            rebuildPlayOrder(true);
        });

        btnSearch.setOnClickListener(v -> {
            isSearchVisible = !isSearchVisible;
            editSearch.setVisibility(isSearchVisible ? View.VISIBLE : View.GONE);
            if (!isSearchVisible) {
                editSearch.setText("");
            } else {
                editSearch.requestFocus();
            }
        });

        editSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                filterDisplaySongs(s.toString());
            }
        });

        bottomNavigation = findViewById(R.id.bottom_navigation);
        bottomNavigation.setSelectedItemId(R.id.nav_home);

        bottomNavigation.setOnItemSelectedListener(item -> {
            int itemId = item.getItemId();
            if (itemId == R.id.nav_home) {
                finish();
                return true;
            } else if (itemId == R.id.nav_discovery) {
                setResult(RESULT_DISCOVER);
                finish();
                return true;
            }
            return false;
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
        textSongInfo = findViewById(R.id.text_song_info);

        ImageButton btnCollapse = findViewById(R.id.btn_collapse);
        btnPlayPause = findViewById(R.id.btn_play_pause);
        btnPrev = findViewById(R.id.btn_prev);
        btnNext = findViewById(R.id.btn_next);
        btnFullShuffle = findViewById(R.id.btn_full_shuffle);
        ImageButton btnList = findViewById(R.id.btn_list);
        textFullTitle = findViewById(R.id.text_full_title);
        textFullArtist = findViewById(R.id.text_full_artist);
        textFullPlayingBarTitle = findViewById(R.id.text_full_playing_bar_title);
        textCurrentTime = findViewById(R.id.text_current_time);
        textTotalTime = findViewById(R.id.text_total_time);
        seekProgress = findViewById(R.id.seek_progress);
        seekVolume = findViewById(R.id.seek_volume);
        recyclerLyrics = findViewById(R.id.recycler_lyrics);

        lrcAdapter = new LrcAdapter();
        recyclerLyrics.setLayoutManager(new LinearLayoutManager(this));
        recyclerLyrics.setAdapter(lrcAdapter);

        miniPlayer.setOnClickListener(v -> sheetBehavior.setState(BottomSheetBehavior.STATE_EXPANDED));
        btnCollapse.setOnClickListener(v -> sheetBehavior.setState(BottomSheetBehavior.STATE_COLLAPSED));

        btnMiniPlayPause.setOnClickListener(v -> togglePlayPause());
        btnMiniPrev.setOnClickListener(v -> playPrev());
        btnMiniNext.setOnClickListener(v -> playNext());

        btnPlayPause.setOnClickListener(v -> togglePlayPause());
        btnPrev.setOnClickListener(v -> playPrev());
        btnNext.setOnClickListener(v -> playNext());
        btnFullShuffle.setOnClickListener(v -> {
            isShuffle = !isShuffle;
            btnShuffle.setChecked(isShuffle);
            rebuildPlayOrder(true);
        });
        btnList.setOnClickListener(v -> {
            sheetBehavior.setState(BottomSheetBehavior.STATE_COLLAPSED);
            recyclerTagSongs.smoothScrollToPosition(Math.max(0, currentSongIndex));
        });

        seekProgress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && musicPlayer != null) musicPlayer.seekTo(progress);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        seekVolume.setMax(audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        seekVolume.setProgress(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC));
        seekVolume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
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

    private void initPlayer() {
        if (musicService != null) {
            musicService.setTagPlaybackListener(new MusicPlayer.OnPlaybackListener() {
                @Override
                public void onPrepared() {
                    runOnUiThread(() -> {
                        int d = musicPlayer.getDuration();
                        seekProgress.setMax(d);
                        seekMiniProgress.setMax(d);
                        textTotalTime.setText(formatTime(d));
                        btnPlayPause.setImageResource(R.drawable.ic_pause_vector);
                        btnMiniPlayPause.setImageResource(R.drawable.ic_pause_vector);
                        songAdapter.setSelectedPosition(currentSongIndex);
                        updatePlayerInfo();
                        startProgressUpdater();
                    });
                }
    
                @Override
                public void onCompletion() {
                    runOnUiThread(() -> {
                        btnPlayPause.setImageResource(R.drawable.ic_play_vector);
                        btnMiniPlayPause.setImageResource(R.drawable.ic_play_vector);
                        playNext();
                    });
                }
    
                @Override
                public void onError(String error) {
                    runOnUiThread(() -> Toast.makeText(TagPlayerActivity.this, error, Toast.LENGTH_SHORT).show());
                }
    
                @Override
                public void onShouldSkip() {
                    runOnUiThread(() -> playNext());
                }
    
                @Override
                public void onProgress(int currentPosition, int duration) {
                    runOnUiThread(() -> {
                        seekProgress.setProgress(currentPosition);
                        seekMiniProgress.setProgress(currentPosition);
                        textCurrentTime.setText(formatTime(currentPosition));
                        int l = lrcAdapter.updateCurrentLine(currentPosition);
                        if (l != -1) recyclerLyrics.smoothScrollToPosition(l);
                    });
                }
            });
        }
    }

    private void refreshTagData() {
        userTags = tagManager.getUserTags();
        if (userTags.isEmpty()) {
            textEmptyHint.setText(R.string.no_tags);
            textEmptyHint.setVisibility(View.VISIBLE);
            swipeRefresh.setVisibility(View.GONE);
            swipeRefresh.setRefreshing(false);
            return;
        }

        Tag matchTag = null;
        if (selectedTag != null) {
            for (Tag t : userTags) {
                if (t.getId() == selectedTag.getId()) {
                    matchTag = t;
                    break;
                }
            }
        }

        if (matchTag != null) {
            selectedTag = matchTag;
            updateTagChips();
            loadSongsForTag(matchTag, false);
        } else {
            selectTag(userTags.get(0), false);
        }
        swipeRefresh.setRefreshing(false);
    }

    private void loadTags() {
        userTags = tagManager.getUserTags();
        if (userTags.isEmpty()) {
            textEmptyHint.setText(R.string.no_tags);
            textEmptyHint.setVisibility(View.VISIBLE);
            swipeRefresh.setVisibility(View.GONE);
            return;
        }
        updateTagChips();
        selectTag(userTags.get(0), false);
    }

    private void updateTagChips() {
        layoutTagChips.removeAllViews();
        for (Tag tag : userTags) {
            addTagChip(tag);
        }
    }

    private void addTagChip(Tag tag) {
        Button btn = new Button(new android.view.ContextThemeWrapper(this,
                com.google.android.material.R.style.Widget_MaterialComponents_Button_TextButton), null, 0);

        int count = tagManager.getSongCountForTag(tag.getId());
        btn.setText(getString(R.string.filter_chip_format, tag.getName(), count));
        btn.setTextSize(11);
        btn.setAllCaps(false);
        btn.setPadding(20, 0, 20, 0);
        btn.setMinWidth(0);
        btn.setMinimumWidth(0);

        int gray = ContextCompat.getColor(this, android.R.color.darker_gray);
        int red = ContextCompat.getColor(this, R.color.colorPrimary);
        boolean isSelected = selectedTag != null && selectedTag.getId() == tag.getId();
        btn.setTextColor(isSelected ? red : gray);

        btn.setOnClickListener(v -> selectTag(tag, true));
        layoutTagChips.addView(btn);
    }

    private void selectTag(Tag tag, boolean shouldPlay) {
        selectedTag = tag;
        updateTagChips();
        loadSongsForTag(tag, shouldPlay);
    }

    private void loadSongsForTag(Tag tag, boolean shouldPlay) {
        originalTagSongs.clear();
        originalTagSongs.addAll(tagManager.getSongsForTag(tag.getId()));

        rebuildPlayOrder(shouldPlay);

        if (originalTagSongs.isEmpty()) {
            textEmptyHint.setText(getString(R.string.no_songs_for_tag, tag.getName()));
            textEmptyHint.setVisibility(View.VISIBLE);
            swipeRefresh.setVisibility(View.GONE);
        } else {
            textEmptyHint.setVisibility(View.GONE);
            swipeRefresh.setVisibility(View.VISIBLE);
        }

        if (currentSongIndex >= 0 && currentSongIndex < playOrder.size()) {
            songAdapter.setSelectedPosition(currentSongIndex);
            updatePlayerInfo();
        } else {
            currentSongIndex = -1;
            songAdapter.setSelectedPosition(-1);
            updatePlayerInfo();
        }

        if (isSearchVisible) {
            filterDisplaySongs(editSearch.getText().toString());
        } else {
            displaySongs.clear();
            displaySongs.addAll(playOrder);
            songAdapter.updateSongs(displaySongs);
        }
    }

    private void rebuildPlayOrder(boolean shouldPlay) {
        if (isShuffle) {
            playOrder.clear();
            playOrder.addAll(originalTagSongs);
            TrueRandomShuffler.shuffle(playOrder);
        } else {
            playOrder.clear();
            playOrder.addAll(originalTagSongs);
        }
        if (!isSearchVisible) {
            displaySongs.clear();
            displaySongs.addAll(playOrder);
            songAdapter.updateSongs(displaySongs);
        }
        if (shouldPlay && !playOrder.isEmpty()) {
            playSong(0);
        }
    }

    private void filterDisplaySongs(String query) {
        String lq = query.toLowerCase().trim();
        displaySongs.clear();
        for (Song s : playOrder) {
            if (lq.isEmpty()
                    || (s.getTitle() != null && s.getTitle().toLowerCase().contains(lq))
                    || (s.getArtist() != null && s.getArtist().toLowerCase().contains(lq))) {
                displaySongs.add(s);
            }
        }
        songAdapter.updateSongs(displaySongs);

        if (displaySongs.isEmpty() && !lq.isEmpty()) {
            textEmptyHint.setText(R.string.no_match_songs);
            textEmptyHint.setVisibility(View.VISIBLE);
            swipeRefresh.setVisibility(View.GONE);
        } else if (!originalTagSongs.isEmpty()) {
            textEmptyHint.setVisibility(View.GONE);
            swipeRefresh.setVisibility(View.VISIBLE);
        }
    }

    private void playSong(int index) {
        if (musicPlayer == null || playOrder.isEmpty() || index < 0 || index >= playOrder.size()) return;
        isTagPlayerActive = true;
        Song song = playOrder.get(index);
        if (song == null || song.getPath() == null) return;
        currentSongIndex = index;
        musicPlayer.play(this, song.getPath());
        
        String art = song.getArtist();
        if (art == null || art.isEmpty() || art.contains("<unknown>")) art = "";
        
        if (musicService != null) {
            musicService.updateLastPlayedPath(song.getPath(), song.getTitle(), art, 0);
            musicService.notifyPlaybackStarted(musicPlayer, song.getTitle(), art);
        }
        
        bottomSheetPlayer.setVisibility(View.VISIBLE);
        sheetBehavior.setHideable(false);
        sheetBehavior.setState(BottomSheetBehavior.STATE_COLLAPSED);
        loadLyrics(song.getPath());
        updatePlayerInfo();
    }

    private void togglePlayPause() {
        if (musicService == null) return;
        MusicPlayer active = musicService.getActivePlayer();
        if (active.isPlaying()) {
            active.pause();
            updatePlayPauseUI();
            musicService.notifyPlaybackPaused();
        } else {
            if (isTagPlayerActive && musicPlayer.isPrepared()) {
                musicPlayer.resume();
                updatePlayPauseUI();
                musicService.notifyPlaybackResumed();
            } else if (currentSongIndex >= 0) {
                isTagPlayerActive = true;
                playSong(currentSongIndex);
            } else if (!playOrder.isEmpty()) {
                isTagPlayerActive = true;
                playSong(0);
            }
        }
    }

    private void playPrev() {
        if (musicPlayer == null || playOrder.isEmpty()) return;
        isTagPlayerActive = true;
        int idx = currentSongIndex - 1;
        if (idx < 0) idx = playOrder.size() - 1;
        playSong(idx);
    }

    private void playNext() {
        if (musicPlayer == null || playOrder.isEmpty()) return;
        isTagPlayerActive = true;
        int idx = currentSongIndex + 1;
        if (idx >= playOrder.size()) idx = 0;
        playSong(idx);
    }

    private void updatePlayerInfo() {
        if (currentSongIndex >= 0 && currentSongIndex < playOrder.size()) {
            Song s = playOrder.get(currentSongIndex);
            String art = s.getArtist();
            if (art == null || art.isEmpty() || art.contains("<unknown>")) art = "";

            textSongInfo.setText(getString(R.string.song_info_format, s.getTitle(), art));
            textFullTitle.setText(s.getTitle());
            textFullArtist.setText(art);
            textFullArtist.setVisibility(art.isEmpty() ? View.INVISIBLE : View.VISIBLE);
            textFullPlayingBarTitle.setText(getString(R.string.song_info_format, s.getTitle(), art));
        } else {
            textSongInfo.setText(R.string.no_song_playing);
        }
    }

    private void loadLyrics(String path) {
        String embed = extractLyricsFromMetadata(path);
        if (embed != null && !embed.trim().isEmpty()) {
            lrcAdapter.setLrcLines(parseLrcContent(embed));
            return;
        }
        int dot = path.lastIndexOf(".");
        if (dot != -1) {
            File lf = new File(path.substring(0, dot) + ".lrc");
            if (lf.exists()) {
                lrcAdapter.setLrcLines(parseLrcFile(lf));
                return;
            }
        }
        lrcAdapter.setLrcLines(new ArrayList<>());
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
                        int idx = raw.indexOf("value=");
                        if (idx >= 0) {
                            String val = raw.substring(idx + 6).trim();
                            if (!val.isEmpty()) return val;
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private List<LrcLine> parseLrcFile(File file) {
        List<LrcLine> lines = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = br.readLine()) != null) {
                parseLrcLine(line, lines);
            }
        } catch (Exception ignored) {}
        java.util.Collections.sort(lines, (a, b) -> Long.compare(a.getTime(), b.getTime()));
        return lines;
    }

    private List<LrcLine> parseLrcContent(String content) {
        List<LrcLine> lines = new ArrayList<>();
        if (content == null || content.isEmpty()) return lines;
        for (String line : content.split("\n")) {
            parseLrcLine(line, lines);
        }
        java.util.Collections.sort(lines, (a, b) -> Long.compare(a.getTime(), b.getTime()));
        return lines;
    }

    private void parseLrcLine(String line, List<LrcLine> lines) {
        Pattern p = Pattern.compile("\\[(\\d+):(\\d+)(?:[.:](\\d+))?]");
        Matcher m = p.matcher(line);
        String text = line.replaceAll("\\[\\d+:?\\d*\\.?\\d*]", "").trim();
        while (m.find()) {
            String g1 = m.group(1), g2 = m.group(2), g3 = m.group(3);
            if (g1 == null || g2 == null) continue;
            long min = Long.parseLong(g1);
            long sec = Long.parseLong(g2);
            long ms = 0;
            if (g3 != null) {
                ms = Long.parseLong(g3);
                if (g3.length() == 2) ms *= 10;
            }
            long time = min * 60000L + sec * 1000L + ms;
            lines.add(new LrcLine(time, text));
        }
    }

    private String formatTime(int ms) {
        int totalSec = ms / 1000;
        return String.format(Locale.CHINA, "%02d:%02d", totalSec / 60, totalSec % 60);
    }

    private final Runnable progressRunnable = new Runnable() {
        @Override
        public void run() {
            if (musicPlayer != null && musicPlayer.isPrepared() && musicPlayer.isPlaying()) {
                int current = musicPlayer.getCurrentPosition();
                int duration = musicPlayer.getDuration();
                if (duration > 0) {
                    seekProgress.setProgress(current);
                    seekMiniProgress.setProgress(current);
                }
                textCurrentTime.setText(formatTime(current));
                int l = lrcAdapter.updateCurrentLine(current);
                if (l != -1) recyclerLyrics.smoothScrollToPosition(l);
                progressHandler.postDelayed(this, 200);
            }
        }
    };

    private void startProgressUpdater() {
        progressHandler.removeCallbacks(progressRunnable);
        progressHandler.post(progressRunnable);
    }

    private void stopProgressUpdater() {
        progressHandler.removeCallbacks(progressRunnable);
    }
    
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            MusicPlayerService.MusicBinder binder = (MusicPlayerService.MusicBinder) service;
            musicService = binder.getService();
            musicPlayer = musicService.getTagPlayer();
            serviceBound = true;
            musicService.setCallback(serviceCallback);
            initPlayer();
            loadTags();
            syncPlayerUI();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            serviceBound = false;
            musicService = null;
        }
    };
    
    private final MusicPlayerService.ServiceCallback serviceCallback = action -> runOnUiThread(() -> {
        switch (action) {
            case MusicPlayerService.ACTION_PLAY_PAUSE:
                togglePlayPause();
                break;
            case MusicPlayerService.ACTION_NEXT:
                playNext();
                break;
            case MusicPlayerService.ACTION_PREV:
                playPrev();
                break;
            case MusicPlayerService.ACTION_STOP:
                updatePlayPauseUI();
                break;
            case MusicPlayerService.ACTION_PAUSED_BY_SERVICE:
                updatePlayPauseUI();
                break;
        }
    });

    private void syncPlayerUI() {
        if (musicService == null) return;
        MusicPlayer active = musicService.getActivePlayer();
        
        isTagPlayerActive = (active == musicPlayer);
        
        String currentPath = active.getCurrentPath();
        if (currentPath != null && !currentPath.isEmpty()) {
            boolean found = false;
            for (int i = 0; i < playOrder.size(); i++) {
                if (currentPath.equals(playOrder.get(i).getPath())) {
                    currentSongIndex = i;
                    songAdapter.setSelectedPosition(i);
                    found = true;
                    break;
                }
            }
            if (!found) {
                for (Song s : originalTagSongs) {
                    if (currentPath.equals(s.getPath())) {
                        found = true;
                        break;
                    }
                }
            }
        }

        int d = active.getDuration();
        seekProgress.setMax(d);
        seekMiniProgress.setMax(d);
        textTotalTime.setText(formatTime(d));
        updatePlayPauseUI();
        updatePlayerInfo();
        bottomSheetPlayer.setVisibility(View.VISIBLE);
        sheetBehavior.setHideable(false);
        startProgressUpdater();
    }

    private void updatePlayPauseUI() {
        if (musicService == null) return;
        boolean playing = musicService.getActivePlayer().isPlaying();
        int icon = playing ? R.drawable.ic_pause_vector : R.drawable.ic_play_vector;
        btnPlayPause.setImageResource(icon);
        btnMiniPlayPause.setImageResource(icon);
        if (playing) startProgressUpdater(); else stopProgressUpdater();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopProgressUpdater();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (serviceBound && musicService != null) {
            musicService.setCallback(serviceCallback);
            syncPlayerUI();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopProgressUpdater();
        if (serviceBound) {
            if (musicService != null) { musicService.setCallback(null); musicService.setTagPlaybackListener(null); }
            try { unbindService(serviceConnection); } catch (Exception ignored) {}
            serviceBound = false;
        }
        musicPlayer = null;
    }
}
