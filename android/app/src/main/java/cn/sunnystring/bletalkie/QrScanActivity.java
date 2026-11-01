package cn.sunnystring.bletalkie;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Paint;
import android.graphics.RectF;
import android.hardware.Camera;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;

import java.io.IOException;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import cn.sunnystring.bletalkie.ui.InviteCode;
import cn.sunnystring.bletalkie.ui.UiKit;

/** Local QR decoding with one bounded in-flight camera frame; nothing is uploaded. */
@SuppressWarnings("deprecation")
public final class QrScanActivity extends Activity implements SurfaceHolder.Callback {
    private static final int CAMERA_PERMISSION = 61;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private final AtomicBoolean decoding = new AtomicBoolean();
    private Camera camera;
    private FrameLayout root;
    private SurfaceView preview;
    private TextView status;
    private Button recovery;
    private boolean resumed, surfaceReady, permissionRequested, returning;
    private int cameraGeneration, previewWidth, previewHeight;
    private long lastFrameAt, lastInvalidAt;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        permissionRequested = state != null && state.getBoolean("permissionRequested");
        buildUi();
    }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        preview = new SurfaceView(this);
        preview.getHolder().addCallback(this);
        root.addView(preview, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        root.addView(new ScanGuide(), new FrameLayout.LayoutParams(-1, -1));

        LinearLayout controls = UiKit.column(this);
        controls.setPadding(dp(24), dp(24), dp(24), dp(24));
        controls.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets safe = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(safe.left + dp(24), safe.top + dp(24), safe.right + dp(24), safe.bottom + dp(24));
            } else view.setPadding(insets.getSystemWindowInsetLeft() + dp(24), insets.getSystemWindowInsetTop() + dp(24),
                    insets.getSystemWindowInsetRight() + dp(24), insets.getSystemWindowInsetBottom() + dp(24));
            return insets;
        });
        TextView title = UiKit.text(this, "扫描队伍二维码", 25, Color.WHITE, true);
        controls.addView(title);
        controls.addView(UiKit.text(this, "对准队友屏幕上的邀请二维码", 14, 0xFFE0E9DF, false), UiKit.block(this, 8));
        View spacer = new View(this);
        controls.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1));
        status = UiKit.text(this, "正在打开相机…", 15, Color.WHITE, true);
        status.setGravity(Gravity.CENTER);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        controls.addView(status, UiKit.block(this, 12));
        TextView privacy = UiKit.text(this, "扫码只在本机完成", 12, 0xFFCFD9CE, false);
        privacy.setGravity(Gravity.CENTER);
        controls.addView(privacy, UiKit.block(this, 8));
        recovery = UiKit.button(this, "重新打开相机", false);
        recovery.setVisibility(View.GONE);
        recovery.setOnClickListener(v -> {
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", getPackageName(), null)));
            } else openCamera();
        });
        controls.addView(recovery, UiKit.block(this, 16));
        Button cancel = UiKit.button(this, "返回", false);
        cancel.setOnClickListener(v -> finish());
        controls.addView(cancel, UiKit.block(this, 12));
        root.addView(controls, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        controls.requestApplyInsets();
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) openCamera();
        else if (!permissionRequested) {
            permissionRequested = true;
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION);
        } else permissionDenied();
    }

    @Override protected void onPause() {
        resumed = false;
        closeCamera();
        super.onPause();
    }

    @Override protected void onDestroy() {
        closeCamera();
        decoder.shutdownNow();
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("permissionRequested", permissionRequested);
        super.onSaveInstanceState(state);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != CAMERA_PERMISSION) return;
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) openCamera();
        else permissionDenied();
    }

    private void permissionDenied() {
        status.setText("扫码需要相机权限。允许后可返回继续；也可以手动填写组队码。");
        recovery.setText("打开权限设置");
        recovery.setVisibility(View.VISIBLE);
    }

    @Override public void surfaceCreated(SurfaceHolder holder) {
        surfaceReady = true;
        openCamera();
    }

    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        // setDisplayOrientation handles rotation; layout preserves the preview's aspect ratio.
        if (camera == null) openCamera();
    }

    @Override public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceReady = false;
        closeCamera();
    }

    private void openCamera() {
        if (!resumed || !surfaceReady || camera != null || returning) return;
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
        Camera opened = null;
        try {
            int cameraId = rearCamera();
            if (cameraId < 0) { cameraError("此设备没有可用相机。请返回，手动填写组队码。"); return; }
            opened = Camera.open(cameraId);
            Camera.Parameters parameters = opened.getParameters();
            Camera.Size size = choosePreview(parameters.getSupportedPreviewSizes());
            previewWidth = size.width;
            previewHeight = size.height;
            parameters.setPreviewSize(previewWidth, previewHeight);
            parameters.setPreviewFormat(ImageFormat.NV21);
            List<String> focus = parameters.getSupportedFocusModes();
            if (focus != null && focus.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE))
                parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            opened.setParameters(parameters);
            int orientation = displayOrientation(cameraId);
            opened.setDisplayOrientation(orientation);
            fitPreview(orientation);
            opened.setPreviewDisplay(preview.getHolder());
            camera = opened;
            final int generation = ++cameraGeneration;
            opened.setPreviewCallbackWithBuffer((bytes, source) -> previewFrame(bytes, source, generation));
            opened.addCallbackBuffer(new byte[previewWidth * previewHeight * 3 / 2]);
            opened.addCallbackBuffer(new byte[previewWidth * previewHeight * 3 / 2]);
            opened.startPreview();
            status.setText("将邀请二维码放入框内，保持片刻");
            recovery.setVisibility(View.GONE);
            preview.setOnClickListener(v -> {
                Camera active = camera;
                if (active != null) try { active.autoFocus(null); } catch (RuntimeException ignored) { }
            });
            preview.setContentDescription("相机预览，点击可对焦");
        } catch (RuntimeException | IOException error) {
            if (opened != null && opened != camera) try { opened.release(); } catch (RuntimeException ignored) { }
            closeCamera();
            cameraError("相机暂不可用。请关闭其他使用相机的应用后重试，或返回填写组队码。");
        }
    }

    private void previewFrame(byte[] bytes, Camera source, int generation) {
        if (bytes == null || !resumed || source != camera || generation != cameraGeneration) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastFrameAt < 220 || !decoding.compareAndSet(false, true)) {
            recycleFrame(source, bytes, generation);
            return;
        }
        lastFrameAt = now;
        int width = previewWidth, height = previewHeight;
        try {
            decoder.execute(() -> {
                String result = null;
                try {
                    MultiFormatReader reader = new MultiFormatReader();
                    Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
                    hints.put(DecodeHintType.POSSIBLE_FORMATS, Collections.singletonList(BarcodeFormat.QR_CODE));
                    hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
                    hints.put(DecodeHintType.CHARACTER_SET, "UTF-8");
                    reader.setHints(hints);
                    // QR finder patterns support all four rotations; the preview is rotated separately.
                    PlanarYUVLuminanceSource luminance = new PlanarYUVLuminanceSource(bytes, width, height, 0, 0, width, height, false);
                    Result decoded = reader.decodeWithState(new BinaryBitmap(new HybridBinarizer(luminance)));
                    result = decoded.getText();
                    reader.reset();
                } catch (Exception noQrYet) { /* The next bounded preview frame will retry. */ }
                String decodedText = result;
                main.post(() -> {
                    decoding.set(false);
                    recycleFrame(source, bytes, generation);
                    if (!resumed || generation != cameraGeneration || returning) return;
                    if (decodedText != null) receivedQr(decodedText);
                });
            });
        } catch (RejectedExecutionException closed) {
            decoding.set(false);
            recycleFrame(source, bytes, generation);
        }
    }

    private void receivedQr(String text) {
        String code = InviteCode.parse(text);
        if (code != null) {
            returning = true;
            setResult(RESULT_OK, new Intent().putExtra("code", code));
            closeCamera();
            finish();
        } else if (SystemClock.elapsedRealtime() - lastInvalidAt > 1800) {
            lastInvalidAt = SystemClock.elapsedRealtime();
            status.setText("这不是本应用的队伍邀请。请扫描队友显示的组队二维码。");
        }
    }

    private void recycleFrame(Camera source, byte[] bytes, int generation) {
        if (source != camera || generation != cameraGeneration) return;
        try { source.addCallbackBuffer(bytes); } catch (RuntimeException ignored) { }
    }

    private void closeCamera() {
        cameraGeneration++;
        Camera active = camera;
        camera = null;
        if (active == null) return;
        try { active.setPreviewCallbackWithBuffer(null); } catch (RuntimeException ignored) { }
        try { active.stopPreview(); } catch (RuntimeException ignored) { }
        try { active.release(); } catch (RuntimeException ignored) { }
    }

    private void cameraError(String text) {
        status.setText(text);
        recovery.setText("重新打开相机");
        recovery.setVisibility(View.VISIBLE);
    }

    private static int rearCamera() {
        int count = Camera.getNumberOfCameras();
        for (int i = 0; i < count; i++) {
            Camera.CameraInfo info = new Camera.CameraInfo();
            Camera.getCameraInfo(i, info);
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) return i;
        }
        return count == 0 ? -1 : 0;
    }

    private Camera.Size choosePreview(List<Camera.Size> sizes) {
        Camera.Size best = sizes.get(0);
        double target = 4.0 / 3.0;
        double bestScore = Double.MAX_VALUE;
        for (Camera.Size size : sizes) {
            int area = size.width * size.height;
            double score = Math.abs((double) size.width / size.height - target) * 5
                    + Math.abs(area - 921600) / 921600.0 + (area > 1280 * 960 ? 10 : 0);
            if (score < bestScore) { bestScore = score; best = size; }
        }
        return best;
    }

    private int displayOrientation(int cameraId) {
        Camera.CameraInfo info = new Camera.CameraInfo();
        Camera.getCameraInfo(cameraId, info);
        int rotation = getWindowManager().getDefaultDisplay().getRotation();
        int degrees = rotation == Surface.ROTATION_90 ? 90 : rotation == Surface.ROTATION_180 ? 180 : rotation == Surface.ROTATION_270 ? 270 : 0;
        if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) return (360 - (info.orientation + degrees) % 360) % 360;
        return (info.orientation - degrees + 360) % 360;
    }

    private void fitPreview(int orientation) {
        int width = root.getWidth(), height = root.getHeight();
        if (width <= 0 || height <= 0) return;
        int visualWidth = orientation % 180 == 0 ? previewWidth : previewHeight;
        int visualHeight = orientation % 180 == 0 ? previewHeight : previewWidth;
        float scale = Math.max((float) width / visualWidth, (float) height / visualHeight);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(Math.round(visualWidth * scale), Math.round(visualHeight * scale), Gravity.CENTER);
        preview.setLayoutParams(params);
    }

    private int dp(float value) { return UiKit.dp(this, value); }

    private final class ScanGuide extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF frame = new RectF();

        ScanGuide() { super(QrScanActivity.this); setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float side = Math.min(getWidth() - dp(56), getHeight() * 0.37f);
            float left = (getWidth() - side) / 2f, top = getHeight() * 0.43f - side / 2f;
            float right = left + side, bottom = top + side;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0x88000000);
            canvas.drawRect(0, 0, getWidth(), top, paint);
            canvas.drawRect(0, bottom, getWidth(), getHeight(), paint);
            canvas.drawRect(0, top, left, bottom, paint);
            canvas.drawRect(right, top, getWidth(), bottom, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2));
            paint.setColor(0xFFDBF1CF);
            frame.set(left, top, right, bottom);
            canvas.drawRoundRect(frame, dp(18), dp(18), paint);
        }
    }
}
