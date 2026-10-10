package com.orphos.mobile.ui.scribbles;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ZoomState;
import androidx.camera.view.CameraController;
import androidx.camera.view.LifecycleCameraController;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;
import androidx.core.widget.ImageViewCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.orphos.mobile.R;
import com.orphos.mobile.core.capture.CaptureFiles;
import com.orphos.mobile.data.model.Capture;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * "Capture image" — the designed camera screen (Figma "ORPHOS-1"). The viewfinder shows the
 * live back camera (CameraX), with the device's own zoom levels, pinch-to-zoom and
 * tap-to-focus. AI framing detects the text in each preview frame on the device and guides
 * the framing and lighting; Auto Detect takes the photo by itself once the content is well
 * framed and the phone is steady.
 *
 * <p>The shutter and Gallery both put the image in the same frame for review; nothing is
 * processed until the user taps Done (or Use Photo). Only then does the image go through
 * processing (OCR → AI summary) and on to the review screen, where the user corrects and
 * names the note before it is saved.</p>
 */
public class CaptureActivity extends AppCompatActivity {

    private static final String STATE_IMAGE = "pending_image";
    private static final String STATE_TYPE = "pending_type";
    private static final String STATE_MIME = "pending_mime";
    private static final String PREF_FRAMING = "capture_ai_framing";
    private static final String PREF_AUTO = "capture_auto_detect";
    /** How long the content must stay well framed and steady before Auto Detect captures. */
    private static final long AUTO_CAPTURE_HOLD_MS = 1200;
    /** Lighting must read the same for this many frames before the hint changes, so it doesn't flicker. */
    private static final int LIGHTING_DEBOUNCE_FRAMES = 3;

    private ActivityResultLauncher<PickVisualMediaRequest> pickImage;
    private ActivityResultLauncher<String> cameraPermission;

    private PreviewView preview;
    private ImageView still;
    private FramingOverlayView overlay;
    private TextView cameraMsg, framingPill, framingLabel, framingSub;
    private View lightingCard, zoomGroup;
    private final TextView[] zoomPills = new TextView[4];

    private LifecycleCameraController camera;
    private FramingAnalyzer analyzer;
    private ExecutorService analysisExecutor;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;

    private boolean framingOn, autoOn, capturing, handedOff;
    private List<Float> zoomPresets = Collections.singletonList(1f);

    /** The captured / picked image waiting in the frame for Done; null while the camera is live. */
    private File pendingImage;
    private String pendingType, pendingMime;

