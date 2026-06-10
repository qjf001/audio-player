package com.musicplayer.app.adapter;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.musicplayer.app.model.LrcLine;
import java.util.ArrayList;
import java.util.List;

public class LrcAdapter extends RecyclerView.Adapter<LrcAdapter.ViewHolder> {
    private List<LrcLine> lrcLines = new ArrayList<>();
    private int currentLine = -1;

    public void setLrcLines(List<LrcLine> lines) {
        this.lrcLines = lines != null ? lines : new ArrayList<>();
        this.currentLine = -1;
        notifyDataSetChanged();
    }

    public int updateCurrentLine(long time) {
        int newLine = -1;
        for (int i = 0; i < lrcLines.size(); i++) {
            if (time >= lrcLines.get(i).getTime()) {
                newLine = i;
            } else {
                break;
            }
        }
        if (newLine != currentLine) {
            int oldLine = currentLine;
            currentLine = newLine;
            if (oldLine != -1) notifyItemChanged(oldLine);
            if (currentLine != -1) notifyItemChanged(currentLine);
            return currentLine;
        }
        return -1;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(android.R.layout.simple_list_item_1, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        LrcLine line = lrcLines.get(position);
        holder.textView.setText(line.getText());
        holder.textView.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        
        if (position == currentLine) {
            holder.textView.setTextColor(Color.WHITE);
            holder.textView.setTextSize(18);
        } else {
            holder.textView.setTextColor(Color.parseColor("#80FFFFFF"));
            holder.textView.setTextSize(14);
        }
    }

    @Override
    public int getItemCount() {
        return lrcLines.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView textView;
        ViewHolder(View itemView) {
            super(itemView);
            textView = itemView.findViewById(android.R.id.text1);
        }
    }
}
