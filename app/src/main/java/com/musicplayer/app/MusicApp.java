package com.musicplayer.app;

import android.content.Context;

public class MusicApp extends android.app.Application {
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LocaleHelper.applyLocale(base));
    }
}
