Repository: Areo-RGB/photo-finish-project (branch: master)

## Context

The app currently uses CameraX ImageAnalysis for live motion detection. A previous attempt to use Camera2 constrained high-speed sessions for 120fps failed because YUV_420_888 ImageReader surfaces are rejected by constrained HS sessions on Pixel 7 (and likely most devices). The entire HS stack was rolled back in commit e3cc474b.

The solution is to use a SurfaceTexture (PRIVATE format, which HS sessions accept) and extract luma data via OpenGL ES — rendering each frame through a luminance shader and reading back just the ROI strip with glReadPixels. This feeds the existing RoiFrameDiffer + NativeDetectionMath pipeline.

Reference the rolled-back implementation memories in `.serena/memories/implementation/` for context on what was tried before, especially:
- `pixel7_hs_constrained_720p_runtime_rear_override_camera2_textureview_2026_03_24.md`
- `hs_nonblocking_analysis_pipeline_fix_all_devices_2026_03_24.md`
- `high_speed_120fps_auto_fallback_overlay_phase1_2026_03_24.md`
- `monitoring_preview_640_hs_controls_local_preview_toggle_2026_03_24.md`
- `bugfix/pixel7_hs_testmode_yuv_surface_fallback_to_normal_2026_03_24.md`

## Step 1: Create GlLumaExtractor.kt

Create `android/app/src/main/kotlin/com/paul/sprintsync/sensor_native/GlLumaExtractor.kt`

This class manages the OpenGL ES pipeline for extracting luma data from camera frames delivered via SurfaceTexture.

### Design:
```kotlin
class GlLumaExtractor(
    private val width: Int,   // e.g. 1280 for 720p
    private val height: Int,  // e.g. 720
) {
    // EGL state
    private var eglDisplay: EGLDisplay
    private var eglContext: EGLContext  
    private var eglSurface: EGLSurface  // offscreen PBuffer

    // GL state
    private var shaderProgram: Int
    private var oesTextureId: Int       // GL_TEXTURE_EXTERNAL_OES for SurfaceTexture
    private var fboId: Int              // framebuffer object
    private var fboColorTexture: Int    // FBO color attachment (RGBA8)
    
    // Camera surface
    private var surfaceTexture: SurfaceTexture
    private var surface: Surface        // pass this to Camera2 session
    
    // Readback buffer
    private var readbackBuffer: ByteBuffer  // direct ByteBuffer for glReadPixels
    
    fun init()           // create EGL context, compile shaders, create FBO, create SurfaceTexture
    fun getSurface(): Surface  // returns the Surface for Camera2 output target
    fun getTimestampNanos(): Long  // returns surfaceTexture.timestamp after updateTexImage
    
    // Call from analysis thread when frame is available:
    // 1. surfaceTexture.updateTexImage()
    // 2. Bind FBO, draw fullscreen quad with luma shader
    // 3. glReadPixels for ROI strip only
    // Returns luma ByteArray for the ROI region
    fun extractRoiLuma(roiStartX: Int, roiEndX: Int): ByteArray
    
    fun release()        // cleanup all GL/EGL resources
}
```

### Shader (fragment):
```glsl
#extension GL_OES_EGL_image_external : require
precision mediump float;
varying vec2 vTexCoord;
uniform samplerExternalOES uTexture;
void main() {
    vec4 color = texture2D(uTexture, vTexCoord);
    float luma = dot(color.rgb, vec3(0.299, 0.587, 0.114));
    gl_FragColor = vec4(luma, 0.0, 0.0, 1.0);
}
```

### Vertex shader:
Simple fullscreen quad that maps texture coordinates 0..1 to the full FBO.

