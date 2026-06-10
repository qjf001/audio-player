package com.musicplayer.app;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;

import java.util.Locale;

public class LocaleHelper {

    private static final String PREF_NAME = "app_prefs";
    private static final String KEY_LANGUAGE = "language";

    public static final String LANG_SYSTEM = "system";
    public static final String LANG_ZH = "zh";
    public static final String LANG_ZH_TW = "zh-TW";
    public static final String LANG_EN = "en";

    /** 获取用户保存的语言代码 */
    public static String getSavedLanguage(Context context) {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getString(KEY_LANGUAGE, LANG_SYSTEM);
    }

    /** 保存语言设置 */
    public static void saveLanguage(Context context, String lang) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_LANGUAGE, lang).apply();
    }

    /** 在 attachBaseContext 中调用，应用保存的语言 */
    public static Context applyLocale(Context context) {
        String lang = getSavedLanguage(context);
        if (LANG_SYSTEM.equals(lang)) {
            return context;
        }
        Locale locale = parseLocale(lang);
        return applyLocaleToContext(context, locale);
    }

    /** 设置语言并返回 true（如果语言确实发生了变化） */
    public static boolean setLocale(Activity activity, String lang) {
        saveLanguage(activity, lang);
        Locale locale;
        if (LANG_SYSTEM.equals(lang)) {
            locale = Resources.getSystem().getConfiguration().locale;
        } else {
            locale = parseLocale(lang);
        }
        applyLocaleToContext(activity, locale);
        return true;
    }

    private static Locale parseLocale(String lang) {
        if (LANG_ZH_TW.equals(lang)) {
            return new Locale("zh", "TW");
        } else if (LANG_EN.equals(lang)) {
            return Locale.ENGLISH;
        } else {
            return Locale.SIMPLIFIED_CHINESE;
        }
    }

    private static Context applyLocaleToContext(Context context, Locale locale) {
        Locale.setDefault(locale);
        Configuration config = new Configuration(context.getResources().getConfiguration());
        config.setLocale(locale);
        return context.createConfigurationContext(config);
    }
}
