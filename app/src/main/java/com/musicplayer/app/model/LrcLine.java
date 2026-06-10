package com.musicplayer.app.model;

public class LrcLine implements Comparable<LrcLine> {
    private long time; // milliseconds
    private String text;

    public LrcLine(long time, String text) {
        this.time = time;
        this.text = text;
    }

    public long getTime() {
        return time;
    }

    public String getText() {
        return text;
    }

    @Override
    public int compareTo(LrcLine other) {
        return Long.compare(this.time, other.time);
    }
}
