package com.orphos.mobile;

import android.app.Application;

import com.orphos.mobile.auth.AppLockState;
import com.orphos.mobile.core.config.AppConfig;
import com.orphos.mobile.core.role.AccessState;
import com.orphos.mobile.core.session.SessionStore;
import com.orphos.mobile.data.api.ApiClient;
import com.orphos.mobile.data.repository.OrphosRepository;
import com.orphos.mobile.ui.common.HeaderState;

/**
 * Application entry point. Wires the config, secure session store, API client and
 * the repository that fronts them (with mock fallback in demo mode).
 */
public class OrphosApp extends Application {

    private static OrphosApp instance;
    private AppConfig config;
    private SessionStore sessionStore;
    private ApiClient apiClient;
    private OrphosRepository repository;
    private AccessState accessState;
    private final AppLockState lockState = new AppLockState();
    private HeaderState headerState;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        // Tracks real foreground/background transitions for the biometric app lock.
        registerActivityLifecycleCallbacks(lockState);
        config = new AppConfig(this);
        sessionStore = new SessionStore(this);
        apiClient = new ApiClient(sessionStore, config);
        repository = new OrphosRepository(apiClient, config);
        accessState = new AccessState();
        headerState = new HeaderState(this);
        // Seed the access persona from the last known value so RBAC gating is sensible before
        // /profile returns; refreshed from the backend once online.
        accessState.setPersona(config.persona());

        // Apply the saved UI language (en / ja / "" = follow system) before any UI renders.
        applySavedLanguage();
    }

    /** Applies the persisted language choice via AppCompat's per-app locales. */
    public void applySavedLanguage() {
        String lang = config.language();
        androidx.core.os.LocaleListCompat locales = (lang == null || lang.isEmpty())
                ? androidx.core.os.LocaleListCompat.getEmptyLocaleList()
                : androidx.core.os.LocaleListCompat.forLanguageTags(lang);
        androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(locales);
    }

    public static OrphosApp get() {
        return instance;
    }

    public AppConfig config() {
        return config;
    }

    public SessionStore session() {
        return sessionStore;
    }

    public ApiClient api() {
        return apiClient;
    }

    public OrphosRepository repo() {
        return repository;
    }

    public AccessState accessState() {
        return accessState;
    }

    public AppLockState lockState() {
        return lockState;
    }

    /** Shared header state (availability dot + notification bell), the same on every screen. */
    public HeaderState headerState() {
        return headerState;
    }
}