    // Live guidance state
    private float[] lastContent;
    private long steadySince;
    private CaptureGuidance.Lighting shownLighting = CaptureGuidance.Lighting.UNKNOWN;
    private CaptureGuidance.Lighting candidateLighting = CaptureGuidance.Lighting.UNKNOWN;
    private int candidateFrames;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_capture);
        // Shared header: status dot and bell behave exactly as on Home (see HeaderActions).
        com.orphos.mobile.ui.common.HeaderActions.bind(this, null);
        prefs = getPreferences(MODE_PRIVATE);

        preview = findViewById(R.id.capture_preview);
        still = findViewById(R.id.capture_still);
        overlay = findViewById(R.id.capture_overlay);
        cameraMsg = findViewById(R.id.capture_camera_msg);
        framingPill = findViewById(R.id.capture_framing_pill);
        framingLabel = findViewById(R.id.capture_framing_label);
        framingSub = findViewById(R.id.capture_framing_sub);
        lightingCard = findViewById(R.id.capture_lighting);
        zoomGroup = findViewById(R.id.capture_zoom);
        zoomPills[0] = findViewById(R.id.zoom_half);
        zoomPills[1] = findViewById(R.id.zoom_1x);
        zoomPills[2] = findViewById(R.id.zoom_2x);
        zoomPills[3] = findViewById(R.id.zoom_5x);
        // The viewfinder's rounded corners clip the live preview and the still.
        View viewfinder = findViewById(R.id.capture_viewfinder);
        viewfinder.setClipToOutline(true);

        pickImage = registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), uri -> {
            if (uri != null) ingestUri(uri);
        });
        cameraPermission = registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            if (granted) startCamera(); else showCameraMessage(R.string.capture_camera_permission_msg);
        });

        findViewById(R.id.capture_back).setOnClickListener(v -> finish());
        findViewById(R.id.capture_shutter).setOnClickListener(v -> takePhoto());
        findViewById(R.id.capture_use_photo).setOnClickListener(v -> {
            if (pendingImage != null) done(); else takePhoto();
        });
        findViewById(R.id.capture_done).setOnClickListener(v -> done());
        findViewById(R.id.capture_retake).setOnClickListener(v -> retake());
        findViewById(R.id.capture_tips).setOnClickListener(v -> showTips());
        findViewById(R.id.capture_gallery).setOnClickListener(v -> pickImage.launch(
                new PickVisualMediaRequest.Builder()
                        .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                        .build()));
        findViewById(R.id.capture_auto).setOnClickListener(v -> toggleAuto());
        findViewById(R.id.capture_share).setOnClickListener(v -> share());
        cameraMsg.setOnClickListener(v -> requestCamera());
        for (int i = 0; i < zoomPills.length; i++) {
            final int index = i;
            zoomPills[i].setOnClickListener(v -> {
                if (camera != null && index < zoomPresets.size()) camera.setZoomRatio(zoomPresets.get(index));
            });
        }

        SwitchMaterial framingSwitch = findViewById(R.id.capture_framing_switch);
        framingOn = prefs.getBoolean(PREF_FRAMING, true);
        autoOn = prefs.getBoolean(PREF_AUTO, false);
        framingSwitch.setChecked(framingOn);
        framingSwitch.setOnCheckedChangeListener((b, on) -> {
            framingOn = on;
            prefs.edit().putBoolean(PREF_FRAMING, on).apply();
            renderToggles();
            updateAnalysis();
        });
        renderToggles();

        if (savedInstanceState != null && savedInstanceState.getString(STATE_IMAGE) != null) {
            File restored = new File(savedInstanceState.getString(STATE_IMAGE));
            if (restored.exists()) {
                showStill(restored, savedInstanceState.getString(STATE_TYPE),
                        savedInstanceState.getString(STATE_MIME));
            }
        }
        if (pendingImage == null) requestCamera();
        renderActions();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Permission may have been granted from the system settings while we were away.
        if (camera == null && pendingImage == null && hasCameraPermission()) startCamera();
    }

    // ---- Camera ----------------------------------------------------------------------------

    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestCamera() {
        if (hasCameraPermission()) { startCamera(); return; }
        if (cameraMsg.getVisibility() == View.VISIBLE
                && !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
            // Denied permanently: the system no longer shows the prompt, so open the app's settings.
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", getPackageName(), null)));
            return;
        }
        cameraPermission.launch(Manifest.permission.CAMERA);
    }

    private void startCamera() {
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            showCameraMessage(R.string.capture_camera_unavailable);
            return;
        }
        cameraMsg.setVisibility(View.GONE);
        if (camera == null) {
            camera = new LifecycleCameraController(this);
            camera.setCameraSelector(CameraSelector.DEFAULT_BACK_CAMERA);
            camera.setEnabledUseCases(CameraController.IMAGE_CAPTURE | CameraController.IMAGE_ANALYSIS);
            // Full-resolution, best-quality stills: OCR needs the detail.
            camera.setImageCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY);
            camera.setPinchToZoomEnabled(true);
            camera.setTapToFocusEnabled(true);
            preview.setController(camera);
            camera.getZoomState().observe(this, this::onZoomState);
            camera.getInitializationFuture().addListener(() -> {
                try {
                    camera.getInitializationFuture().get();
                    if (!camera.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                        if (camera.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
                            camera.setCameraSelector(CameraSelector.DEFAULT_FRONT_CAMERA);
                        } else {
                            showCameraMessage(R.string.capture_camera_unavailable);
                        }
                    }
                } catch (Exception e) {
                    showCameraMessage(R.string.capture_camera_unavailable);
                }
            }, ContextCompat.getMainExecutor(this));
        }
        if (pendingImage == null) {
            camera.bindToLifecycle(this);
            updateAnalysis();
        }
    }

    private void showCameraMessage(int resId) {
        cameraMsg.setText(resId);
        cameraMsg.setVisibility(pendingImage == null ? View.VISIBLE : View.GONE);
        hideGuidance();
    }

    private boolean cameraLive() {
        return camera != null && pendingImage == null && cameraMsg.getVisibility() != View.VISIBLE;
    }

    /** Frame analysis runs only while the camera is live and AI framing or Auto Detect needs it. */
    private void updateAnalysis() {
        if (camera == null) return;
        if (cameraLive() && (framingOn || autoOn)) {
            if (analyzer == null) {
                analysisExecutor = Executors.newSingleThreadExecutor();
                analyzer = new FramingAnalyzer(ContextCompat.getMainExecutor(this), this::onFrame);
            }
            camera.setImageAnalysisAnalyzer(analysisExecutor, analyzer);
        } else {
            camera.clearImageAnalysisAnalyzer();
            hideGuidance();
        }
    }

    private void onZoomState(ZoomState z) {
        if (z == null) return;
        zoomPresets = CaptureGuidance.zoomPresets(z.getMinZoomRatio(), z.getMaxZoomRatio());
        int active = CaptureGuidance.activeZoom(zoomPresets, z.getZoomRatio());
        for (int i = 0; i < zoomPills.length; i++) {
            TextView pill = zoomPills[i];
            if (i >= zoomPresets.size()) { pill.setVisibility(View.GONE); continue; }
            boolean on = i == active;
            // The active pill shows the exact ratio while pinching between presets, like the stock camera.
            float shown = on ? z.getZoomRatio() : zoomPresets.get(i);
            pill.setVisibility(View.VISIBLE);
            pill.setText(getString(R.string.zoom_value, CaptureGuidance.zoomLabel(shown)));
            pill.setBackgroundResource(on ? R.drawable.bg_zoom_active : 0);
            pill.setTextColor(on ? 0xFF111111 : ContextCompat.getColor(this, R.color.text));
        }
    }

    // ---- Live guidance ---------------------------------------------------------------------

    private void onFrame(@Nullable Rect content, CaptureGuidance.Lighting lighting) {
        if (!cameraLive() || isFinishing()) return;
        float[] box = content == null ? null
                : new float[] { content.left, content.top, content.right, content.bottom };
        CaptureGuidance.Framing framing = CaptureGuidance.framing(box, preview.getWidth(), preview.getHeight());

        long now = SystemClock.elapsedRealtime();
        boolean steady = framing == CaptureGuidance.Framing.GOOD
                && CaptureGuidance.iou(box, lastContent) >= CaptureGuidance.STEADY_IOU;
        if (!steady) steadySince = now;
        lastContent = box;

        if (lighting == candidateLighting) {
            candidateFrames++;
        } else {
            candidateLighting = lighting;
            candidateFrames = 1;
        }
        if (candidateFrames >= LIGHTING_DEBOUNCE_FRAMES) shownLighting = candidateLighting;

        boolean readyToShoot = framing == CaptureGuidance.Framing.GOOD
                && shownLighting != CaptureGuidance.Lighting.DARK;
        boolean autoFire = autoOn && readyToShoot && !capturing && now - steadySince >= AUTO_CAPTURE_HOLD_MS;

        renderFraming(framing, content, autoOn && readyToShoot, autoFire);
        renderLighting();
        if (autoFire) takePhoto();
    }

    private void renderFraming(CaptureGuidance.Framing framing, @Nullable Rect content,
                               boolean holding, boolean firing) {
        int green = ContextCompat.getColor(this, R.color.green);
        int amber = ContextCompat.getColor(this, R.color.orange);
        boolean good = framing == CaptureGuidance.Framing.GOOD;

        if (framingOn) {
            overlay.setContent(content == null ? null : new RectF(content), good ? green : amber);
            overlay.setCornerColor(good ? green : 0xFFFFFFFF);
        } else {
            overlay.setContent(null, 0);
            overlay.setCornerColor(0xFFFFFFFF);
        }
        if (!framingOn && !autoOn) { framingPill.setVisibility(View.GONE); return; }

        int text, bg;
        boolean check = false;
        if (firing) { text = R.string.capture_framing_capturing; bg = R.drawable.bg_pill_green; check = true; }
        else if (holding) { text = R.string.capture_framing_steady; bg = R.drawable.bg_pill_green; check = true; }
        else switch (framing) {
            case GOOD:      text = R.string.capture_good_framing; bg = R.drawable.bg_pill_green; check = true; break;
            case CUT_OFF:   text = R.string.capture_framing_cut_off; bg = R.drawable.bg_pill_amber; break;
            case TOO_SMALL: text = R.string.capture_framing_closer; bg = R.drawable.bg_pill_amber; break;
            default:        text = R.string.capture_framing_searching; bg = R.drawable.bg_pill_neutral; break;
        }
        framingPill.setText(text);
        framingPill.setBackgroundResource(bg);
        framingPill.setCompoundDrawablesRelativeWithIntrinsicBounds(check ? R.drawable.ic_check_circle : 0, 0, 0, 0);
        framingPill.setVisibility(View.VISIBLE);
    }

    private void renderLighting() {
        if (!framingOn || shownLighting == CaptureGuidance.Lighting.UNKNOWN) {
            lightingCard.setVisibility(View.GONE);
            return;
        }
        boolean good = shownLighting == CaptureGuidance.Lighting.GOOD;
        int color = ContextCompat.getColor(this, good ? R.color.green : R.color.orange);
        ImageView icon = findViewById(R.id.capture_lighting_icon);
        TextView title = findViewById(R.id.capture_lighting_title);
        TextView sub = findViewById(R.id.capture_lighting_sub);
        icon.setImageResource(good ? R.drawable.ic_check_circle : R.drawable.ic_bulb);
        ImageViewCompat.setImageTintList(icon, ColorStateList.valueOf(color));
        title.setTextColor(color);
        if (good) {
            title.setText(R.string.capture_good_lighting);
            sub.setText(R.string.capture_good_lighting_sub);
        } else if (shownLighting == CaptureGuidance.Lighting.DARK) {
            title.setText(R.string.capture_low_light);
            sub.setText(R.string.capture_low_light_sub);
        } else {
            title.setText(R.string.capture_glare);
            sub.setText(R.string.capture_glare_sub);
        }
        lightingCard.setVisibility(View.VISIBLE);
    }

    private void hideGuidance() {
        framingPill.setVisibility(View.GONE);
        lightingCard.setVisibility(View.GONE);
        overlay.setContent(null, 0);
        overlay.setCornerColor(0xFFFFFFFF);
        lastContent = null;
        shownLighting = candidateLighting = CaptureGuidance.Lighting.UNKNOWN;
        candidateFrames = 0;
    }

    private void toggleAuto() {
        if (!autoOn && camera == null) { toast(R.string.capture_auto_needs_camera); return; }
        autoOn = !autoOn;
        prefs.edit().putBoolean(PREF_AUTO, autoOn).apply();
        steadySince = SystemClock.elapsedRealtime();
        renderToggles();
        updateAnalysis();
        toast(autoOn ? R.string.capture_auto_on : R.string.capture_auto_off);
    }

    private void renderToggles() {
        framingLabel.setText(framingOn ? R.string.capture_framing : R.string.capture_framing_off);
        framingSub.setText(framingOn ? R.string.capture_framing_sub : R.string.capture_framing_off_sub);
        int color = ContextCompat.getColor(this, autoOn ? R.color.primary : R.color.text_secondary);
        ImageView auto = findViewById(R.id.capture_auto);
        ImageViewCompat.setImageTintList(auto, ColorStateList.valueOf(color));
        auto.setSelected(autoOn);
        ((TextView) findViewById(R.id.capture_auto_label)).setTextColor(color);
    }

    // ---- Capture / gallery → preview in the frame ------------------------------------------

    /** Shutter (or Auto Detect): take a full-resolution photo from the live camera into the frame. */
    private void takePhoto() {
        if (pendingImage != null || capturing) return;
        if (camera == null) { requestCamera(); return; }
        File out = CaptureFiles.newPhotoFile(this);
        if (out == null) { toast(R.string.capture_failed); return; }
        capturing = true;
        preview.animate().alpha(0.25f).setDuration(80)
                .withEndAction(() -> preview.animate().alpha(1f).setDuration(160).start()).start();
        camera.takePicture(new ImageCapture.OutputFileOptions.Builder(out).build(),
                ContextCompat.getMainExecutor(this), new ImageCapture.OnImageSavedCallback() {
                    @Override
                    public void onImageSaved(ImageCapture.OutputFileResults results) {
                        capturing = false;
                        if (isDestroyed()) { deleteQuietly(out); return; }
                        showStill(out, Capture.TYPE_PHOTO, "image/jpeg");
                    }

                    @Override
                    public void onError(ImageCaptureException e) {
                        capturing = false;
                        deleteQuietly(out);
                        if (!isDestroyed()) toast(R.string.capture_failed);
                    }
                });
    }

    /** Gallery: copy the picked image to a cache file, byte for byte, and show it in the frame. */
    private void ingestUri(Uri uri) {
        io.execute(() -> {
            File file = CaptureFiles.fromUri(this, uri, "gallery");
            String mime = getContentResolver().getType(uri);
            runOnUiThread(() -> {
                if (isDestroyed()) { deleteQuietly(file); return; }
                if (file == null) { toast(R.string.capture_image_unreadable); return; }
                discardPending();
                showStill(file, Capture.TYPE_IMAGE,
                        mime != null && mime.startsWith("image/") ? mime : "image/jpeg");
            });
        });
    }

    /**
     * Puts the image in the viewfinder and pauses the camera. Nothing is processed here —
     * the image waits for Done, Retake, or another Gallery pick.
     */
    private void showStill(File image, String type, String mime) {
        pendingImage = image;
        pendingType = type != null ? type : Capture.TYPE_IMAGE;
        pendingMime = mime != null ? mime : "image/jpeg";
        if (camera != null) {
            camera.clearImageAnalysisAnalyzer();
            camera.unbind();
        }
        hideGuidance();
        cameraMsg.setVisibility(View.GONE);
        zoomGroup.setVisibility(View.GONE);
        still.setImageDrawable(null);
        still.setVisibility(View.VISIBLE);
        renderActions();

        int maxDim = Math.max(1080, Math.max(still.getWidth(), still.getHeight()));
        String path = image.getAbsolutePath();
        io.execute(() -> {
            Bitmap bmp = CaptureFiles.decodeForDisplay(path, maxDim);
            runOnUiThread(() -> {
                if (isDestroyed() || pendingImage == null || !pendingImage.getAbsolutePath().equals(path)) return;
                if (bmp == null) {
                    discardPending();
                    backToLive();
                    toast(R.string.capture_image_unreadable);
                    return;
                }
                still.setImageBitmap(bmp);
                toast(R.string.capture_ready_hint);
            });
        });
    }

    /** Retake: drop the image in the frame and return to the live camera. */
    private void retake() {
        if (pendingImage == null) return;
        discardPending();
        backToLive();
    }

    private void backToLive() {
        still.setImageDrawable(null);
        still.setVisibility(View.GONE);
        zoomGroup.setVisibility(View.VISIBLE);
        steadySince = SystemClock.elapsedRealtime();
        renderActions();
        if (camera != null) {
            camera.bindToLifecycle(this);
            updateAnalysis();
        } else {
            requestCamera();
        }
    }

    /** Done: only now is the image sent on for OCR and the AI summary, then the review. */
    private void done() {
        if (pendingImage == null) { toast(R.string.capture_nothing_yet); return; }
        handedOff = true;
        Intent i = new Intent(this, ProcessingActivity.class);
        i.putExtra(ProcessingActivity.EXTRA_IMAGE, pendingImage.getAbsolutePath());
        i.putExtra(ProcessingActivity.EXTRA_IMAGE_TYPE, pendingType);
        i.putExtra(ProcessingActivity.EXTRA_IMAGE_MIME, pendingMime);
        startActivity(i);
        finish();
    }

    private void renderActions() {
        boolean ready = pendingImage != null;
        View retake = findViewById(R.id.capture_retake);
        View done = findViewById(R.id.capture_done);
        View share = findViewById(R.id.capture_share);
        retake.setEnabled(ready);
        retake.setAlpha(ready ? 1f : 0.45f);
        done.setAlpha(ready ? 1f : 0.45f);
        share.setAlpha(ready ? 1f : 0.45f);
    }

    private void share() {
        if (pendingImage == null) { toast(R.string.capture_nothing_yet); return; }
        Intent send = new Intent(Intent.ACTION_SEND)
                .setType(pendingMime)
                .putExtra(Intent.EXTRA_STREAM, CaptureFiles.shareableUri(this, pendingImage))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(send, getString(R.string.capture_share_chooser)));
    }

    private void showTips() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.capture_tips_title)
                .setMessage(R.string.capture_tips_body)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void discardPending() {
        deleteQuietly(pendingImage);
        pendingImage = null;
    }

    private static void deleteQuietly(@Nullable File f) {
        //noinspection ResultOfMethodCallIgnored
        if (f != null) f.delete();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (pendingImage != null) {
            outState.putString(STATE_IMAGE, pendingImage.getAbsolutePath());
            outState.putString(STATE_TYPE, pendingType);
            outState.putString(STATE_MIME, pendingMime);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Leaving without Done: the unsent image is thrown away (it lives only in the cache).
        if (isFinishing() && !handedOff) discardPending();
        if (camera != null) camera.clearImageAnalysisAnalyzer();
        if (analyzer != null) analyzer.close();
        if (analysisExecutor != null) analysisExecutor.shutdown();
        io.shutdown();
    }

    private void toast(int resId) {
        View root = findViewById(android.R.id.content);
        Snackbar.make(root, getString(resId), Snackbar.LENGTH_SHORT).show();
    }
}
