package com.orphos.mobile.ui.settings;

import android.os.Bundle;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;
import com.orphos.mobile.OrphosApp;
import com.orphos.mobile.R;
import com.orphos.mobile.core.config.AppConfig;

/**
 * Per-client backend configuration for on-prem installs: API endpoint, tenant slug,
 * and a demo-mode toggle (mock data when no backend is reachable).
 */
public class SettingsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        AppConfig config = OrphosApp.get().config();

        TextInputEditText baseUrl = findViewById(R.id.cfg_base_url);
        TextInputEditText tenant = findViewById(R.id.cfg_tenant);
        TextInputEditText keycloakUrl = findViewById(R.id.cfg_keycloak_url);
        MaterialSwitch demo = findViewById(R.id.cfg_demo);
        MaterialSwitch alertsHigh = findViewById(R.id.cfg_alerts_high);
        MaterialSwitch appLock = findViewById(R.id.cfg_app_lock);
        com.google.android.material.chip.ChipGroup langGroup = findViewById(R.id.cfg_lang_group);

        baseUrl.setText(config.apiBaseUrl());
        tenant.setText(config.tenantSlug());
        keycloakUrl.setText(config.keycloakUrl());
        demo.setChecked(config.isDemoMode());
        alertsHigh.setChecked(config.alertsIncludeHigh());
        appLock.setChecked(config.appLockEnabled());
        langGroup.check(chipForLang(config.language()));

        findViewById(R.id.cfg_save).setOnClickListener(v -> {
            config.setApiBaseUrl(String.valueOf(baseUrl.getText()).trim());
            config.setTenantSlug(String.valueOf(tenant.getText()).trim());
            // Blank keeps the build default rather than saving an unusable empty URL.
            String kc = String.valueOf(keycloakUrl.getText()).trim();
            if (!kc.isEmpty()) config.setKeycloakUrl(kc);
            config.setDemoMode(demo.isChecked());
            config.setAlertsIncludeHigh(alertsHigh.isChecked());
            config.setAppLockEnabled(appLock.isChecked());

            // Language: persist and apply immediately (re-renders in the chosen locale).
            String lang = langForChip(langGroup.getCheckedChipId());
            config.setLanguage(lang);
            OrphosApp.get().applySavedLanguage();

            OrphosApp.get().api().rebuild();
            Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show();
            finish();
        });

        findViewById(R.id.cfg_privacy).setOnClickListener(v ->
                startActivity(new android.content.Intent(this,
                        com.orphos.mobile.ui.privacy.PrivacyActivity.class)));

        findViewById(R.id.cfg_sign_out).setOnClickListener(v -> signOut());
    }

    /** Full sign-out (SSO session, tokens, persona, grants) and back to the login screen. */
    private void signOut() {
        Toast.makeText(this, R.string.signed_out, Toast.LENGTH_SHORT).show();
        com.orphos.mobile.auth.AuthManager.signOut(this);
    }

    private int chipForLang(String lang) {
        return "ja".equalsIgnoreCase(lang) ? R.id.lang_ja : R.id.lang_en;
    }

    private String langForChip(int id) {
        return id == R.id.lang_ja ? "ja" : "en";
    }
}
