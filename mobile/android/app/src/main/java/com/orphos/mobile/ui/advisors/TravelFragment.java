package com.orphos.mobile.ui.advisors;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.snackbar.Snackbar;
import com.orphos.mobile.OrphosApp;
import com.orphos.mobile.R;
import com.orphos.mobile.data.model.TravelItem;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** "Travel" tab — trip cards extracted from the tenant's mail (route, mode, date/time, purpose). */
public class TravelFragment extends Fragment {

    private final List<TravelItem> data = new ArrayList<>();
    private Adapter adapter;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_travel, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        RecyclerView list = (RecyclerView) view;
        list.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new Adapter();
        list.setAdapter(adapter);
    }

    /** Re-read on every return to the screen, so a change made on the web shows up here. */
    @Override
    public void onResume() {
        super.onResume();
        OrphosApp.get().repo().travel((trips, fromMock) -> {
            if (!isAdded()) return;
            data.clear();
            data.addAll(trips);
            adapter.notifyDataSetChanged();
        });
    }

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_TRIP = 1;

    private class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @Override
        public int getItemViewType(int position) {
            return position == 0 ? TYPE_HEADER : TYPE_TRIP;
        }

        @NonNull @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inf = LayoutInflater.from(parent.getContext());
            if (viewType == TYPE_HEADER) {
                return new HeaderVH(inf.inflate(R.layout.item_travel_summary, parent, false));
            }
            return new VH(inf.inflate(R.layout.item_travel, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (holder instanceof HeaderVH hv) {
                hv.count.setText(String.format(Locale.getDefault(),
                        getString(R.string.travel_trips_count), data.size()));
                return;
            }
            VH h = (VH) holder;
            TravelItem t = data.get(position - 1);
            h.time.setText(t.time != null ? t.time : "—");
            h.route.setText(t.route());
            h.sub.setText(subLine(t));
            h.purpose.setText(t.purpose != null ? t.purpose : t.route());
            bindStatus(h.status, t.confirmationStatus);
            h.row.setOnClickListener(v -> Snackbar.make(v,
                    t.route() + (t.date != null ? " · " + t.date : ""), Snackbar.LENGTH_SHORT).show());
        }

        @Override public int getItemCount() { return data.size() + 1; }

        class VH extends RecyclerView.ViewHolder {
            final TextView time, route, sub, purpose, status;
            final View row;
            VH(@NonNull View v) {
                super(v);
                row = v.findViewById(R.id.travel_row);
                time = v.findViewById(R.id.travel_time);
                route = v.findViewById(R.id.travel_route);
                sub = v.findViewById(R.id.travel_sub);
                purpose = v.findViewById(R.id.travel_purpose);
                status = v.findViewById(R.id.travel_status);
            }
        }

        class HeaderVH extends RecyclerView.ViewHolder {
            final TextView count;
            HeaderVH(@NonNull View v) {
                super(v);
                count = v.findViewById(R.id.summary_count);
            }
        }
    }

    /** "Flight · Fri, 15 Aug · 6:30 AM" from whichever fields are present. */
    private String subLine(TravelItem t) {
        StringBuilder sb = new StringBuilder();
        if (t.mode != null) sb.append(t.mode);
        if (t.date != null) { if (sb.length() > 0) sb.append(" · "); sb.append(t.date); }
        if (t.time != null) { if (sb.length() > 0) sb.append(" · "); sb.append(t.time); }
        return sb.toString();
    }

    /** Confirmation chip — green for CONFIRMED, orange otherwise (TENTATIVE). */
    private void bindStatus(TextView chip, String status) {
        if (status == null || status.isEmpty()) { chip.setVisibility(View.GONE); return; }
        chip.setVisibility(View.VISIBLE);
        chip.setText(status);
        boolean confirmed = "CONFIRMED".equalsIgnoreCase(status);
        chip.setTextColor(ContextCompat.getColor(requireContext(), confirmed ? R.color.green : R.color.orange));
    }
}
