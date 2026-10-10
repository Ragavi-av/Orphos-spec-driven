package com.orphos.mobile.ui.common;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.orphos.mobile.R;

/**
 * Temporary section screen for tabs not yet built out (AI Advisors, Business,
 * Scribbles, Financials). Each is implemented in later phases per the Blueprint.
 */
public class PlaceholderFragment extends Fragment {

    private static final String ARG_TITLE = "title";
    private static final String ARG_BODY = "body";

    public static PlaceholderFragment newInstance(String title, String body) {
        PlaceholderFragment f = new PlaceholderFragment();
        Bundle b = new Bundle();
        b.putString(ARG_TITLE, title);
        b.putString(ARG_BODY, body);
        f.setArguments(b);
        return f;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_placeholder, container, false);
        TextView title = v.findViewById(R.id.placeholder_title);
        TextView body = v.findViewById(R.id.placeholder_body);
        Bundle args = getArguments();
        if (args != null) {
            title.setText(args.getString(ARG_TITLE, ""));
            body.setText(args.getString(ARG_BODY, ""));
        }
        return v;
    }
}
