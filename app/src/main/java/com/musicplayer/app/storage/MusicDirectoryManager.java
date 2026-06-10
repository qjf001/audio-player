package com.musicplayer.app.storage;

import android.content.Context;
import android.content.SharedPreferences;

public class MusicDirectoryManager {
    private static final String PREFS_NAME = "music_player_prefs";
    private static final String KEY_MUSIC_DIRECTORY = "music_directory";
    private SharedPreferences prefs;

    public MusicDirectoryManager(Context context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public void setMusicDirectory(String path) {
        prefs.edit().putString(KEY_MUSIC_DIRECTORY, path).apply();
    }

    public String getMusicDirectory() {
        return prefs.getString(KEY_MUSIC_DIRECTORY, null);
    }

    public boolean hasCustomDirectory() {
        return getMusicDirectory() != null && !getMusicDirectory().isEmpty();
    }

    public void clearMusicDirectory() {
        prefs.edit().remove(KEY_MUSIC_DIRECTORY).apply();
    }
}
