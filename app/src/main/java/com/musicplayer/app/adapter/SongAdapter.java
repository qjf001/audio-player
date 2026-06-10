package com.musicplayer.app.adapter;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.musicplayer.app.R;
import com.musicplayer.app.model.Song;

import java.util.List;

public class SongAdapter extends RecyclerView.Adapter<SongAdapter.SongViewHolder> {
    private List<Song> songs;
    private OnSongClickListener clickListener;
    private OnSongLongClickListener longClickListener;
    private int selectedPosition = -1;

    public interface OnSongClickListener {
        void onSongClick(Song song, int position);
    }

    public interface OnSongLongClickListener {
        void onSongLongClick(Song song, int position);
    }

    public SongAdapter(List<Song> songs) {
        this.songs = songs;
    }

    public void setOnSongClickListener(OnSongClickListener listener) {
        this.clickListener = listener;
    }

    public void setOnSongLongClickListener(OnSongLongClickListener listener) {
        this.longClickListener = listener;
    }

    public void updateSongs(List<Song> songs) {
        this.songs = songs;
        notifyDataSetChanged();
    }

    public void setSelectedPosition(int position) {
        int previousSelected = this.selectedPosition;
        this.selectedPosition = position;
        notifyItemChanged(previousSelected);
        notifyItemChanged(selectedPosition);
    }

    public int getSelectedPosition() {
        return selectedPosition;
    }

    @NonNull
    @Override
    public SongViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_song, parent, false);
        return new SongViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull SongViewHolder holder, int position) {
        Song song = songs.get(position);
        holder.titleText.setText(song.getTitle());
        
        StringBuilder details = new StringBuilder();
        boolean hasArtist = song.getArtist() != null && !song.getArtist().isEmpty() && 
                          !song.getArtist().equals("<unknown>") && !song.getArtist().equals("未知歌手");
        boolean hasAlbum = song.getAlbum() != null && !song.getAlbum().isEmpty() && 
                         !song.getAlbum().equals("<unknown>") && !song.getAlbum().equals("未知专辑");

        if (hasArtist) {
            details.append(song.getArtist());
        }
        
        if (hasAlbum) {
            if (hasArtist) details.append(" - ");
            details.append(song.getAlbum());
        }
        
        holder.artistAlbumText.setText(details.toString());
        holder.artistAlbumText.setVisibility(details.length() == 0 ? View.GONE : View.VISIBLE);
        
        String tagsStr = "";
        if (song.getTags() != null && !song.getTags().isEmpty()) {
            tagsStr = String.join(", ", song.getTags());
        }
        holder.tagsText.setText(tagsStr);
        holder.tagsText.setVisibility(tagsStr.isEmpty() ? View.GONE : View.VISIBLE);
        
        // 渲染进度条（仅针对长音频大类）
        String category = song.getCategory();
        if (category != null && !category.equals("音乐") && song.getLastPosition() > 0 && song.getDuration() > 0) {
            holder.layoutProgress.setVisibility(View.VISIBLE);
            int percent = (int) ((song.getLastPosition() * 100L) / song.getDuration());
            holder.progressBar.setMax(100);
            holder.progressBar.setProgress(percent);
            holder.textPercent.setText("已听 " + percent + "%");
        } else {
            holder.layoutProgress.setVisibility(View.GONE);
        }
        
        if (position == selectedPosition) {
            holder.itemView.setBackgroundColor(Color.parseColor("#E1BEE7")); // Light purple highlight
            holder.titleText.setTextColor(Color.parseColor("#7B1FA2"));
        } else {
            holder.itemView.setBackgroundResource(android.R.drawable.screen_background_light_transparent);
            // 彻底解决字体颜色看不见的问题：直接从系统资源获取标准文本颜色
            android.util.TypedValue typedValue = new android.util.TypedValue();
            if (holder.itemView.getContext().getTheme().resolveAttribute(android.R.attr.textColorPrimary, typedValue, true)) {
                holder.titleText.setTextColor(typedValue.resourceId != 0 ? 
                        androidx.core.content.ContextCompat.getColor(holder.itemView.getContext(), typedValue.resourceId) : 
                        typedValue.data);
            } else {
                holder.titleText.setTextColor(Color.DKGRAY);
            }
        }
        
        holder.itemView.setOnClickListener(v -> {
            if (clickListener != null) {
                clickListener.onSongClick(song, position);
            }
        });
        
        holder.itemView.setOnLongClickListener(v -> {
            if (longClickListener != null) {
                longClickListener.onSongLongClick(song, position);
                return true;
            }
            return false;
        });
    }

    @Override
    public int getItemCount() {
        return songs != null ? songs.size() : 0;
    }

    static class SongViewHolder extends RecyclerView.ViewHolder {
        TextView titleText;
        TextView artistAlbumText;
        TextView tagsText;
        View layoutProgress;
        android.widget.ProgressBar progressBar;
        TextView textPercent;

        public SongViewHolder(@NonNull View itemView) {
            super(itemView);
            titleText = itemView.findViewById(R.id.text_title);
            artistAlbumText = itemView.findViewById(R.id.text_artist_album);
            tagsText = itemView.findViewById(R.id.text_tags);
            layoutProgress = itemView.findViewById(R.id.layout_progress_container);
            progressBar = itemView.findViewById(R.id.item_progress_bar);
            textPercent = itemView.findViewById(R.id.text_progress_percent);
        }
    }
}
