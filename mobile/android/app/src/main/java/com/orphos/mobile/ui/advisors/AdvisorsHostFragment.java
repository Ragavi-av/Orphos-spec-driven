package com.orphos.mobile.ui.advisors;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;
import com.orphos.mobile.MainActivity;
import com.orphos.mobile.R;
import com.orphos.mobile.ui.business.ApprovalsFragment;
import com.orphos.mobile.ui.business.DecisionLogFragment;
import com.orphos.mobile.ui.business.NotificationsFragment;
import com.orphos.mobile.ui.business.TasksListFragment;
import com.orphos.mobile.ui.business.TravelListFragment;
import com.orphos.mobile.ui.common.HeaderActions;

/**
 * Business host — five tabs: Approvals (decisions needing an exec response), Tasks (actions
 * for the day), Notifications (critical/high alerts), Travel (upcoming trips) and the
 * Decision Log (the full decision history from decision-log-service). Home's stat widgets
 * and the Next Travel card deep-link straight to one of these via
 * {@link MainActivity#openBusinessTab(int)}.
 *
 * <p>Decision Log is APPENDED at index 4 rather than slotted next to Approvals, which it
 * reads more naturally beside: the deep-link indices above are positional and Home passes
 * 0-2 literally, so inserting anywhere before Travel would silently re-point every one of
 * those links at the wrong tab.
 */
public class AdvisorsHostFragment extends Fragment {

    private HeaderActions header;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_advisors_host, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        View back = view.findViewById(R.id.biz_back);
        if (back != null) back.setOnClickListener(v -> {
            if (getActivity() instanceof MainActivity m) m.selectTab(R.id.nav_home);
        });

        // Shared header: status dot, bell and search behave exactly as on Home. The bell
        // re-opens this host on Notifications (re-selecting the tab rebuilds it).
        header = HeaderActions.bind(this, view, view.findViewById(R.id.biz_search));

        ViewPager2 pager = view.findViewById(R.id.advisor_pager);
        TabLayout tabs = view.findViewById(R.id.advisor_tabs);

        final int[] titleRes = {R.string.tab_approvals, R.string.tab_tasks,
                R.string.tab_notifications, R.string.tab_travel, R.string.tab_decisions};

        pager.setAdapter(new FragmentStateAdapter(this) {
            @NonNull @Override
            public Fragment createFragment(int position) {
                switch (position) {
                    case 1:  return new TasksListFragment();
                    case 2:  return new NotificationsFragment();
                    case 3:  return new TravelListFragment();
                    case 4:  return new DecisionLogFragment();
                    default: return new ApprovalsFragment();
                }
            }
            @Override public int getItemCount() { return titleRes.length; }
        });

        new TabLayoutMediator(tabs, pager, (tab, position) ->
                tab.setText(getString(titleRes[position]))).attach();

        // Honor a deep-link from Home's stat widgets (Approvals=0, Tasks=1, Notifications=2).
        if (getActivity() instanceof MainActivity m) {
            int inner = m.consumeBusinessInnerTab();
            if (inner >= 0 && inner < titleRes.length) pager.setCurrentItem(inner, false);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        header.refreshStatus();
    }
}
