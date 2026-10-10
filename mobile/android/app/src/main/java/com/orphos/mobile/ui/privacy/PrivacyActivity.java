package com.orphos.mobile.ui.privacy;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.orphos.mobile.OrphosApp;
import com.orphos.mobile.R;
import com.orphos.mobile.core.config.AppConfig;

/**
 * Privacy &amp; data — AI-transparency notice, granular (opt-in) consent, and in-app data
 * export / deletion (DSAR). Satisfies the GDPR-as-global-template, APPI and CCPA
 * expectations from the regional standards. No pre-ticked boxes.
 */
public class PrivacyActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_privacy);

        AppConfig config = OrphosApp.get().config();
        MaterialToolbar toolbar = findViewById(R.id.privacy_toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        MaterialSwitch analytics = findViewById(R.id.privacy_analytics);
        MaterialSwitch personalisation = findViewById(R.id.privacy_personalisation);
        analytics.setChecked(config.analyticsConsent());
        personalisation.setChecked(config.personalisationConsent());
        analytics.setOnCheckedChangeListener((b, checked) -> config.setAnalyticsConsent(checked));
        personalisation.setOnCheckedChangeListener((b, checked) -> config.setPersonalisationConsent(checked));

        findViewById(R.id.privacy_export).setOnClickListener(v -> exportData(config));
        findViewById(R.id.privacy_delete).setOnClickListener(v -> confirmDelete());
    }

    /** Data portability — hand the user a copy of their local data. */
    private void exportData(AppConfig config) {
        String text = "Orphos — your data export\n"
                + "Tenant: " + OrphosApp.get().session().tenantSlug() + "\n"
                + "Analytics consent: " + config.analyticsConsent() + "\n"
                + "Personalisation consent: " + config.personalisationConsent() + "\n"
                + "Mode: " + (config.isDemoMode() ? "demo" : "connected") + "\n"
                + "Note: full server-side export is delivered via the platform DSAR flow.";
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.privacy_export));
        send.putExtra(Intent.EXTRA_TEXT, text);
        startActivity(Intent.createChooser(send, getString(R.string.privacy_export)));
    }

    /** Right to erasure — clears local session/data and returns to sign-in. */
    private void confirmDelete() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.privacy_delete)
                .setMessage(R.string.privacy_delete_msg)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.privacy_delete_confirm, (d, w) -> {
                    Toast.makeText(this, R.string.privacy_deleted, Toast.LENGTH_LONG).show();
                    com.orphos.mobile.auth.AuthManager.signOut(this);
                })
                .show();
    }
}
