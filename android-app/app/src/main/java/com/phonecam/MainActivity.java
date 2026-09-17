package com.phonecam;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.*;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import android.graphics.Rect;
import android.hardware.camera2.params.MeteringRectangle;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "PhoneCam";
    private static final int REQUEST_CAMERA = 100;

    private static final int CLIENT_QUEUE_BYTES = 4 * 1024 * 1024;

    private static final int DEFAULT_PORT = 8080;
    private static final long WAKELOCK_TIMEOUT_MS = 3600000L; // 1 hour max
    private static final int CLIENT_READ_TIMEOUT_MS = 15000;
    private static final int DRAIN_JOIN_TIMEOUT_MS = 1000;
    private static final int CAMERA_RETRY_DELAY_MS = 1500;
    private static final int TAP_FOCUS_RESTORE_DELAY_MS = 2500;

    private static final int PREVIEW_MAX_WIDTH = 1280; // on-screen cap; encoder unaffected
    private static final int PREVIEW_MAX_HEIGHT = 720;

    // Zoom slider maps progress p to (p + ZOOM_SEEK_BASE) / 100, e.g. 50 -> 1.0x.
    private static final int ZOOM_SEEK_BASE = 50; // 0.5x in hundredths
    private static final int ZOOM_SEEK_SCALE = 100;

    private static final int FOCUS_RECT_MIN_HALF_PX = 80;
    private static final int FOCUS_RECT_DIVISOR = 12; // rect half = sensor width / 12
    private static final int FOCUS_METERING_WEIGHT = 900;

    private static final String[] QUALITY_LABELS = {
        "1080p 60fps  (H.264)",
        "1080p 30fps  (H.264)",
        "720p  60fps  (H.264)",
        "720p  30fps  (H.264)",
    };
    private static final int[] Q_W   = { 1920, 1920, 1280, 1280 };
    private static final int[] Q_H   = { 1080, 1080,  720,  720 };
    private static final int[] Q_FPS = {   60,   30,   60,   30 };
    private static final int[] Q_BITRATE = { 10_000_000, 7_000_000, 6_000_000, 4_000_000 };
    private int selectedQuality = 0;

    private TextureView textureView;
    private Button streamBtn, switchCameraBtn;
    private EditText portInput;
    private Spinner qualitySpinner;
    private CheckBox flashCheck;
    private TextView statusText, clientCount, fpsDisplay, zoomLabel, wifiInfo;
    private SeekBar zoomSeek;
    private View statusDot;

    private CameraManager cameraManager;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private CaptureRequest.Builder previewBuilder;
    private Surface previewSurface; // released in closeCamera (avoids Surface leaks)
    private HandlerThread backgroundThread;
    private Handler backgroundHandler;
    private boolean usingFrontCamera = false;
    private volatile int frameWidth = 1920, frameHeight = 1080, targetFps = 60;
    private volatile int videoBitrate = 12_000_000;

    // ── H.264 encoder state ──
    private MediaCodec encoder;
    private Surface encoderSurface;
    private Thread drainThread;
    private volatile boolean encoderRunning = false;
    private byte[] spsPps; // cached SPS+PPS prefix, prepended to every IDR

    // ── Orientation / zoom / AF state ──
    private String currentCameraId = null;
    private int cameraGen = 0; // invalidates stale async camera callbacks
    private int pendingOpenGen = 0;
    private boolean cameraOpening = false;
    private boolean spinnerReady = false;
    private boolean isPausing = false; // true while onPause tears down (skips reopen work)
    private int sensorOrientation = 90;
    private Rect sensorArraySize = null;
    // Clockwise correction sent per packet; the PC rotates frames with it.
    private volatile int streamRotation = 0; // one of 0/90/180/270
    private volatile float zoomRatio = 1.0f;
    private float zoomLower = 1.0f, zoomUpper = 4.0f;
    private boolean zoomRatioSupported = false;
    private Range<Integer> activeFpsRange = new Range<>(60, 60);
    private int afModeInUse = CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO;
    private boolean afSupportedContinuous = true;
    private float minFocusDistance = 0f;
    private boolean hasFlash = false;

    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock; // high-perf radio lock: stops WiFi batching/drops
    private ServerSocket serverSocket;
    private volatile boolean isStreaming = false;
    private final CopyOnWriteArrayList<ClientWriter> clients = new CopyOnWriteArrayList<>();

    private long fpsWindowStart = 0;
    private int  fpsFrameCount  = 0;
    private volatile float measuredFps = 0f;

    // ── Wire protocol ─────────────────────────────────────────────────────────
    // Session header:  "PCAMH264" + BE u32 w,h,fps
    // Packet:          "PCAM" + BE u32 payloadLen + BE u32 rotation + u8 front + payload
    // Payload is one Annex-B access unit (SPS+PPS prepended to IDRs by the drain loop).
    private static final byte[] SESSION_MAGIC = { 'P','C','A','M','H','2','6','4' };
    private static final byte[] PACKET_MAGIC  = { 'P','C','A','M' };

    private static void writeU32BE(OutputStream os, int v) throws IOException {
        os.write((v >>> 24) & 0xFF);
        os.write((v >>> 16) & 0xFF);
        os.write((v >>>  8) & 0xFF);
        os.write(v & 0xFF);
    }

    // ── Per-client async writer ───────────────────────────────────────────────
    private static class ClientWriter {
        final OutputStream os;
        final LinkedBlockingQueue<H264Chunk> queue = new LinkedBlockingQueue<>();
        final Thread writerThread;
        volatile boolean alive = true;
        volatile long queuedBytes = 0;
        volatile boolean needsKey = true; // drop P-frames until first IDR (clean join)
        private final int maxQueueBytes;

        ClientWriter(OutputStream os, int maxQueueBytes) {
            this.os = os;
            this.maxQueueBytes = maxQueueBytes;
            writerThread = new Thread(() -> {
                try {
                    DataOutputStream dos = new DataOutputStream(os);
                    while (alive) {
                        H264Chunk c;
                        try {
                            c = queue.take();
                        } catch (InterruptedException e) {
                            break;
                        }
                        try {
                            dos.write(PACKET_MAGIC);
                            writeU32BE(dos, c.data.length);
                            writeU32BE(dos, c.rotation);
                            dos.write(c.front ? 1 : 0);
                            dos.write(c.data);
                            dos.flush();
                        } catch (Exception e) {
                            alive = false;
                            break;
                        }
                        queuedBytes -= c.data.length;
                    }
                } catch (Exception e) {
                    alive = false;
                }
            }, "ClientWriter");
            writerThread.setDaemon(true);
            writerThread.start();
        }

        /**
         * @return 0 accepted, 1 accepted but had to drop data, 2 dropped a
         *         keyframe (caller should request a fresh IDR), -1 dead client.
         */
        synchronized int offer(byte[] pkt, boolean isKey, int rotation, boolean front) {
            if (!alive) return -1;
            if (needsKey) {
                if (!isKey) return 0; // wait for IDR so the decoder starts clean
                needsKey = false;
            }
            int res = 0;
            // Drop oldest packets when over budget (encoder output is small;
            // this only bites on a stalled connection).
            while (queuedBytes + pkt.length > maxQueueBytes) {
                H264Chunk dropped = queue.poll();
                if (dropped == null) break;
                queuedBytes -= dropped.data.length;
                res = 1;
                if (dropped.key) { needsKey = true; res = 2; } // head must stay decodable
            }
            queuedBytes += pkt.length;
            queue.offer(new H264Chunk(pkt, isKey, rotation, front));
            return res;
        }

        void close() {
            alive = false;
            writerThread.interrupt();
            try { os.close(); } catch (IOException ignored) {}
        }
    }

    private static class H264Chunk {
        final byte[] data;
        final boolean key;
        final int rotation;
        final boolean front;
        H264Chunk(byte[] data, boolean key, int rotation, boolean front) {
            this.data = data; this.key = key; this.rotation = rotation; this.front = front;
        }
    }

    // ── Activity lifecycle ────────────────────────────────────────────────────
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PhoneCam:StreamLock");
        wakeLock.setReferenceCounted(false);

        textureView     = findViewById(R.id.textureView);
        streamBtn       = findViewById(R.id.streamBtn);
        switchCameraBtn = findViewById(R.id.switchCameraBtn);
        portInput       = findViewById(R.id.portInput);
        qualitySpinner  = findViewById(R.id.qualitySpinner);
        flashCheck      = findViewById(R.id.flashCheck);
        statusText      = findViewById(R.id.statusText);
        clientCount     = findViewById(R.id.clientCount);
        statusDot       = findViewById(R.id.statusDot);
        fpsDisplay      = findViewById(R.id.fpsDisplay);
        wifiInfo        = findViewById(R.id.wifiInfo);
        zoomSeek        = findViewById(R.id.zoomSeek);
        zoomLabel       = findViewById(R.id.zoomLabel);

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, QUALITY_LABELS);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        qualitySpinner.setAdapter(adapter);
        qualitySpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (!spinnerReady) { spinnerReady = true; selectedQuality = pos; return; }
                if (pos == selectedQuality) return;
                selectedQuality = pos;
                // Re-open camera at new size/fps if preview already running
                if (cameraDevice != null) {
                    closeCamera();
                    openCamera();
                }
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });

        if (zoomSeek != null) {
            // Range 0.5x..4.0x; tightened to the real zoom range once known.
            zoomSeek.setMax(4 * ZOOM_SEEK_SCALE - ZOOM_SEEK_BASE);
            zoomSeek.setProgress(ZOOM_SEEK_SCALE - ZOOM_SEEK_BASE); // 1.0x
            zoomSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                    float z = (progress + ZOOM_SEEK_BASE) / (float) ZOOM_SEEK_SCALE;
                    z = Math.max(zoomLower, Math.min(zoomUpper, z));
                    zoomRatio = z;
                    if (zoomLabel != null)
                        zoomLabel.setText(String.format(java.util.Locale.US, "%.1fx", z));
                    if (fromUser) applyZoom();
                }
                @Override public void onStartTrackingTouch(SeekBar s) {}
                @Override public void onStopTrackingTouch(SeekBar s) { kickAutofocus(); }
            });
        }

        // Tap-to-focus
        textureView.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_UP) {
                handleTapToFocus(event.getX(), event.getY(), v.getWidth(), v.getHeight());
            }
            return true;
        });

        streamBtn.setOnClickListener(v -> { if (!isStreaming) startStreaming(); else stopStreaming(); });
        switchCameraBtn.setOnClickListener(v -> switchCamera());
        if (flashCheck != null) {
            flashCheck.setOnCheckedChangeListener((buttonView, isChecked) -> applyTorch());
        }

        cameraManager = (CameraManager) getSystemService(CAMERA_SERVICE);

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA);
        } else {
            setupCamera();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        isPausing = false;
        updateWifiInfo();
        startBackgroundThread();
        if (cameraDevice != null) return; // already open (e.g. setupCamera won the race)
        if (textureView.isAvailable()) openCamera();
        else textureView.setSurfaceTextureListener(surfaceTextureListener);
    }

    /** Show the LAN address so the PC can connect over WiFi (no ADB needed).
     *  The server socket binds all interfaces, so USB and WiFi work at once. */
    private void updateWifiInfo() {
        final String ip = getWifiIp();
        String portText = String.valueOf(DEFAULT_PORT);
        if (portInput != null) {
            String typed = portInput.getText().toString().trim();
            if (!typed.isEmpty()) portText = typed;
        }
        final String port = portText;
        runOnUiThread(() -> {
            if (wifiInfo != null)
                wifiInfo.setText(ip != null
                        ? ("USB :" + DEFAULT_PORT + "  •  WiFi " + ip + ":" + port)
                        : ("USB :" + DEFAULT_PORT + "  •  WiFi off"));
        });
    }

    private String getWifiIp() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wm == null || !wm.isWifiEnabled()) return null;
            int addr = wm.getConnectionInfo().getIpAddress();
            if (addr == 0) return null;
            return (addr & 0xFF) + "." + ((addr >> 8) & 0xFF)
                    + "." + ((addr >> 16) & 0xFF) + "." + ((addr >> 24) & 0xFF);
        } catch (Exception e) {
            // Best-effort UI label only - any failure means "WiFi off".
            Log.d(TAG, "WiFi IP unavailable: " + e.getMessage());
            return null;
        }
    }

    @Override
    protected void onPause() {
        isPausing = true;
        if (isStreaming) stopStreaming();
        closeCamera();
        stopBackgroundThread();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onPause();
    }

    @Override
    public void onConfigurationChanged(@NonNull android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // Re-apply preview transform + stream rotation on rotate
        if (textureView != null && textureView.isAvailable()) {
            configureTransform(textureView.getWidth(), textureView.getHeight());
        }
    }

    // ── Camera setup ──────────────────────────────────────────────────────────
    private void setupCamera() {
        startBackgroundThread();
        if (textureView.isAvailable()) openCamera();
        else textureView.setSurfaceTextureListener(surfaceTextureListener);
    }

    private final TextureView.SurfaceTextureListener surfaceTextureListener =
            new TextureView.SurfaceTextureListener() {
        @Override public void onSurfaceTextureAvailable(@NonNull SurfaceTexture s, int w, int h) {
            configureTransform(w, h);
            openCamera();
        }
        @Override public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture s, int w, int h) {
            configureTransform(w, h);
        }
        @Override public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture s) { return true; }
        @Override public void onSurfaceTextureUpdated(@NonNull SurfaceTexture s) {}
    };

    private void openCamera() {
        if (backgroundHandler == null) startBackgroundThread();
        if (cameraDevice != null || cameraOpening) return; // open already in flight / done
        cameraOpening = true;
        cameraGen++;
        final int gen = cameraGen;
        pendingOpenGen = gen;
        try {
            String cameraId = getBestCameraId(usingFrontCamera);
            if (cameraId == null) { setStatus("No camera found", false); return; }
            currentCameraId = cameraId;
            CameraCharacteristics ch = cameraManager.getCameraCharacteristics(cameraId);

            frameWidth  = Q_W[selectedQuality];
            frameHeight = Q_H[selectedQuality];
            targetFps   = Q_FPS[selectedQuality];
            videoBitrate = Q_BITRATE[selectedQuality];

            // Cache sensor characteristics
            Integer so = ch.get(CameraCharacteristics.SENSOR_ORIENTATION);
            sensorOrientation = (so != null) ? so : 90;
            sensorArraySize = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);

            // AF capability
            int[] afModes = ch.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
            afSupportedContinuous = false;
            boolean hasAuto = false;
            if (afModes != null) {
                for (int m : afModes) {
                    if (m == CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO) afSupportedContinuous = true;
                    if (m == CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE) hasAuto = true;
                    if (m == CaptureRequest.CONTROL_AF_MODE_AUTO) hasAuto = true;
                }
            }
            Float minFocus = ch.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
            minFocusDistance = (minFocus != null) ? minFocus : 0f;
            if (minFocusDistance == 0f) {
                Log.i(TAG, "Fixed-focus camera (likely ultrawide) - AF will be OFF");
            }
            if (afSupportedContinuous) afModeInUse = CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO;
            else if (hasAuto) afModeInUse = CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE;
            else afModeInUse = CaptureRequest.CONTROL_AF_MODE_OFF;

            // Flash availability is per-camera (front cameras usually have none).
            Boolean flashAvail = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            hasFlash = (flashAvail != null && flashAvail);
            Log.i(TAG, "Flash available: " + hasFlash);
            runOnUiThread(() -> {
                if (flashCheck != null) {
                    flashCheck.setEnabled(hasFlash);
                    if (!hasFlash) flashCheck.setChecked(false);
                }
            });

            // Zoom range (API 30+). Samsung logical back camera typically reports ~0.5..4+
            zoomRatioSupported = false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Range<Float> zr = ch.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
                if (zr != null) {
                    zoomLower = Math.max(0.5f, zr.getLower());
                    // Allow at least 0.5 even if driver reports 1.0 (some Samsung builds hide UW
                    // behind logical camera but still honor 0.5) - clamp only the upper side strictly
                    if (zr.getLower() <= 0.6f) zoomLower = zr.getLower();
                    zoomUpper = Math.min(10.0f, zr.getUpper());
                    if (zoomUpper < 4.0f) zoomUpper = zr.getUpper();
                    zoomRatioSupported = true;
                    Log.i(TAG, "Zoom ratio range: " + zr.getLower() + ".." + zr.getUpper());
                }
            }
            if (!zoomRatioSupported) {
                // Crop-region fallback can only zoom IN (>=1.0x)
                zoomLower = 1.0f; zoomUpper = 4.0f;
            }
            zoomRatio = Math.max(zoomLower, Math.min(zoomUpper, zoomRatio));
            final float zoomMax = zoomUpper;
            runOnUiThread(() -> {
                if (zoomSeek != null) {
                    zoomSeek.setMax(Math.round((zoomMax - 0.5f) * ZOOM_SEEK_SCALE)
                            - ZOOM_SEEK_BASE);
                    zoomSeek.setProgress(Math.round(zoomRatio * ZOOM_SEEK_SCALE)
                            - ZOOM_SEEK_BASE);
                }
                if (zoomLabel != null)
                    zoomLabel.setText(String.format(java.util.Locale.US, "%.1fx", zoomRatio));
            });

            // FPS range actually supported (don't blindly request 60/60)
            activeFpsRange = pickFpsRange(ch, targetFps);
            Log.i(TAG, "Target " + targetFps + "fps -> using range " + activeFpsRange);
            if (activeFpsRange.getUpper() < targetFps) {
                final Range<Integer> ar = activeFpsRange;
                final int want = targetFps;
                runOnUiThread(() -> setStatus(
                        "Lens caps at " + ar.getUpper() + "fps (wanted " + want + ")", false));
            }

            // Stream rotation for current display orientation
            if (textureView.isAvailable())
                configureTransform(textureView.getWidth(), textureView.getHeight());
            else updateStreamRotation();

            // Snap to the nearest size the camera+encoder pipeline supports.
            // (720p/1080p are universal; this just guards odd devices.)
            Size supported = pickSupportedSize(cameraId, frameWidth, frameHeight);
            frameWidth  = supported.getWidth();
            frameHeight = supported.getHeight();

            Log.i(TAG, "Opening camera " + cameraId + " at " + frameWidth + "x" + frameHeight
                    + " @" + targetFps + "fps H.264 " + (videoBitrate / 1_000_000) + "Mbps"
                    + (encoderRunning ? " +encoder" : " preview-only"));

            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED) {
                cameraManager.openCamera(cameraId, stateCallback, backgroundHandler);
            } else {
                cameraOpening = false;
            }
        } catch (CameraAccessException e) {
            cameraOpening = false;
            Log.e(TAG, "Camera open error", e);
            setStatus("Camera error: " + e.getMessage(), false);
        } catch (IllegalArgumentException | SecurityException e) {
            cameraOpening = false;
            Log.e(TAG, "Camera open error", e);
            setStatus("Camera error: " + e.getMessage(), false);
        }
    }

    /** Pick an AE target-FPS range the hardware actually supports. */
    private Range<Integer> pickFpsRange(CameraCharacteristics ch, int want) {
        try {
            Range<Integer>[] ranges =
                    ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
            if (ranges == null || ranges.length == 0) return new Range<>(want, want);
            // Prefer a range that CONTAINS want (lower <= want <= upper) with the
            // highest lower bound (least flicker); e.g. want=30 must not pick [60,60].
            Range<Integer> bestContaining = null;
            for (Range<Integer> r : ranges) {
                if (r.getLower() <= want && r.getUpper() >= want) {
                    if (bestContaining == null || r.getLower() > bestContaining.getLower()
                            || (r.getLower().equals(bestContaining.getLower())
                                && r.getUpper() < bestContaining.getUpper()))
                        bestContaining = r;
                }
            }
            if (bestContaining != null) return bestContaining;
            Range<Integer> maxUpper = ranges[0];
            for (Range<Integer> r : ranges)
                if (r.getUpper() > maxUpper.getUpper()) maxUpper = r;
            final Range<Integer> capped = maxUpper;
            runOnUiThread(() -> setStatus("60fps not supported here - using " + capped.getUpper() + "fps", false));
            return maxUpper;
        } catch (Exception e) {
            return new Range<>(want, want);
        }
    }

    /**
     * Standard Camera2 preview transform: rotates/scales the TextureView so the
     * preview appears upright for the current display rotation. Also updates
     * the streamRotation metadata sent with each H.264 packet.
     */
    private void configureTransform(int viewWidth, int viewHeight) {
        if (textureView == null || currentCameraId == null
                || viewWidth == 0 || viewHeight == 0) {
            updateStreamRotation();
            return;
        }
        try {
            int rotation = getWindowManager().getDefaultDisplay().getRotation();
            Matrix matrix = new Matrix();
            RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
            // Buffer rect is in sensor coordinates; swap dimensions for 90/270
            RectF bufferRect = new RectF(0, 0, frameHeight, frameWidth);
            float centerX = viewRect.centerX();
            float centerY = viewRect.centerY();
            if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
                bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
                matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
                float scale = Math.max(
                        (float) viewHeight / frameHeight,
                        (float) viewWidth / frameWidth);
                matrix.postScale(scale, scale, centerX, centerY);
                int deg = (rotation == Surface.ROTATION_90) ? 90 : 270;
                // Sensor is mounted sideways relative to display on most phones
                int sensorToDisplay = (sensorOrientation - deg + 360) % 360;
                matrix.postRotate(sensorToDisplay, centerX, centerY);
                if (usingFrontCamera) matrix.postScale(-1, 1, centerX, centerY);
            } else if (rotation == Surface.ROTATION_180) {
                matrix.postRotate(180, centerX, centerY);
                if (usingFrontCamera) matrix.postScale(-1, 1, centerX, centerY);
            } else {
                // ROTATION_0: still need 1:1 scale + front mirror
                if (usingFrontCamera) matrix.postScale(-1, 1, centerX, centerY);
            }
            textureView.setTransform(matrix);
        } catch (Exception e) {
            Log.w(TAG, "configureTransform failed", e);
        }
        updateStreamRotation();
    }

    /** Compute the clockwise rotation to apply to streamed frames so OBS sees them upright. */
    private void updateStreamRotation() {
        try {
            int rotation = getWindowManager().getDefaultDisplay().getRotation();
            int deviceDeg;
            switch (rotation) {
                case Surface.ROTATION_90:  deviceDeg = 90;  break;
                case Surface.ROTATION_180: deviceDeg = 180; break;
                case Surface.ROTATION_270: deviceDeg = 270; break;
                default: deviceDeg = 0;
            }
            int rot;
            if (usingFrontCamera) rot = (sensorOrientation + deviceDeg) % 360;
            else rot = (sensorOrientation - deviceDeg + 360) % 360;
            // Normalize to 0/90/180/270
            streamRotation = ((rot + 45) / 90 * 90) % 360;
        } catch (Exception e) {
            streamRotation = 0;
        }
    }

    private String getBestCameraId(boolean front) throws CameraAccessException {
        int wantFacing = front ? CameraCharacteristics.LENS_FACING_FRONT
                               : CameraCharacteristics.LENS_FACING_BACK;
        String fallback = null;
        String logicalWithWideZoom = null;
        for (String id : cameraManager.getCameraIdList()) {
            CameraCharacteristics ch = cameraManager.getCameraCharacteristics(id);
            Integer facing = ch.get(CameraCharacteristics.LENS_FACING);
            if (facing == null || facing != wantFacing) continue;
            if (fallback == null) fallback = id;
            // Prefer the logical camera whose zoom range reaches ~0.6x (main+UW combo)
            if (!front && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Range<Float> zr = ch.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
                if (zr != null && zr.getLower() <= 0.6f) {
                    StreamConfigurationMap map =
                            ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                    if (map != null) {
                        Size[] sizes = map.getOutputSizes(SurfaceTexture.class);
                        if (sizes != null) {
                            for (Size s : sizes) {
                                if (s.getWidth() >= 1920) { logicalWithWideZoom = id; break; }
                            }
                        }
                    }
                    if (logicalWithWideZoom != null) break;
                }
            }
            if (front) break;
        }
        if (logicalWithWideZoom != null) {
            Log.i(TAG, "Selected 0.6x-capable logical camera: " + logicalWithWideZoom);
            return logicalWithWideZoom;
        }
        return fallback;
    }

    private Size pickSupportedSize(String cameraId, int w, int h) throws CameraAccessException {
        CameraCharacteristics ch = cameraManager.getCameraCharacteristics(cameraId);
        StreamConfigurationMap map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map == null) return new Size(w, h);
        // SurfaceTexture table covers preview-capable sizes; 720p/1080p encoder
        // surfaces overlap it on all modern devices.
        Size[] sizes = map.getOutputSizes(SurfaceTexture.class);
        if (sizes == null || sizes.length == 0) return new Size(w, h);

        Size best = sizes[0];
        long bestDelta = Long.MAX_VALUE;
        for (Size s : sizes) {
            long delta = Math.abs((long) s.getWidth() * s.getHeight() - (long) w * h);
            if (delta < bestDelta) { bestDelta = delta; best = s; }
        }
        if (best.getWidth() != w || best.getHeight() != h) {
            Log.w(TAG, "Requested " + w + "x" + h + " -> using " + best.getWidth() + "x" + best.getHeight());
            final Size finalBest = best;
            runOnUiThread(() -> setStatus("Using " + finalBest.getWidth() + "x" + finalBest.getHeight(), false));
        }
        return best;
    }

    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override public void onOpened(@NonNull CameraDevice camera) {
            cameraOpening = false;
            if (pendingOpenGen != cameraGen) { camera.close(); return; } // stale open, drop it
            cameraDevice = camera;
            createCaptureSession(cameraGen);
        }
        @Override public void onDisconnected(@NonNull CameraDevice camera) {
            cameraOpening = false;
            camera.close(); if (cameraDevice == camera) cameraDevice = null;
        }
        @Override public void onError(@NonNull CameraDevice camera, int error) {
            cameraOpening = false;
            Log.e(TAG, "Camera device error: " + error);
            camera.close(); if (cameraDevice == camera) cameraDevice = null;
            setStatus("Camera error " + error + " - retrying...", false);
            if (backgroundHandler != null) backgroundHandler.postDelayed(() -> {
                if (cameraDevice == null) openCamera();
            }, CAMERA_RETRY_DELAY_MS);
        }
    };

    private void createCaptureSession(int gen) {
        if (gen != cameraGen) return;
        final CameraDevice device = cameraDevice;
        if (device == null) return;
        try {
            SurfaceTexture tex = textureView.getSurfaceTexture();
            if (tex == null) return;
            // Cap the on-screen preview at 720p even when encoding 1080p:
            // the display/GPU work shrinks a lot, the encoder is unaffected.
            int pvW = frameWidth, pvH = frameHeight;
            if (pvW > PREVIEW_MAX_WIDTH || pvH > PREVIEW_MAX_HEIGHT) {
                float s = Math.min((float) PREVIEW_MAX_WIDTH / pvW,
                        (float) PREVIEW_MAX_HEIGHT / pvH);
                pvW = Math.max(2, (int) (pvW * s) & ~1);
                pvH = Math.max(2, (int) (pvH * s) & ~1);
            }
            tex.setDefaultBufferSize(pvW, pvH);
            configureTransform(textureView.getWidth(), textureView.getHeight());
            if (previewSurface != null) {
                try { previewSurface.release(); } catch (Exception ignored) {}
            }
            previewSurface = new Surface(tex);
            // Encoder surface is attached only while streaming; preview-only otherwise.
            final Surface encSurface = (encoderRunning && encoderSurface != null)
                    ? encoderSurface : null;

            List<Surface> targets = new ArrayList<>();
            targets.add(previewSurface);
            if (encSurface != null) targets.add(encSurface);

            device.createCaptureSession(
                    targets,
                    new CameraCaptureSession.StateCallback() {
                        @Override public void onConfigured(@NonNull CameraCaptureSession session) {
                            // Drop callbacks from a previous open/close generation
                            if (gen != cameraGen || cameraDevice != device) {
                                session.close();
                                return;
                            }
                            captureSession = session;
                            startPreview(device, previewSurface, encSurface);
                        }
                        @Override public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            setStatus("Session config failed", false);
                        }
                    }, backgroundHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Session error", e);
        } catch (IllegalStateException e) {
            Log.w(TAG, "Session raced with close, ignoring", e);
        }
    }

    private void createCaptureSession() {
        createCaptureSession(cameraGen);
    }

    private void startPreview(CameraDevice device, Surface previewSurface, Surface encSurface) {
        if (captureSession == null || device == null || cameraDevice != device) return;
        try {
            // TEMPLATE_RECORD sustains 60fps video on Samsung.
            CaptureRequest.Builder b = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            b.addTarget(previewSurface);
            if (encSurface != null) b.addTarget(encSurface);
            b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, activeFpsRange);
            // AF: continuous-video when available, else off (fixed-focus UW)
            b.set(CaptureRequest.CONTROL_AF_MODE, afModeInUse);
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && zoomRatioSupported) {
                try {
                    b.set(CaptureRequest.CONTROL_ZOOM_RATIO,
                            Math.max(zoomLower, Math.min(zoomUpper, zoomRatio)));
                } catch (Exception e) {
                    Log.w(TAG, "Zoom ratio rejected", e);
                }
            }
            if (flashCheck != null && hasFlash && flashCheck.isChecked()) {
                b.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH);
            }
            previewBuilder = b;
            captureSession.setRepeatingRequest(b.build(), captureCallback, backgroundHandler);
            String afNote = (afModeInUse == CaptureRequest.CONTROL_AF_MODE_OFF)
                    ? "fixed-focus" : "AF-C";
            setStatus(frameWidth + "x" + frameHeight + " " + activeFpsRange
                    + " " + afNote + " " + String.format(java.util.Locale.US, "%.1fx", zoomRatio)
                    + " H264 - active", false);
            // Kick one AF scan so Samsung starts focused immediately
            kickAutofocus();
        } catch (CameraAccessException e) {
            Log.e(TAG, "Preview error", e);
        } catch (IllegalStateException e) {
            Log.w(TAG, "Preview raced with close, ignoring", e);
        }
    }

    /** Apply torch state live (checkbox toggles mid-stream included). */
    private void applyTorch() {
        if (captureSession == null || previewBuilder == null) return;
        if (!hasFlash) return;
        try {
            boolean on = flashCheck != null && flashCheck.isChecked();
            previewBuilder.set(CaptureRequest.FLASH_MODE, on
                    ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF);
            captureSession.setRepeatingRequest(previewBuilder.build(), captureCallback, backgroundHandler);
            Log.i(TAG, "Torch " + (on ? "ON" : "OFF"));
        } catch (CameraAccessException e) {
            Log.w(TAG, "Torch apply failed", e);
        } catch (IllegalStateException e) {
            Log.w(TAG, "Torch raced with close, ignoring", e);
        }
    }

    /** Apply the current zoomRatio live without restarting the session. */
    private void applyZoom() {
        if (captureSession == null || previewBuilder == null) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && zoomRatioSupported) {
                previewBuilder.set(CaptureRequest.CONTROL_ZOOM_RATIO,
                        Math.max(zoomLower, Math.min(zoomUpper, zoomRatio)));
            } else {
                // Pre-R fallback: center crop (zoom-in only, >=1.0x)
                if (sensorArraySize != null && zoomRatio >= 1.0f) {
                    int cx = sensorArraySize.width() / 2, cy = sensorArraySize.height() / 2;
                    int dw = (int) (sensorArraySize.width() / zoomRatio);
                    int dh = (int) (sensorArraySize.height() / zoomRatio);
                    Rect crop = new Rect(cx - dw / 2, cy - dh / 2, cx + dw / 2, cy + dh / 2);
                    previewBuilder.set(CaptureRequest.SCALER_CROP_REGION, crop);
                }
            }
            captureSession.setRepeatingRequest(previewBuilder.build(), captureCallback, backgroundHandler);
        } catch (CameraAccessException e) {
            Log.w(TAG, "applyZoom failed", e);
        } catch (IllegalStateException e) {
            Log.w(TAG, "applyZoom raced with close, ignoring", e);
        }
    }

    /** Trigger a single AF scan (also re-arms continuous AF on Samsung). */
    private void kickAutofocus() {
        if (captureSession == null || previewBuilder == null || backgroundHandler == null) return;
        if (afModeInUse == CaptureRequest.CONTROL_AF_MODE_OFF) return;
        try {
            previewBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                    CameraMetadata.CONTROL_AF_TRIGGER_START);
            captureSession.capture(previewBuilder.build(), captureCallback, backgroundHandler);
            previewBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                    CameraMetadata.CONTROL_AF_TRIGGER_IDLE);
            captureSession.setRepeatingRequest(previewBuilder.build(), captureCallback, backgroundHandler);
        } catch (CameraAccessException e) {
            Log.w(TAG, "AF trigger failed", e);
        } catch (IllegalStateException e) {
            Log.w(TAG, "AF trigger raced with close, ignoring", e);
        }
    }

    /** Tap-to-focus: set AF/AE metering rectangles at the touch point + trigger scan. */
    private void handleTapToFocus(float x, float y, int viewW, int viewH) {
        if (captureSession == null || previewBuilder == null || sensorArraySize == null
                || backgroundHandler == null) return;
        if (afModeInUse == CaptureRequest.CONTROL_AF_MODE_OFF) {
            setStatus("Fixed-focus camera (no tap AF)", false);
            return;
        }
        try {
            // Map view coords -> sensor coords (basic, assumes upright preview)
            int sx = (int) (x / Math.max(1, viewW) * sensorArraySize.width());
            int sy = (int) (y / Math.max(1, viewH) * sensorArraySize.height());
            int half = Math.max(FOCUS_RECT_MIN_HALF_PX,
                    sensorArraySize.width() / FOCUS_RECT_DIVISOR);
            Rect r = new Rect(
                    Math.max(0, sx - half), Math.max(0, sy - half),
                    Math.min(sensorArraySize.width(), sx + half),
                    Math.min(sensorArraySize.height(), sy + half));
            MeteringRectangle mr = new MeteringRectangle(r, FOCUS_METERING_WEIGHT);
            previewBuilder.set(CaptureRequest.CONTROL_AF_REGIONS, new MeteringRectangle[]{mr});
            previewBuilder.set(CaptureRequest.CONTROL_AE_REGIONS, new MeteringRectangle[]{mr});
            previewBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
            previewBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                    CameraMetadata.CONTROL_AF_TRIGGER_START);
            captureSession.capture(previewBuilder.build(), captureCallback, backgroundHandler);
            previewBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                    CameraMetadata.CONTROL_AF_TRIGGER_IDLE);
            // Return to continuous mode after a moment
            backgroundHandler.postDelayed(() -> {
                try {
                    if (captureSession == null || previewBuilder == null) return;
                    previewBuilder.set(CaptureRequest.CONTROL_AF_MODE, afModeInUse);
                    previewBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                            CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);
                    captureSession.setRepeatingRequest(previewBuilder.build(),
                            captureCallback, backgroundHandler);
                    previewBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                            CameraMetadata.CONTROL_AF_TRIGGER_IDLE);
                } catch (CameraAccessException e) {
                    Log.w(TAG, "AF restore failed", e);
                } catch (IllegalStateException e) {
                    Log.w(TAG, "AF restore raced with close, ignoring", e);
                }
            }, TAP_FOCUS_RESTORE_DELAY_MS);
            setStatus("Focusing...", false);
        } catch (CameraAccessException e) {
            Log.w(TAG, "Tap AF failed", e);
        } catch (IllegalStateException e) {
            Log.w(TAG, "Tap AF raced with close, ignoring", e);
        }
    }

    private final CameraCaptureSession.CaptureCallback captureCallback =
            new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                       @NonNull CaptureRequest request,
                                       @NonNull android.hardware.camera2.TotalCaptureResult result) {
            long now = System.currentTimeMillis();
            if (fpsWindowStart == 0) fpsWindowStart = now;
            fpsFrameCount++;
            long elapsed = now - fpsWindowStart;
            if (elapsed >= 1000) {
                measuredFps = fpsFrameCount * 1000f / elapsed;
                fpsFrameCount  = 0;
                fpsWindowStart = now;
                final float fps = measuredFps;
                runOnUiThread(() -> {
                    if (fpsDisplay != null)
                        fpsDisplay.setText(String.format(java.util.Locale.US, "%.0f fps", fps));
                });
            }
        }
    };

    // ── H.264 encoder (MediaCodec, Surface input, Annex-B over TCP) ──────────
    private void startEncoder() {
        stopEncoder();
        final int w = frameWidth, h = frameHeight, fps = targetFps, br = videoBitrate;
        MediaFormat fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h);
        fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, br);
        fmt.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
        fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1); // 1s IDR for fast join
        // Low-latency streaming tuning: CBR for predictable USB load, realtime
        // priority, operating-rate hint so the encoder holds 60fps, no B-frames.
        try {
            fmt.setInteger(MediaFormat.KEY_BITRATE_MODE,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);
        } catch (Exception ignored) {}
        try {
            fmt.setInteger(MediaFormat.KEY_PRIORITY, 0); // 0 = realtime
        } catch (Exception ignored) {}
        try {
            fmt.setInteger(MediaFormat.KEY_OPERATING_RATE, fps);
        } catch (Exception ignored) {}
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                fmt.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0);
            } catch (Exception ignored) {}
        }

        MediaCodec enc;
        try {
            enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        } catch (IOException e) {
            Log.e(TAG, "No AVC encoder", e);
            setStatus("No H.264 encoder on device", false);
            return;
        }
        try {
            enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        } catch (Exception e) {
            // Some devices reject tuned keys (CBR/priority/operating-rate) —
            // retry plain config.
            Log.w(TAG, "Tuned configure failed, retrying plain", e);
            try {
                enc.reset();
                MediaFormat plain = MediaFormat.createVideoFormat(
                        MediaFormat.MIMETYPE_VIDEO_AVC, w, h);
                plain.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
                plain.setInteger(MediaFormat.KEY_BIT_RATE, br);
                plain.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
                plain.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
                enc.configure(plain, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            } catch (Exception e2) {
                Log.e(TAG, "Encoder configure failed", e2);
                setStatus("Encoder error: " + e2.getMessage(), false);
                enc.release();
                return;
            }
        }
        try {
            encoderSurface = enc.createInputSurface();
            enc.start();
        } catch (Exception e) {
            Log.e(TAG, "Encoder start failed", e);
            setStatus("Encoder error: " + e.getMessage(), false);
            enc.release();
            return;
        }
        encoder = enc;
        encoderRunning = true;
        spsPps = null;
        drainThread = new Thread(this::drainEncoder, "H264Drain");
        drainThread.setDaemon(true);
        drainThread.start();
        Log.i(TAG, "H.264 encoder started: " + w + "x" + h + "@" + fps
                + " " + (br / 1_000_000) + "Mbps");
    }

    private void stopEncoder() {
        encoderRunning = false;
        if (drainThread != null) {
            try { drainThread.join(DRAIN_JOIN_TIMEOUT_MS); } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // preserve interrupt status
            }
            drainThread = null;
        }
        if (encoder != null) {
            try { encoder.stop(); } catch (Exception ignored) {}
            try { encoder.release(); } catch (Exception ignored) {}
            encoder = null;
        }
        if (encoderSurface != null) {
            try { encoderSurface.release(); } catch (Exception ignored) {}
            encoderSurface = null;
        }
        spsPps = null;
    }

    private void drainEncoder() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (encoderRunning) {
            MediaCodec enc = encoder;
            if (enc == null) break;
            int idx;
            try {
                idx = enc.dequeueOutputBuffer(info, 10000);
            } catch (Exception e) {
                if (encoderRunning) Log.w(TAG, "dequeue failed", e);
                break;
            }
            if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) continue;
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                cacheSpsPpsFromFormat(enc.getOutputFormat());
                continue;
            }
            if (idx < 0) continue;
            boolean config = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
            boolean key = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
            try {
                ByteBuffer out = enc.getOutputBuffer(idx);
                if (config) {
                    // Some devices deliver SPS/PPS as a config buffer instead.
                    if (out != null && info.size > 0) {
                        byte[] cfg = new byte[info.size];
                        out.position(info.offset);
                        out.limit(info.offset + info.size);
                        out.get(cfg);
                        spsPps = cfg;
                    }
                } else if (out != null && info.size > 0 && !clients.isEmpty()) {
                    byte[] nal = new byte[info.size];
                    out.position(info.offset);
                    out.limit(info.offset + info.size);
                    out.get(nal);
                    byte[] pkt = nal;
                    if (key && spsPps != null) {
                        pkt = new byte[spsPps.length + nal.length];
                        System.arraycopy(spsPps, 0, pkt, 0, spsPps.length);
                        System.arraycopy(nal, 0, pkt, spsPps.length, nal.length);
                    }
                    broadcastNal(pkt, key);
                }
            } catch (Exception e) {
                Log.w(TAG, "drain error", e);
            } finally {
                try { enc.releaseOutputBuffer(idx, false); } catch (Exception ignored) {}
            }
            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
        }
    }

    private void cacheSpsPpsFromFormat(MediaFormat fmt) {
        try {
            ByteBuffer sps = fmt.getByteBuffer("csd-0");
            ByteBuffer pps = fmt.getByteBuffer("csd-1");
            if (sps == null) return;
            byte[] spsB = new byte[sps.remaining()];
            sps.get(spsB);
            byte[] ppsB = new byte[0];
            if (pps != null) {
                ppsB = new byte[pps.remaining()];
                pps.get(ppsB);
            }
            byte[] both = new byte[spsB.length + ppsB.length];
            System.arraycopy(spsB, 0, both, 0, spsB.length);
            System.arraycopy(ppsB, 0, both, spsB.length, ppsB.length);
            spsPps = both;
        } catch (Exception e) {
            Log.w(TAG, "SPS/PPS read failed", e);
        }
    }

    private void broadcastNal(byte[] nal, boolean isKey) {
        try {
            int rot = streamRotation;
            boolean front = usingFrontCamera;
            boolean needIdr = false;
            List<ClientWriter> toRemove = new ArrayList<>();
            for (ClientWriter cw : clients) {
                int res = cw.offer(nal, isKey, rot, front);
                if (res < 0) toRemove.add(cw);
                else if (res == 2) needIdr = true;
            }
            if (!toRemove.isEmpty()) {
                for (ClientWriter cw : toRemove) cw.close();
                clients.removeAll(toRemove);
                updateClientCount();
            }
            // A dropped keyframe would corrupt a client until the next 1s IDR:
            // ask the encoder for a fresh one now (cheap, self-healing).
            if (needIdr) requestSyncFrame();
        } catch (Exception e) {
            Log.e(TAG, "Broadcast error", e);
        }
    }

    private void requestSyncFrame() {
        MediaCodec enc = encoder;
        if (enc == null || !encoderRunning) return;
        try {
            Bundle b = new Bundle();
            b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
            enc.setParameters(b);
        } catch (Exception e) {
            Log.w(TAG, "Sync-frame request failed", e);
        }
    }

    // ── Streaming server (H.264 Annex-B over TCP) ─────────────────────────────
    private void startStreaming() {
        int port;
        try { port = Integer.parseInt(portInput.getText().toString().trim()); }
        catch (NumberFormatException e) { port = DEFAULT_PORT; }
        final int finalPort = port;

        startEncoder();
        if (encoderSurface == null) {
            setStatus("Encoder failed to start", false);
            stopEncoder();
            return;
        }
        if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire(WAKELOCK_TIMEOUT_MS);
        // Keep the WiFi radio at full performance while streaming: without this,
        // power-save batching causes multi-second stalls (and timeouts) on WiFi.
        try {
            WifiManager wm = (WifiManager) getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                        "PhoneCam:WifiLock");
                wifiLock.acquire();
            }
        } catch (Exception e) {
            Log.w(TAG, "WiFi lock failed (non-fatal)", e);
        }

        new Thread(() -> {
            try {
                serverSocket = new ServerSocket(finalPort);
                isStreaming  = true;
                runOnUiThread(() -> {
                    streamBtn.setText(getString(R.string.btn_stop_streaming));
                    streamBtn.setBackgroundTintList(
                            android.content.res.ColorStateList.valueOf(
                                    ContextCompat.getColor(MainActivity.this,
                                            R.color.button_stop_background)));
                    setStatus("Streaming H.264 :" + finalPort + " " + frameWidth + "x" + frameHeight
                            + " @" + targetFps + "fps", true);
                    portInput.setEnabled(false);
                    qualitySpinner.setEnabled(false);
                    updateWifiInfo();
                });
                // Rebuild the capture session so the encoder surface is attached.
                if (cameraDevice != null) {
                    closeCamera();
                    openCamera();
                }

                while (isStreaming) {
                    try {
                        handleNewClient(serverSocket.accept());
                    } catch (IOException e) {
                        if (isStreaming) Log.e(TAG, "Accept error", e);
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "Server error", e);
                runOnUiThread(() -> setStatus("Error: " + e.getMessage(), false));
            }
        }, "ServerThread").start();
    }

    private void handleNewClient(Socket socket) {
        new Thread(() -> {
            try {
                socket.setTcpNoDelay(true);
                socket.setSoTimeout(CLIENT_READ_TIMEOUT_MS);
                OutputStream os = socket.getOutputStream();
                // Session header first, then framed packets from the writer thread.
                DataOutputStream dos = new DataOutputStream(os);
                dos.write(SESSION_MAGIC);
                writeU32BE(dos, frameWidth);
                writeU32BE(dos, frameHeight);
                writeU32BE(dos, targetFps);
                dos.flush();

                ClientWriter cw = new ClientWriter(os, CLIENT_QUEUE_BYTES);
                clients.add(cw);
                updateClientCount();

                byte[] buf = new byte[64];
                // The PC never sends data; only EOF means disconnect.
                // Read timeouts are normal - keep the writer alive through them.
                while (cw.alive) {
                    try {
                        if (socket.getInputStream().read(buf) == -1) break;
                    } catch (SocketTimeoutException e) {
                        continue;
                    } catch (IOException e) { break; }
                }
                cw.close();
                clients.remove(cw);
                updateClientCount();

            } catch (IOException e) {
                Log.d(TAG, "Client error: " + e.getMessage());
            }
        }, "ClientThread").start();
    }

    private void stopStreaming() {
        isStreaming = false;
        stopEncoder();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (wifiLock != null) {
            try { if (wifiLock.isHeld()) wifiLock.release(); } catch (Exception ignored) {}
            wifiLock = null;
        }
        for (ClientWriter cw : clients) cw.close();
        clients.clear();
        try { if (serverSocket != null && !serverSocket.isClosed()) serverSocket.close(); }
        catch (IOException e) { Log.e(TAG, "Stop error", e); }
        if (!isPausing) {
            // User-tapped Stop: return to a preview-only session.
            // (Skipped during onPause - the camera is torn down right after.)
            if (cameraDevice != null) {
                closeCamera();
                openCamera();
            }
        }
        runOnUiThread(() -> {
            streamBtn.setText(getString(R.string.btn_start_streaming));
            streamBtn.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(
                            ContextCompat.getColor(MainActivity.this,
                                    R.color.button_stream_background)));
            setStatus(getString(R.string.status_stopped), false);
            clientCount.setText(getString(R.string.clients_zero));
            if (fpsDisplay != null) fpsDisplay.setText("");
            portInput.setEnabled(true);
            qualitySpinner.setEnabled(true);
        });
    }

    // ── Camera helpers ────────────────────────────────────────────────────────
    private void switchCamera() {
        usingFrontCamera = !usingFrontCamera;
        zoomRatio = 1.0f;
        closeCamera();
        openCamera();
    }

    private void closeCamera() {
        cameraGen++; // invalidate any in-flight onOpened/onConfigured callbacks
        cameraOpening = false;
        if (captureSession != null) {
            try { captureSession.close(); } catch (Exception ignored) {}
            captureSession = null;
        }
        previewBuilder = null;
        if (previewSurface != null) {
            try { previewSurface.release(); } catch (Exception ignored) {}
            previewSurface = null;
        }
        if (cameraDevice  != null) {
            try { cameraDevice.close(); } catch (Exception ignored) {}
            cameraDevice   = null;
        }
        // NOTE: encoder/encoderSurface survive camera re-opens while streaming.
    }

    private void startBackgroundThread() {
        if (backgroundThread != null) return;
        backgroundThread = new HandlerThread("CameraBackground");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }

    private void stopBackgroundThread() {
        if (backgroundThread == null) return;
        backgroundThread.quitSafely();
        try { backgroundThread.join(); } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // preserve interrupt status
        }
        backgroundThread = null;
        backgroundHandler = null;
    }

    private void setStatus(final String msg, final boolean active) {
        runOnUiThread(() -> {
            statusText.setText(msg);
            statusDot.setBackgroundResource(active ? R.drawable.dot_green : R.drawable.dot_gray);
        });
    }

    private void updateClientCount() {
        runOnUiThread(() -> {
            int n = clients.size();
            clientCount.setText(n + " client" + (n != 1 ? "s" : ""));
        });
    }

    @Override
    public void onRequestPermissionsResult(int req, @NonNull String[] perms,
                                           @NonNull int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        if (req == REQUEST_CAMERA && results.length > 0
                && results[0] == PackageManager.PERMISSION_GRANTED) {
            setupCamera();
        }
    }
}
