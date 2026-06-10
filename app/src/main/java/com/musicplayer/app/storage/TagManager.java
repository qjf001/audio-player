package com.musicplayer.app.storage;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.musicplayer.app.model.Song;
import com.musicplayer.app.model.Tag;

import java.util.ArrayList;
import java.util.List;

public class TagManager {
    private DatabaseHelper dbHelper;

    public TagManager(Context context) {
        dbHelper = new DatabaseHelper(context);
    }

    public long addTag(String tagName) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(DatabaseHelper.COLUMN_NAME, tagName);
        values.put(DatabaseHelper.COLUMN_TAG_TYPE, "tag");
        long id = db.insertWithOnConflict(DatabaseHelper.TABLE_TAGS, null, values, SQLiteDatabase.CONFLICT_IGNORE);
        if (id == -1) {
            Cursor cursor = db.query(DatabaseHelper.TABLE_TAGS, new String[]{DatabaseHelper.COLUMN_ID},
                    DatabaseHelper.COLUMN_NAME + " = ?", new String[]{tagName}, null, null, null);
            if (cursor.moveToFirst()) {
                id = cursor.getLong(0);
            }
            cursor.close();
        }
        db.close();
        return id;
    }

    public long addCategoryTag(String tagName) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(DatabaseHelper.COLUMN_NAME, tagName);
        values.put(DatabaseHelper.COLUMN_TAG_TYPE, "category");
        long id = db.insertWithOnConflict(DatabaseHelper.TABLE_TAGS, null, values, SQLiteDatabase.CONFLICT_IGNORE);
        if (id == -1) {
            Cursor cursor = db.query(DatabaseHelper.TABLE_TAGS, new String[]{DatabaseHelper.COLUMN_ID},
                    DatabaseHelper.COLUMN_NAME + " = ?", new String[]{tagName}, null, null, null);
            if (cursor.moveToFirst()) {
                id = cursor.getLong(0);
            }
            cursor.close();
        }
        db.close();
        return id;
    }

    public void deleteTag(long tagId) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        db.delete(DatabaseHelper.TABLE_SONG_TAGS, DatabaseHelper.COLUMN_TAG_ID + " = ?",
                new String[]{String.valueOf(tagId)});
        db.delete(DatabaseHelper.TABLE_TAGS, DatabaseHelper.COLUMN_ID + " = ?",
                new String[]{String.valueOf(tagId)});
        db.close();
    }

    public List<Tag> getAllTags() {
        List<Tag> tags = new ArrayList<>();
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor cursor = db.query(DatabaseHelper.TABLE_TAGS, null, null, null, null, null, null);
        if (cursor.moveToFirst()) {
            do {
                Tag tag = new Tag();
                tag.setId(cursor.getLong(cursor.getColumnIndexOrThrow(DatabaseHelper.COLUMN_ID)));
                tag.setName(cursor.getString(cursor.getColumnIndexOrThrow(DatabaseHelper.COLUMN_NAME)));
                int typeIdx = cursor.getColumnIndex(DatabaseHelper.COLUMN_TAG_TYPE);
                tag.setType(typeIdx >= 0 ? cursor.getString(typeIdx) : null);
                tags.add(tag);
            } while (cursor.moveToNext());
        }
        cursor.close();
        db.close();
        return tags;
    }

    public List<Tag> getUserTags() {
        List<Tag> tags = new ArrayList<>();
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor cursor = db.query(DatabaseHelper.TABLE_TAGS, null,
                DatabaseHelper.COLUMN_TAG_TYPE + " = ?", new String[]{"tag"},
                null, null, DatabaseHelper.COLUMN_NAME + " ASC");
        if (cursor.moveToFirst()) {
            do {
                Tag tag = new Tag();
                tag.setId(cursor.getLong(cursor.getColumnIndexOrThrow(DatabaseHelper.COLUMN_ID)));
                tag.setName(cursor.getString(cursor.getColumnIndexOrThrow(DatabaseHelper.COLUMN_NAME)));
                tag.setType("tag");
                tags.add(tag);
            } while (cursor.moveToNext());
        }
        cursor.close();
        db.close();
        return tags;
    }

    public List<Tag> getCategoryTags() {
        List<Tag> tags = new ArrayList<>();
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor cursor = db.query(DatabaseHelper.TABLE_TAGS, null,
                DatabaseHelper.COLUMN_TAG_TYPE + " = ?", new String[]{"category"},
                null, null, DatabaseHelper.COLUMN_NAME + " ASC");
        if (cursor.moveToFirst()) {
            do {
                Tag tag = new Tag();
                tag.setId(cursor.getLong(cursor.getColumnIndexOrThrow(DatabaseHelper.COLUMN_ID)));
                tag.setName(cursor.getString(cursor.getColumnIndexOrThrow(DatabaseHelper.COLUMN_NAME)));
                tag.setType("category");
                tags.add(tag);
            } while (cursor.moveToNext());
        }
        cursor.close();
        db.close();
        return tags;
    }

    public int getSongCountByCategory(String category) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM " + DatabaseHelper.TABLE_SONGS +
                " WHERE " + DatabaseHelper.COLUMN_CATEGORY + " = ?", new String[]{category});
        int count = 0;
        if (cursor.moveToFirst()) {
            count = cursor.getInt(0);
        }
        cursor.close();
        db.close();
        return count;
    }

    public void addTagToSong(long songId, long tagId) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(DatabaseHelper.COLUMN_SONG_ID, songId);
        values.put(DatabaseHelper.COLUMN_TAG_ID, tagId);
        db.insertWithOnConflict(DatabaseHelper.TABLE_SONG_TAGS, null, values, SQLiteDatabase.CONFLICT_IGNORE);
        db.close();
    }

    public void removeTagFromSong(long songId, long tagId) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        db.delete(DatabaseHelper.TABLE_SONG_TAGS,
                DatabaseHelper.COLUMN_SONG_ID + " = ? AND " + DatabaseHelper.COLUMN_TAG_ID + " = ?",
                new String[]{String.valueOf(songId), String.valueOf(tagId)});
        db.close();
    }

    public void deleteSong(long songId) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        db.delete(DatabaseHelper.TABLE_SONG_TAGS, DatabaseHelper.COLUMN_SONG_ID + " = ?",
                new String[]{String.valueOf(songId)});
        db.delete(DatabaseHelper.TABLE_SONGS, DatabaseHelper.COLUMN_ID + " = ?",
                new String[]{String.valueOf(songId)});
        db.close();
    }

    public void clearAllSongs() {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        db.delete(DatabaseHelper.TABLE_SONG_TAGS, null, null);
        db.delete(DatabaseHelper.TABLE_SONGS, null, null);
        db.close();
    }

    public List<String> getTagsForSong(long songId) {
        List<String> tags = new ArrayList<>();
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        String query = "SELECT t." + DatabaseHelper.COLUMN_NAME +
                " FROM " + DatabaseHelper.TABLE_TAGS + " t" +
                " INNER JOIN " + DatabaseHelper.TABLE_SONG_TAGS + " st" +
                " ON t." + DatabaseHelper.COLUMN_ID + " = st." + DatabaseHelper.COLUMN_TAG_ID +
                " WHERE st." + DatabaseHelper.COLUMN_SONG_ID + " = ?";
        Cursor cursor = db.rawQuery(query, new String[]{String.valueOf(songId)});
        if (cursor.moveToFirst()) {
            do {
                tags.add(cursor.getString(0));
            } while (cursor.moveToNext());
        }
        cursor.close();
        db.close();
        return tags;
    }

    public List<Song> getSongsForTag(long tagId) {
        List<Song> songs = new ArrayList<>();
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        String query = "SELECT s.*" +
                " FROM " + DatabaseHelper.TABLE_SONGS + " s" +
                " INNER JOIN " + DatabaseHelper.TABLE_SONG_TAGS + " st" +
                " ON s." + DatabaseHelper.COLUMN_ID + " = st." + DatabaseHelper.COLUMN_SONG_ID +
                " WHERE st." + DatabaseHelper.COLUMN_TAG_ID + " = ?" +
                " ORDER BY s." + DatabaseHelper.COLUMN_SORT_ORDER + " ASC";
        Cursor cursor = db.rawQuery(query, new String[]{String.valueOf(tagId)});
        if (cursor.moveToFirst()) {
            do {
                Song song = new Song();
                song.setId(cursor.getLong(cursor.getColumnIndexOrThrow(DatabaseHelper.COLUMN_ID)));
                String path = cursor.getString(cursor.getColumnIndexOrThrow(DatabaseHelper.COLUMN_PATH));
                song.setPath(path);
                song.setTitle(Song.formatTitleFromPath(path));
                song.setArtist(cursor.getString(cursor.getColumnIndexOrThrow(DatabaseHelper.COLUMN_ARTIST)));
                song.setAlbum(cursor.getString(cursor.getColumnIndexOrThrow(DatabaseHelper.COLUMN_ALBUM)));
                song.setDuration(cursor.getLong(cursor.getColumnIndexOrThrow(DatabaseHelper.COLUMN_DURATION)));
                song.setLastPosition(cursor.getInt(cursor.getColumnIndexOrThrow(DatabaseHelper.COLUMN_LAST_POS)));
                song.setCategory(cursor.getString(cursor.getColumnIndexOrThrow(DatabaseHelper.COLUMN_CATEGORY)));
                song.setLyrics(cursor.getString(cursor.getColumnIndexOrThrow(DatabaseHelper.COLUMN_LYRICS)));
                song.setTags(getTagsForSong(song.getId()));
                songs.add(song);
            } while (cursor.moveToNext());
        }
        cursor.close();
        db.close();
        return songs;
    }

    public int getSongCountForTag(long tagId) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        String query = "SELECT COUNT(*) FROM " + DatabaseHelper.TABLE_SONG_TAGS + 
                " WHERE " + DatabaseHelper.COLUMN_TAG_ID + " = ?";
        Cursor cursor = db.rawQuery(query, new String[]{String.valueOf(tagId)});
        int count = 0;
        if (cursor.moveToFirst()) {
            count = cursor.getInt(0);
        }
        cursor.close();
        db.close();
        return count;
    }

    public int getTotalSongCount() {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        String query = "SELECT COUNT(*) FROM " + DatabaseHelper.TABLE_SONGS;
        Cursor cursor = db.rawQuery(query, null);
        int count = 0;
        if (cursor.moveToFirst()) {
            count = cursor.getInt(0);
        }
        cursor.close();
        db.close();
        return count;
    }

    public List<Long> getSongIdsForTag(long tagId) {
        List<Long> ids = new ArrayList<>();
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor cursor = db.query(DatabaseHelper.TABLE_SONG_TAGS, new String[]{DatabaseHelper.COLUMN_SONG_ID},
                DatabaseHelper.COLUMN_TAG_ID + " = ?", new String[]{String.valueOf(tagId)}, null, null, null);
        if (cursor != null) {
            while (cursor.moveToNext()) {
                ids.add(cursor.getLong(0));
            }
            cursor.close();
        }
        db.close();
        return ids;
    }

    public long addSong(Song song) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        
        // 获取当前最大的排序值，新歌放在最后
        int maxOrder = 0;
        Cursor maxCursor = db.rawQuery("SELECT MAX(" + DatabaseHelper.COLUMN_SORT_ORDER + ") FROM " + DatabaseHelper.TABLE_SONGS, null);
        if (maxCursor.moveToFirst()) {
            maxOrder = maxCursor.getInt(0);
        }
        maxCursor.close();

        ContentValues values = new ContentValues();
        values.put(DatabaseHelper.COLUMN_TITLE, song.getTitle());
        values.put(DatabaseHelper.COLUMN_ARTIST, song.getArtist());
        values.put(DatabaseHelper.COLUMN_ALBUM, song.getAlbum());
        values.put(DatabaseHelper.COLUMN_PATH, song.getPath());
        values.put(DatabaseHelper.COLUMN_DURATION, song.getDuration());
        values.put(DatabaseHelper.COLUMN_LAST_POS, song.getLastPosition());
        values.put(DatabaseHelper.COLUMN_CATEGORY, song.getCategory());
        values.put(DatabaseHelper.COLUMN_LYRICS, song.getLyrics());
        values.put(DatabaseHelper.COLUMN_SORT_ORDER, maxOrder + 1);

        long id = db.insertWithOnConflict(DatabaseHelper.TABLE_SONGS, null, values, SQLiteDatabase.CONFLICT_IGNORE);
        if (id == -1) {
            Cursor cursor = db.query(DatabaseHelper.TABLE_SONGS, new String[]{DatabaseHelper.COLUMN_ID},
                    DatabaseHelper.COLUMN_PATH + " = ?", new String[]{song.getPath()}, null, null, null);
            if (cursor.moveToFirst()) {
                id = cursor.getLong(0);
            }
            cursor.close();
        }
        db.close();
        return id;
    }

    public List<Song> getAllSongs() {
        List<Song> songs = new ArrayList<>();
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor cursor = db.query(DatabaseHelper.TABLE_SONGS, null, null, null, null, null, DatabaseHelper.COLUMN_SORT_ORDER + " ASC");
        if (cursor.moveToFirst()) {
            do {
                Song song = new Song();
                song.setId(cursor.getLong(cursor.getColumnIndexOrThrow(DatabaseHelper.COLUMN_ID)));
                String path = cursor.getString(cursor.getColumnIndexOrThrow(DatabaseHelper.COLUMN_PATH));
                song.setPath(path);
                song.setTitle(Song.formatTitleFromPath(path));
                song.setArtist(cursor.getString(cursor.getColumnIndexOrThrow(DatabaseHelper.COLUMN_ARTIST)));
                song.setAlbum(cursor.getString(cursor.getColumnIndexOrThrow(DatabaseHelper.COLUMN_ALBUM)));
                song.setDuration(cursor.getLong(cursor.getColumnIndexOrThrow(DatabaseHelper.COLUMN_DURATION)));
                song.setLastPosition(cursor.getInt(cursor.getColumnIndexOrThrow(DatabaseHelper.COLUMN_LAST_POS)));
                song.setCategory(cursor.getString(cursor.getColumnIndexOrThrow(DatabaseHelper.COLUMN_CATEGORY)));
                song.setLyrics(cursor.getString(cursor.getColumnIndexOrThrow(DatabaseHelper.COLUMN_LYRICS)));
                song.setTags(getTagsForSong(song.getId()));
                songs.add(song);
            } while (cursor.moveToNext());
        }
        cursor.close();
        db.close();
        return songs;
    }

    public void updateSongsOrder(List<Song> songs) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        db.beginTransaction();
        try {
            for (int i = 0; i < songs.size(); i++) {
                ContentValues values = new ContentValues();
                values.put(DatabaseHelper.COLUMN_SORT_ORDER, i);
                db.update(DatabaseHelper.TABLE_SONGS, values, DatabaseHelper.COLUMN_ID + " = ?",
                        new String[]{String.valueOf(songs.get(i).getId())});
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
            db.close();
        }
    }

    public void updateLastPosition(long songId, int position) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(DatabaseHelper.COLUMN_LAST_POS, position);
        db.update(DatabaseHelper.TABLE_SONGS, values, DatabaseHelper.COLUMN_ID + " = ?",
                new String[]{String.valueOf(songId)});
        db.close();
    }

    public void updateSongMetadata(long songId, String artist, String lyrics) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(DatabaseHelper.COLUMN_ARTIST, artist);
        values.put(DatabaseHelper.COLUMN_LYRICS, lyrics);
        db.update(DatabaseHelper.TABLE_SONGS, values, DatabaseHelper.COLUMN_ID + " = ?",
                new String[]{String.valueOf(songId)});
        db.close();
    }
}
