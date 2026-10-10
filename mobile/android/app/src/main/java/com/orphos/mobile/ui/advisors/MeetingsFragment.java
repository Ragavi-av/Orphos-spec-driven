package com.orphos.mobile.ui.advisors;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.snackbar.Snackbar;
import com.orphos.mobile.OrphosApp;
import com.orphos.mobile.R;
import com.orphos.mobile.data.model.MeetingBrief;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** "Today's meeting" tab — meeting cards with attendees and AI brief bullet points. */
public class MeetingsFragment extends Fragment {

    private final List<MeetingBrief> data = new ArrayList<>();
    private Adapter adapter;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_meetings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        RecyclerView list = (RecyclerView) view;
        list.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new Adapter();
        list.setAdapter(adapter);
        OrphosApp.get().repo().meetingBriefs((briefs, fromMock) -> {
            data.clear();
            data.addAll(briefs);
            adapter.notifyDataSetChanged();
        });
    }

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_MEETING = 1;

    private class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @Override
        public int getItemViewType(int position) {
            return position == 0 ? TYPE_HEADER : TYPE_MEETING;
        }

        @NonNull @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inf = LayoutInflater.from(parent.getContext());
            if (viewType == TYPE_HEADER) {
                return new HeaderVH(inf.inflate(R.layout.item_meeting_summary, parent, false));
            }
            return new VH(inf.inflate(R.layout.item_meeting, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (holder instanceof HeaderVH hv) {
                hv.count.setText(String.format(Locale.getDefault(),
                        getString(R.string.meetings_today_count), data.size()));
                return;
            }
            VH h = (VH) holder;
            MeetingBrief m = data.get(position - 1);
            h.time.setText(m.time);
            h.title.setText(m.title);
            h.sub.setText(m.location != null ? m.location : "");
            h.more.setText(String.format(Locale.getDefault(), "+%d", Math.max(0, m.attendeeCount)));
            h.download.setOnClickListener(v -> Snackbar.make(v,
                    getString(R.string.meeting_download) + " · " + m.title, Snackbar.LENGTH_SHORT).show());
            View.OnClickListener open = v -> openDetail(m);
            h.row.setOnClickListener(open);
            h.prepare.setOnClickListener(open);
        }

        @Override public int getItemCount() { return data.size() + 1; }

        class VH extends RecyclerView.ViewHolder {
            final TextView time, title, sub, more;
            final com.google.android.material.button.MaterialButton download, prepare;
            final View row;
            VH(@NonNull View v) {
                super(v);
                row = v.findViewById(R.id.meeting_row);
                time = v.findViewById(R.id.meeting_time);
                title = v.findViewById(R.id.meeting_title);
                sub = v.findViewById(R.id.meeting_sub);
                more = v.findViewById(R.id.meeting_more);
                download = v.findViewById(R.id.meeting_download);
                prepare = v.findViewById(R.id.meeting_prepare);
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

    private void openDetail(MeetingBrief m) {
        startActivity(MeetingDetailActivity.intent(requireContext(), m, m.title, m.time, null));
    }

    private int themeColor(int attr) {
        android.util.TypedValue tv = new android.util.TypedValue();
        requireContext().getTheme().resolveAttribute(attr, tv, true);
        return tv.resourceId != 0 ? requireContext().getColor(tv.resourceId) : tv.data;
    }
}
