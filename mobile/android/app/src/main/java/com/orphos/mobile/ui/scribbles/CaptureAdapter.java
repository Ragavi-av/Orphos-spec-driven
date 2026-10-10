package com.orphos.mobile.ui.scribbles;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.orphos.mobile.R;
import com.orphos.mobile.data.model.Capture;

import java.util.ArrayList;
import java.util.List;

/** Recent captures list. */
public class CaptureAdapter extends RecyclerView.Adapter<CaptureAdapter.VH> {

    public interface Listener { void onOpen(Capture c); }

    private final List<Capture> items = new ArrayList<>();
    private final Listener listener;

    public CaptureAdapter(Listener listener) { this.listener = listener; }

    public void submit(List<Capture> data) {
        items.clear();
        if (data != null) items.addAll(data);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_capture, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Capture c = items.get(position);
        h.title.setText(c.title);
        h.meta.setText(label(c.type) + " · " + (c.createdAt == null ? "" : c.createdAt));
        h.icon.setImageResource(iconFor(c.type));
        h.itemView.setOnClickListener(v -> listener.onOpen(c));
    }

    static int iconFor(String type) {
        if ("VOICE".equals(type) || "AUDIO".equals(type)) return R.drawable.ic_mic;
        if ("IMAGE".equals(type) || "PHOTO".equals(type)) return R.drawable.ic_camera;
        if ("HANDWRITING".equals(type)) return R.drawable.ic_scribbles;
        return R.drawable.ic_note;
    }

    static String label(String type) {
        if (type == null) return "Note";
        switch (type) {
            case "VOICE":
            case "AUDIO": return "Voice";
            case "IMAGE":
            case "PHOTO": return "Image";
            case "HANDWRITING": return "Handwriting";
            default: return "Note";
        }
    }

    @Override
    public int getItemCount() { return items.size(); }

    static class VH extends RecyclerView.ViewHolder {
        final ImageView icon; final TextView title, meta;
        VH(@NonNull View v) {
            super(v);
            icon = v.findViewById(R.id.capture_icon);
            title = v.findViewById(R.id.capture_title);
            meta = v.findViewById(R.id.capture_meta);
        }
    }
}