### Key implementation details:
- Use EGL14 APIs (android.opengl.EGL14)
- Create a PBuffer surface (no window needed since we're doing offscreen rendering)
- The SurfaceTexture is created with the OES texture ID: `SurfaceTexture(oesTextureId)`
- Set `surfaceTexture.setDefaultBufferSize(width, height)`
- Set an `OnFrameAvailableListener` on the SurfaceTexture to notify when frames arrive
- For glReadPixels: read only the ROI strip region. At 720p with 12% ROI width, that's ~154 pixels wide × 720 high. Read GL_RGBA format (ES 2.0 compatible), then extract the R channel (which contains luma). Or if ES 3.0 is available, use GL_RED format directly.
- The readback buffer should be a pre-allocated direct ByteBuffer to avoid GC pressure.
- Thread safety: all GL calls must happen on the same thread that created the EGL context. Use a dedicated HandlerThread.

### Frame available notification:
Use `surfaceTexture.setOnFrameAvailableListener(listener, glHandler)` where glHandler is on the GL thread. When a frame arrives, the listener triggers the extraction pipeline.

## Step 2: Create Camera2SessionManager.kt

Create `android/app/src/main/kotlin/com/paul/sprintsync/sensor_native/Camera2SessionManager.kt`

This replaces the CameraX-based `SensorNativeCameraSession.kt`.

### Design:
```kotlin
enum class CameraSessionMode { NORMAL, HS }

class Camera2SessionManager(
    private val activity: FlutterActivity,
    private val mainHandler: Handler,
    private val emitError: (String) -> Unit,
    private val emitDiagnostic: (String, String) -> Unit,
) {
    // Callbacks
    var onNormalFrame: ((image: android.media.Image) -> Unit)? = null
    var onHsFrameAvailable: (() -> Unit)? = null  // signals GL thread to extract
    
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null  // NORMAL mode only
    private var glLumaExtractor: GlLumaExtractor? = null  // HS mode only
    private var activeMode: CameraSessionMode = CameraSessionMode.NORMAL
    
    fun start(
        preferredFacing: NativeCameraFacing,
        requestedMode: CameraSessionMode,
        previewSurface: Surface?,  // TextureView surface, nullable
    )
    
    fun stop()
    
    // For HS mode: expose the GlLumaExtractor so controller can call extractRoiLuma
    fun getGlLumaExtractor(): GlLumaExtractor?
    
    val currentMode: CameraSessionMode
    val targetFpsUpper: Int?
}
```

### NORMAL mode implementation:
1. Open camera via CameraManager.openCamera()
2. Create ImageReader(640, 480, ImageFormat.YUV_420_888, 2)
3. Create capture session with ImageReader.surface + optional preview surface
4. Set repeating request with CONTROL_AE_TARGET_FPS_RANGE = highest available
5. AE/AWB warmup + lock (reuse existing policy from SensorNativeCameraPolicy)
6. ImageReader.OnImageAvailableListener delivers frames to onNormalFrame callback

### HS mode implementation:
1. Open camera (force rear if HS not available on requested facing)
2. Query StreamConfigurationMap for high-speed video sizes and FPS ranges
3. Create GlLumaExtractor(1280, 720)
4. Create constrained high-speed session with GlLumaExtractor.getSurface() + optional preview surface (max 2 surfaces)
5. Use createHighSpeedRequestList() for repeating request batching
6. Set CONTROL_AE_TARGET_FPS_RANGE to 120-120 or 30-120
7. GlLumaExtractor's OnFrameAvailableListener triggers analysis
8. On HS session creation failure, fallback to NORMAL mode automatically

### Camera selection:
Reuse the existing `SensorNativeCameraPolicy.selectCameraFacing()` logic. For HS mode, check if the selected camera supports high-speed. If not and rear does, force rear with a diagnostic event (same as the rolled-back implementation).

### Important: Keep SensorNativeCameraPolicy
The existing `SensorNativeCameraPolicy` object in `SensorNativeCameraSession.kt` has useful helpers (selectHighestFrameRateBounds, selectCameraFacing, shouldLockAeAwb). Move it to a separate file or into `Camera2SessionManager.kt`.

## Step 3: Add scoreLumaArray() to RoiFrameDiffer and add FPS monitor

Modify `android/app/src/main/kotlin/com/paul/sprintsync/sensor_native/SensorNativeMath.kt`

### Add to RoiFrameDiffer:
```kotlin
@Synchronized
fun scoreLumaArray(
    lumaData: ByteArray,
    width: Int,
    height: Int,
    roiCenterX: Double,
    roiWidth: Double,
): Double
```
This is similar to `scoreLumaPlane()` but accepts a ByteArray directly (from GL readback) instead of a ByteBuffer with rowStride/pixelStride. The luma data is tightly packed (no stride padding). The ROI extraction logic is the same (subsampled by 2x in both dimensions), but since the GL readback already contains only the ROI strip, the startX/endX calculation may differ. 

Actually, a simpler approach: if GlLumaExtractor already reads back only the ROI strip, then scoreLumaArray receives the full ROI strip and just needs to do the frame differencing (no ROI cropping needed). Add a method:
```kotlin
@Synchronized
fun scorePrecroppedLuma(
    lumaData: ByteArray,
    width: Int,   // ROI width
    height: Int,
): Double
```
This subsamples by 2x in both dimensions and computes the same diff metric as scoreLumaPlane.

### Add SensorNativeFpsMonitor class:
```kotlin
class SensorNativeFpsMonitor {
    private var lastTimestampNanos: Long? = null
    private var emaFps: Double? = null
    private var warmupStartNanos: Long? = null
    private var belowThresholdSinceNanos: Long? = null
    
    val observedFps: Double? get() = emaFps
    
    fun reset()
    fun update(timestampNanos: Long): Double?  // returns current EMA FPS
    fun shouldDowngradeHs(currentNanos: Long): Boolean  // true if EMA < 90fps for > 2s after 1.5s warmup
}
```
EMA alpha ~0.2 for FPS smoothing. Warmup period of 1.5 seconds before downgrade decisions. Downgrade threshold: EMA < 90fps sustained for 2.0 seconds in HS mode.

## Step 4: Update SensorNativeModels.kt

Modify `android/app/src/main/kotlin/com/paul/sprintsync/sensor_native/SensorNativeModels.kt`

### Add:
```kotlin
enum class NativeCameraFpsMode(val wireName: String) {
    NORMAL("normal"),
    HS120("hs120"),
}
```

### Add to NativeMonitoringConfig:
- `highSpeedEnabled: Boolean` field (default false)
- Parse from map: `(raw["highSpeedEnabled"] as? Boolean) ?: defaults.highSpeedEnabled`

## Step 5: Rewrite SensorNativeController.kt

Modify `android/app/src/main/kotlin/com/paul/sprintsync/sensor_native/SensorNativeController.kt`

This is the largest change. The controller needs to:

### Remove:
- CameraX imports (ProcessCameraProvider, ImageAnalysis, ImageProxy, PreviewView)
- `ImageAnalysis.Analyzer` interface implementation
- `analyze(image: ImageProxy)` method
- `cameraProvider` field
- `previewView: PreviewView` field
- The lazy `cameraSession: SensorNativeCameraSession`

### Add:
- Camera2SessionManager instance
- GlLumaExtractor reference (from session manager)
- Dedicated GL HandlerThread for HS mode
- Dedicated analysis executor (Executors.newSingleThreadExecutor) — already exists as `analyzerExecutor`
- `AtomicBoolean` for non-blocking analysis gating (from rolled-back fix)
- `SensorNativeFpsMonitor` instance
- `NativeCameraFpsMode` tracking
- TextureView reference (instead of PreviewView)

### Frame processing flow:

**NORMAL mode:**
Camera2SessionManager delivers `android.media.Image` via `onNormalFrame` callback on the ImageReader thread.
1. `updateStreamTelemetry(image.timestamp)` — runs immediately on callback thread:
   - Increment streamFrameCount
   - Update SensorOffsetSmoother with (sensorTimestamp - SystemClock.elapsedRealtimeNanos())
   - Update FpsMonitor
2. If `analysisInFlight.compareAndSet(false, true)`:
   - Check processEveryNFrames skip
   - Extract luma plane from image.planes[0]
   - Submit to analyzerExecutor: run scoreLumaPlane() + detectionMath.process() + emit events
   - Set analysisInFlight back to false when done
3. Close the image

**HS mode:**
GlLumaExtractor's OnFrameAvailableListener fires on the GL thread.
1. Call `glLumaExtractor.extractRoiLuma(roiStartX, roiEndX)` — this calls updateTexImage(), renders, reads back
2. Get timestamp from `glLumaExtractor.getTimestampNanos()` (SurfaceTexture.getTimestamp())
3. `updateStreamTelemetry(timestamp)` — same as NORMAL
4. If `analysisInFlight.compareAndSet(false, true)`:
   - Submit to analyzerExecutor: run scorePrecroppedLuma() + detectionMath.process() + emit events
   - Set analysisInFlight back to false when done

### HS downgrade logic:
If FpsMonitor.shouldDowngradeHs() returns true, tear down HS session and restart in NORMAL mode. Emit a diagnostic event. Only downgrade once per monitoring session.

### Event emission:
Same as current, but add fields to native_frame_stats:
- `observedFps`: from FpsMonitor
- `cameraFpsMode`: "hs120" or "normal"
- `targetFpsUpper`: from Camera2SessionManager

### Preview surface management:
- Change `previewView: PreviewView?` to `textureView: TextureView?`
- `attachPreviewSurface(textureView: TextureView)` / `detachPreviewSurface(textureView: TextureView)`
- When preview is attached, pass `Surface(textureView.surfaceTexture)` to Camera2SessionManager
- In HS mode with preview attached: 2 surfaces = GlLumaExtractor.surface + preview surface
- In HS mode without preview: 1 surface = GlLumaExtractor.surface only (frees a slot)

### Method channel:
Keep the same method channel API. The `startNativeMonitoring` method reads `highSpeedEnabled` from config and passes the appropriate mode to Camera2SessionManager.

## Step 6: Update SensorNativePreviewPlatformView.kt

Modify `android/app/src/main/kotlin/com/paul/sprintsync/sensor_native/SensorNativePreviewPlatformView.kt`

Replace PreviewView with TextureView:
```kotlin
class SensorNativePreviewPlatformView(
    context: Context,
    private val sensorNativeController: SensorNativeController,
) : PlatformView {
    private val textureView: TextureView = TextureView(context)
    
    init {
        sensorNativeController.attachPreviewSurface(textureView)
    }
    
    override fun getView(): View = textureView
    
    override fun dispose() {
        sensorNativeController.detachPreviewSurface(textureView)
    }
}
```

Also update `SensorNativePreviewViewFactory` (currently defined somewhere in the codebase, referenced by MainActivity.kt) to match.

## Step 7: Update build.gradle.kts

Modify `android/app/build.gradle.kts`

The CameraX dependencies can be removed since we're moving to Camera2 entirely:
- Remove: `androidx.camera:camera-core`, `camera-camera2`, `camera-lifecycle`, `camera-view`
- Keep: `com.google.android.gms:play-services-nearby`, `androidx.concurrent:concurrent-futures`, `com.google.guava:guava`
- The Camera2 API is part of the Android framework (android.hardware.camera2), no additional dependency needed.

## Step 8: Delete SensorNativeCameraSession.kt

Delete `android/app/src/main/kotlin/com/paul/sprintsync/sensor_native/SensorNativeCameraSession.kt`

But first, move `SensorNativeCameraPolicy` (the companion object at the bottom of the file, lines 207-261) into either Camera2SessionManager.kt or a separate SensorNativeCameraPolicy.kt file. The policy helpers (selectHighestFrameRateBounds, selectCameraFacing, shouldLockAeAwb, AE_AWB_WARMUP_MS) are still needed and tested in SensorNativeMathTest.kt.

## Step 9: Dart model changes

### Modify `lib/features/motion_detection/motion_detection_models.dart`:
Add `highSpeedEnabled` field to `MotionDetectionConfig`:
- Default: false
- Add to constructor, copyWith, toJson, fromJson
- Clamp: boolean, no clamping needed

### Modify `lib/features/motion_detection/motion_detection_controller.dart`:
Add method:
```dart
Future<void> updateHighSpeedEnabled(bool enabled) async {
    _config = _config.copyWith(highSpeedEnabled: enabled);
    await _repository.saveMotionConfig(_config);
    await _pushNativeConfig();
    notifyListeners();
}
```

### Modify `lib/features/race_session/race_session_models.dart`:
Add `highSpeedEnabled` field to `SessionDevice`:
- Default: false
- Add to constructor, copyWith, toJson, fromJson
- Ensure backward compatibility: `fromJson` defaults to false if field missing

### Modify `lib/features/race_session/race_session_controller.dart`:
Add method:
```dart
void assignHighSpeedEnabled(String deviceId, bool enabled) {
    final device = _devices[deviceId];
    if (device == null) return;
    _devices[deviceId] = device.copyWith(highSpeedEnabled: enabled);
    _broadcastSnapshot();
    notifyListeners();
}
```
Also update the local sync logic (where client applies snapshot settings before monitoring) to apply highSpeedEnabled.

### Modify `lib/features/race_session/race_session_screen.dart`:
In the lobby device row builder, add an HS toggle chip similar to the camera facing toggle:
- Host + not monitoring: editable FilterChip with key `high_speed_toggle_<deviceId>`
- Client or monitoring: read-only Chip with key `high_speed_state_<deviceId>`

## Step 10: Update tests

### Kotlin tests (`SensorNativeMathTest.kt`):
- Add test for `RoiFrameDiffer.scorePrecroppedLuma()` — verify it produces same-magnitude scores as scoreLumaPlane for equivalent data
- Add test for `SensorNativeFpsMonitor` — verify EMA convergence, warmup period, downgrade trigger in HS mode, no downgrade in NORMAL mode
- Keep existing tests for SensorNativeCameraPolicy (move policy to new location but keep tests working)

### Dart tests:
- `motion_detection_controller_test.dart`: Add test for `updateHighSpeedEnabled` persists and pushes config
- `race_session_models_test.dart`: Add test for SessionDevice highSpeedEnabled round-trip and missing-field default
- `race_session_controller_test.dart`: Add test for host high-speed assignment and client snapshot application
- `race_session_screen_test.dart`: Add test for HS toggle chip visibility in lobby

## Step 11: Verification

After implementation:
1. Run `./gradlew :app:compileDebugKotlin` to verify Android compilation
2. Run `./gradlew :app:testDebugUnitTest --tests com.paul.sprintsync.sensor_native.SensorNativeMathTest` for Kotlin unit tests
3. Run `flutter test` for all Dart tests:
   - test/motion_detection_controller_test.dart
   - test/motion_detection_engine_test.dart
   - test/motion_detection_settings_widget_test.dart
   - test/race_session_controller_test.dart
   - test/race_session_models_test.dart
   - test/race_session_screen_test.dart

## Important Notes

- The GlLumaExtractor MUST run all GL calls on a single dedicated thread (HandlerThread). EGL contexts are thread-bound.
- SurfaceTexture.getTimestamp() returns the camera sensor timestamp in nanoseconds — same domain as ImageProxy.imageInfo.timestamp. This preserves the existing SensorOffsetSmoother pipeline.
- In HS mode without preview, only 1 surface is used (GlLumaExtractor's surface), leaving room for future recording surface addition.
- The non-blocking analysis pipeline (AtomicBoolean gating) from the rolled-back commit 0981d485 is critical for HS mode — without it, analysis backpressure throttles frame delivery and causes false HS downgrade.
- The existing `SensorNativeCameraPolicy` helpers must be preserved (they're tested). Move them to a standalone object or into Camera2SessionManager.