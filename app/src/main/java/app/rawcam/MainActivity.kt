package app.rawcam

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Size
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExtendableBuilder
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Минималистичная камера в стиле iOS.
 *
 * Главное отличие от стандартных камер: шумоподавление выключено на уровне Camera2
 * (NOISE_REDUCTION_MODE_OFF), расширения вендора (HDR, «ночь», портрет) не используются.
 * Что отдаёт сенсор после базовой обработки ISP — то и попадает в файл.
 */
class MainActivity : ComponentActivity() {

    private enum class Flash { AUTO, ON, OFF }

    private lateinit var preview: PreviewView
    private lateinit var shutter: ShutterButton
    private lateinit var focusRing: View
    private lateinit var timer: TextView
    private lateinit var zoomLabel: TextView
    private lateinit var modeRow: LinearLayout
    private lateinit var modeVideo: TextView
    private lateinit var modePhoto: TextView
    private lateinit var flashBtn: ImageView
    private lateinit var flipBtn: ImageView
    private lateinit var thumb: ImageView
    private lateinit var permText: TextView

    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null

    private var videoMode = false
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var flash = Flash.AUTO
    private var lastUri: Uri? = null

    private val bg = Executors.newSingleThreadExecutor()

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (hasPerm(Manifest.permission.CAMERA)) startCamera() else permText.visibility = View.VISIBLE
        }

    private val orientationListener by lazy {
        object : OrientationEventListener(this) {
            override fun onOrientationChanged(o: Int) {
                if (o == ORIENTATION_UNKNOWN) return
                val rotation = when (o) {
                    in 45..134 -> Surface.ROTATION_270
                    in 135..224 -> Surface.ROTATION_180
                    in 225..314 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
                imageCapture?.targetRotation = rotation
                videoCapture?.targetRotation = rotation
            }
        }
    }

    private val scaleDetector by lazy {
        ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val cam = camera ?: return true
                val zs = cam.cameraInfo.zoomState.value ?: return true
                val target = (zs.zoomRatio * detector.scaleFactor).coerceIn(zs.minZoomRatio, zs.maxZoomRatio)
                cam.cameraControl.setZoomRatio(target)
                return true
            }
        })
    }

    private val gestures by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                focusAt(e.x, e.y)
                return true
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (e1 == null || scaleDetector.isInProgress) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                if (abs(dx) > abs(dy) * 1.5f && abs(dx) > dp(60)) {
                    // свайп вправо — левый режим (видео), влево — правый (фото)
                    setVideoMode(dx > 0)
                    return true
                }
                return false
            }
        })
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        preview = findViewById(R.id.preview)
        shutter = findViewById(R.id.shutter)
        focusRing = findViewById(R.id.focusRing)
        timer = findViewById(R.id.timer)
        zoomLabel = findViewById(R.id.zoomLabel)
        modeRow = findViewById(R.id.modeRow)
        modeVideo = findViewById(R.id.modeVideo)
        modePhoto = findViewById(R.id.modePhoto)
        flashBtn = findViewById(R.id.flashBtn)
        flipBtn = findViewById(R.id.flipBtn)
        thumb = findViewById(R.id.thumb)
        permText = findViewById(R.id.permText)

        preview.scaleType = PreviewView.ScaleType.FIT_START
        thumb.clipToOutline = true

        preview.setOnTouchListener { _, e ->
            scaleDetector.onTouchEvent(e)
            gestures.onTouchEvent(e)
            true
        }
        shutter.setOnClickListener { if (videoMode) toggleRecording() else takePhoto() }
        modeVideo.setOnClickListener { setVideoMode(true) }
        modePhoto.setOnClickListener { setVideoMode(false) }
        flashBtn.setOnClickListener { cycleFlash() }
        flipBtn.setOnClickListener { flip() }
        thumb.setOnClickListener { openLast() }
        zoomLabel.setOnClickListener { camera?.cameraControl?.setZoomRatio(1f) }

        applyPreviewMargin()
        updateModeUi(animate = false)
        updateFlashIcon()

        if (hasPerm(Manifest.permission.CAMERA)) {
            startCamera()
        } else {
            permLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
        }
    }

    override fun onResume() {
        super.onResume()
        if (permText.visibility == View.VISIBLE && hasPerm(Manifest.permission.CAMERA)) {
            permText.visibility = View.GONE
            startCamera()
        }
    }

    override fun onStart() {
        super.onStart()
        if (orientationListener.canDetectOrientation()) orientationListener.enable()
    }

    override fun onStop() {
        orientationListener.disable()
        recording?.stop()
        super.onStop()
    }

    override fun onDestroy() {
        bg.shutdown()
        super.onDestroy()
    }

    // ───────────────────────── Камера ─────────────────────────

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            provider = future.get()
            bind()
        }, ContextCompat.getMainExecutor(this))
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun bind() {
        val p = provider ?: return
        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        val info = selector.filter(p.availableCameraInfos).firstOrNull() ?: return
        val nr = pickNoiseReductionMode(Camera2CameraInfo.from(info))

        val ratioStrategy = if (videoMode) {
            AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
        } else {
            AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
        }

        val previewUseCase = Preview.Builder()
            .apply { rawOptions(nr) }
            .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(ratioStrategy).build())
            .build()
            .also { it.setSurfaceProvider(preview.surfaceProvider) }

        camera?.cameraInfo?.zoomState?.removeObservers(this)
        p.unbindAll()

        val cam = if (videoMode) {
            val recorder = Recorder.Builder()
                .setQualitySelector(
                    QualitySelector.fromOrderedList(
                        VIDEO_QUALITIES,
                        FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
                    )
                )
                .build()
            val vc = VideoCapture.Builder(recorder)
                .apply { rawOptions(nr) }
                .build()
            videoCapture = vc
            imageCapture = null
            p.bindToLifecycle(this, selector, previewUseCase, vc)
        } else {
            val ic = ImageCapture.Builder()
                .apply { rawOptions(nr) }
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setJpegQuality(100)
                .setFlashMode(imageFlashMode())
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(ratioStrategy)
                        .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                        .build()
                )
                .build()
            imageCapture = ic
            videoCapture = null
            p.bindToLifecycle(this, selector, previewUseCase, ic)
        }
        camera = cam

        cam.cameraInfo.zoomState.observe(this) { zoomLabel.text = formatZoom(it.zoomRatio) }
        val hasFlash = cam.cameraInfo.hasFlashUnit()
        flashBtn.visibility = if (hasFlash) View.VISIBLE else View.INVISIBLE
        if (hasFlash && videoMode && flash == Flash.ON) cam.cameraControl.enableTorch(true)
    }

    /** Выбираем режим без шумодава: OFF, а если его нет — MINIMAL. Если нет ни того, ни другого — не трогаем. */
    @OptIn(ExperimentalCamera2Interop::class)
    private fun pickNoiseReductionMode(info: Camera2CameraInfo): Int? {
        val modes = info.getCameraCharacteristic(
            CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES
        ) ?: return null
        return when {
            CaptureRequest.NOISE_REDUCTION_MODE_OFF in modes -> CaptureRequest.NOISE_REDUCTION_MODE_OFF
            CaptureRequest.NOISE_REDUCTION_MODE_MINIMAL in modes -> CaptureRequest.NOISE_REDUCTION_MODE_MINIMAL
            else -> null
        }
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun <T> ExtendableBuilder<T>.rawOptions(nr: Int?) {
        val ext = Camera2Interop.Extender(this)
        if (nr != null) ext.setCaptureRequestOption(CaptureRequest.NOISE_REDUCTION_MODE, nr)
        if (SHARPENING_OFF) ext.setCaptureRequestOption(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
    }

    // ───────────────────────── Фото ─────────────────────────

    private fun takePhoto() {
        val ic = imageCapture ?: return
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "IMG_${timestamp()}")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/Camera")
        }
        val options = ImageCapture.OutputFileOptions.Builder(
            contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
        ).build()

        preview.alpha = 0.25f
        preview.animate().alpha(1f).setDuration(140).start()

        ic.takePicture(options, ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                    result.savedUri?.let { showThumb(it) }
                }

                override fun onError(exception: ImageCaptureException) {
                    Toast.makeText(this@MainActivity, "Не удалось сохранить фото", Toast.LENGTH_SHORT).show()
                }
            })
    }

    // ───────────────────────── Видео ─────────────────────────

    @SuppressLint("MissingPermission")
    private fun toggleRecording() {
        recording?.let {
            it.stop()
            return
        }
        val vc = videoCapture ?: return
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "VID_${timestamp()}")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/Camera")
        }
        val output = MediaStoreOutputOptions.Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(values)
            .build()

        var pending = vc.output.prepareRecording(this, output)
        if (hasPerm(Manifest.permission.RECORD_AUDIO)) pending = pending.withAudioEnabled()

        recording = pending.start(ContextCompat.getMainExecutor(this)) { event ->
            when (event) {
                is VideoRecordEvent.Start -> setRecordingUi(true)
                is VideoRecordEvent.Status ->
                    timer.text = formatTime(event.recordingStats.recordedDurationNanos)
                is VideoRecordEvent.Finalize -> {
                    recording = null
                    setRecordingUi(false)
                    if (!event.hasError()) {
                        showThumb(event.outputResults.outputUri)
                    } else if (event.error != VideoRecordEvent.Finalize.ERROR_NO_VALID_DATA) {
                        Toast.makeText(this, "Ошибка записи видео", Toast.LENGTH_SHORT).show()
                    }
                }
                else -> Unit
            }
        }
    }

    private fun setRecordingUi(on: Boolean) {
        shutter.recording = on
        if (on) timer.text = "00:00"
        timer.visibility = if (on) View.VISIBLE else View.INVISIBLE
        val alpha = if (on) 0f else 1f
        for (v in listOf(modeRow, flipBtn, thumb)) v.animate().alpha(alpha).setDuration(150).start()
    }

    // ───────────────────────── Управление ─────────────────────────

    private fun setVideoMode(video: Boolean) {
        if (recording != null || video == videoMode) return
        videoMode = video
        if (video && flash == Flash.AUTO) flash = Flash.OFF
        shutter.videoMode = video
        applyPreviewMargin()
        updateModeUi(animate = true)
        updateFlashIcon()
        bind()
    }

    private fun flip() {
        if (recording != null) return
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        flipBtn.animate().rotationBy(180f).setDuration(250).start()
        bind()
    }

    private fun cycleFlash() {
        flash = if (videoMode) {
            if (flash == Flash.ON) Flash.OFF else Flash.ON
        } else {
            Flash.values()[(flash.ordinal + 1) % Flash.values().size]
        }
        imageCapture?.flashMode = imageFlashMode()
        camera?.cameraControl?.enableTorch(videoMode && flash == Flash.ON)
        updateFlashIcon()
    }

    private fun imageFlashMode() = when (flash) {
        Flash.AUTO -> ImageCapture.FLASH_MODE_AUTO
        Flash.ON -> ImageCapture.FLASH_MODE_ON
        Flash.OFF -> ImageCapture.FLASH_MODE_OFF
    }

    private fun updateFlashIcon() {
        flashBtn.setImageResource(
            when (flash) {
                Flash.AUTO -> R.drawable.ic_flash_auto
                Flash.ON -> R.drawable.ic_flash_on
                Flash.OFF -> R.drawable.ic_flash_off
            }
        )
        flashBtn.setColorFilter(if (flash == Flash.ON) YELLOW else Color.WHITE)
    }

    private fun updateModeUi(animate: Boolean) {
        modeVideo.setTextColor(if (videoMode) YELLOW else Color.WHITE)
        modePhoto.setTextColor(if (videoMode) Color.WHITE else YELLOW)
        shutter.videoMode = videoMode

        // выбранный режим всегда по центру экрана, как в iOS
        modeRow.post {
            val selected = if (videoMode) modeVideo else modePhoto
            val parentWidth = (modeRow.parent as View).width
            val selectedCenter = modeRow.left + selected.left + selected.width / 2f
            modeRow.animate()
                .translationX(parentWidth / 2f - selectedCenter)
                .setDuration(if (animate) 200 else 0)
                .start()
        }
    }

    private fun applyPreviewMargin() {
        val lp = preview.layoutParams as FrameLayout.LayoutParams
        lp.topMargin = if (videoMode) 0 else dp(48)
        preview.layoutParams = lp
    }

    private fun focusAt(x: Float, y: Float) {
        val cam = camera ?: return
        val point = preview.meteringPointFactory.createPoint(x, y)
        cam.cameraControl.startFocusAndMetering(
            FocusMeteringAction.Builder(point).setAutoCancelDuration(4, TimeUnit.SECONDS).build()
        )
        focusRing.animate().cancel()
        focusRing.x = preview.x + x - focusRing.width / 2f
        focusRing.y = preview.y + y - focusRing.height / 2f
        focusRing.visibility = View.VISIBLE
        focusRing.alpha = 1f
        focusRing.scaleX = 1.3f
        focusRing.scaleY = 1.3f
        focusRing.animate().scaleX(1f).scaleY(1f).setDuration(180).withEndAction {
            focusRing.animate().alpha(0f).setStartDelay(700).setDuration(250).withEndAction {
                focusRing.visibility = View.GONE
            }.start()
        }.start()
    }

    // ───────────────────────── Миниатюра ─────────────────────────

    private fun showThumb(uri: Uri) {
        lastUri = uri
        bg.execute {
            val bmp = try {
                contentResolver.loadThumbnail(uri, Size(256, 256), null)
            } catch (e: Exception) {
                null
            }
            if (bmp != null) runOnUiThread { thumb.setImageBitmap(bmp) }
        }
    }

    private fun openLast() {
        if (recording != null) return
        val uri = lastUri ?: return
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, contentResolver.getType(uri))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Нет приложения для просмотра", Toast.LENGTH_SHORT).show()
        }
    }

    // ───────────────────────── Утилиты ─────────────────────────

    private fun hasPerm(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    private fun timestamp() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    private fun formatZoom(z: Float): String =
        if (abs(z - z.roundToInt()) < 0.05f) "${z.roundToInt()}×" else String.format(Locale.US, "%.1f×", z)

    private fun formatTime(nanos: Long): String {
        val s = (nanos / 1_000_000_000L).toInt()
        return String.format(Locale.US, "%02d:%02d", s / 60, s % 60)
    }

    private companion object {
        /** true — дополнительно выключить программную резкость (EDGE_MODE_OFF). Картинка станет «мягче» и шумнее. */
        const val SHARPENING_OFF = false

        /** Порядок качества записи: 4K → 1080p → 720p. */
        val VIDEO_QUALITIES = listOf(Quality.UHD, Quality.FHD, Quality.HD)

        val YELLOW = Color.parseColor("#FFD60A")
    }
}
