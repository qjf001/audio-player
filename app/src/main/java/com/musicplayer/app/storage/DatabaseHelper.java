package com.musicplayer.app.storage;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

public class DatabaseHelper extends SQLiteOpenHelper {
    private static final String DATABASE_NAME = "musicplayer.db";
    private static final int DATABASE_VERSION = 6;

    public static final String TABLE_SONGS = "songs";
    public static final String TABLE_TAGS = "tags";
    public static final String TABLE_SONG_TAGS = "song_tags";

    public static final String COLUMN_ID = "id";
    public static final String COLUMN_TITLE = "title";
    public static final String COLUMN_ARTIST = "artist";
    public static final String COLUMN_ALBUM = "album";
    public static final String COLUMN_PATH = "path";
    public static final String COLUMN_DURATION = "duration";
    public static final String COLUMN_LAST_POS = "last_position"; // 新增：播放进度记忆
    public static final String COLUMN_CATEGORY = "category"; // 新增：音频大类
    public static final String COLUMN_SORT_ORDER = "sort_order"; // 新增：排序位置
    public static final String COLUMN_LYRICS = "lyrics"; // 新增：歌词缓存
    
    public static final String COLUMN_NAME = "name";
    public static final String COLUMN_TAG_TYPE = "tag_type";
    public static final String COLUMN_SONG_ID = "song_id";
    public static final String COLUMN_TAG_ID = "tag_id";

    private static final String CREATE_TABLE_SONGS =
            "CREATE TABLE " + TABLE_SONGS + " (" +
                    COLUMN_ID + " INTEGER PRIMARY KEY AUTOINCREMENT," +
                    COLUMN_TITLE + " TEXT," +
                    COLUMN_ARTIST + " TEXT," +
                    COLUMN_ALBUM + " TEXT," +
                    COLUMN_PATH + " TEXT UNIQUE," +
                    COLUMN_DURATION + " INTEGER," +
                    COLUMN_LAST_POS + " INTEGER DEFAULT 0," +
                    COLUMN_CATEGORY + " TEXT," +
                    COLUMN_SORT_ORDER + " INTEGER DEFAULT 0," +
                    COLUMN_LYRICS + " TEXT)";

    private static final String CREATE_TABLE_TAGS =
            "CREATE TABLE " + TABLE_TAGS + " (" +
                    COLUMN_ID + " INTEGER PRIMARY KEY AUTOINCREMENT," +
                    COLUMN_NAME + " TEXT UNIQUE," +
                    COLUMN_TAG_TYPE + " TEXT DEFAULT '" + com.musicplayer.app.model.Tag.TYPE_TAG + "')";

    private static final String CREATE_TABLE_SONG_TAGS =
            "CREATE TABLE " + TABLE_SONG_TAGS + " (" +
                    COLUMN_SONG_ID + " INTEGER," +
                    COLUMN_TAG_ID + " INTEGER," +
                    "PRIMARY KEY (" + COLUMN_SONG_ID + ", " + COLUMN_TAG_ID + "))";

    public DatabaseHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(CREATE_TABLE_SONGS);
        db.execSQL(CREATE_TABLE_TAGS);
        db.execSQL(CREATE_TABLE_SONG_TAGS);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            try { db.execSQL("ALTER TABLE " + TABLE_SONGS + " ADD COLUMN " + COLUMN_ALBUM + " TEXT"); } catch (Exception e) {}
        }
        if (oldVersion < 3) {
            try {
                db.execSQL("ALTER TABLE " + TABLE_SONGS + " ADD COLUMN " + COLUMN_LAST_POS + " INTEGER DEFAULT 0");
                db.execSQL("ALTER TABLE " + TABLE_SONGS + " ADD COLUMN " + COLUMN_CATEGORY + " TEXT");
            } catch (Exception e) {}
        }
        if (oldVersion < 4) {
            try {
                db.execSQL("ALTER TABLE " + TABLE_SONGS + " ADD COLUMN " + COLUMN_SORT_ORDER + " INTEGER DEFAULT 0");
            } catch (Exception e) {}
        }
        if (oldVersion < 5) {
            try {
                db.execSQL("ALTER TABLE " + TABLE_SONGS + " ADD COLUMN " + COLUMN_LYRICS + " TEXT");
            } catch (Exception e) {
                // 如果失败则重建
                db.execSQL("DROP TABLE IF EXISTS " + TABLE_SONGS);
                db.execSQL("DROP TABLE IF EXISTS " + TABLE_TAGS);
                db.execSQL("DROP TABLE IF EXISTS " + TABLE_SONG_TAGS);
                onCreate(db);
            }
        }
        if (oldVersion < 6) {
            try {
                db.execSQL("ALTER TABLE " + TABLE_TAGS + " ADD COLUMN " + COLUMN_TAG_TYPE + " TEXT DEFAULT 'tag'");
                // 已有标签默认为用户标签类型
                db.execSQL("UPDATE " + TABLE_TAGS + " SET " + COLUMN_TAG_TYPE + " = 'tag' WHERE " + COLUMN_TAG_TYPE + " IS NULL");
            } catch (Exception e) {}
        }
    }
}
