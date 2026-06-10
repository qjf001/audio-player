package com.musicplayer.app.model;

import java.util.ArrayList;
import java.util.List;

public class Song {
    private long id;
    private String title;
    private String artist;
    private String album;
    private String path;
    private long duration;
    private int lastPosition; // 新增：播放进度 (毫秒)
    private String category;  // 新增：音频大类 (音乐/评书/相声/小品)
    private String lyrics;    // 新增：歌词信息
    private List<String> tags;

    public Song() {
        this.tags = new ArrayList<>();
        this.category = "音乐"; // 默认分类为音乐
    }

    public String getLyrics() {
        return lyrics;
    }

    public void setLyrics(String lyrics) {
        this.lyrics = lyrics;
    }

    public int getLastPosition() {
        return lastPosition;
    }

    public void setLastPosition(int lastPosition) {
        this.lastPosition = lastPosition;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public Song(long id, String title, String artist, String album, String path, long duration) {
        this.id = id;
        this.title = title;
        this.artist = artist;
        this.album = album;
        this.path = path;
        this.duration = duration;
        this.tags = new ArrayList<>();
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getArtist() {
        return artist;
    }

    public void setArtist(String artist) {
        this.artist = artist;
    }

    public String getAlbum() {
        return album;
    }

    public void setAlbum(String album) {
        this.album = album;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public long getDuration() {
        return duration;
    }

    public void setDuration(long duration) {
        this.duration = duration;
    }

    public List<String> getTags() {
        return tags;
    }

    public void setTags(List<String> tags) {
        this.tags = tags;
    }

    public static String formatTitleFromPath(String path) {
        if (path == null || path.isEmpty() || path.equalsIgnoreCase("null")) return "未知歌曲";
        
        // 同时处理正斜杠和反斜杠
        String filename = path;
        int lastSlash = Math.max(path.lastIndexOf("/"), path.lastIndexOf("\\"));
        if (lastSlash != -1) {
            filename = path.substring(lastSlash + 1);
        }
        
        if (filename.isEmpty() || filename.equalsIgnoreCase("null")) return "未知歌曲";

        int dotIndex = filename.lastIndexOf(".");
        if (dotIndex > 0) {
            return filename.substring(0, dotIndex);
        }
        return filename;
    }

    public void addTag(String tag) {
        if (!tags.contains(tag)) {
            tags.add(tag);
        }
    }

    public void removeTag(String tag) {
        tags.remove(tag);
    }

    public boolean hasTag(String tag) {
        return tags.contains(tag);
    }
}
