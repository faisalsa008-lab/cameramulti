@file:OptIn(ExperimentalCamera2Interop::class)

package com.example.multilenscamera

import android.app.Application
import android.content.ContentUris
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.provider.MediaStore
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.MeteringPoint
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Owns camera state. The Composable screen:
 *  - collects [ui],
 *  - calls [bind] (with its LifecycleOwner + PreviewView surface) whenever the active *camera id* changes,
 *  - forwards gestures / button taps.
 * Switching between lenses of the same logical camera never rebinds; it only changes the zoom ratio.
 */
class CameraViewModel(app: Application) : AndroidViewModel(app) {

    private val appContext = app.applicationContext
    private val _ui = MutableStateFlow(CameraUiState())
    val ui: StateFlow<CameraUiState> = _ui.asStateFlow()

    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null

    init {
        val future = ProcessCameraProvider.getInstance(appContext)
        future.addListener({
            runCatching {
                val provider = future.get()
                cameraProvider = provider
                val lenses = LensDetector.detect(appContext, provider)
                val start = lenses.firstOrNull {
                    it.facing == CameraSelector.LENS_FACING_BACK && it.displayZoom == 1f
                } ?: lenses.firstOrNull()
                _ui.update {
                    it.copy(
                        lenses = lenses,
                        activeLens = start,
                        zoom = start?.displayZoom ?: 1f,
                        message = if (start == null) "No camera found on this device" else null,
                    )
                }
            }.onFailure { e -> _ui.update { it.copy(message = "Camera init failed: ${e.message}") } }
        }, ContextCompat.getMainExecutor(appContext))
        refreshLastPhoto()
    }

    // ---------------------------------------------------------------- binding

    /** Must be called on the main thread. Binds Preview + ImageCapture for the active lens' camera id. */
    fun bind(owner: LifecycleOwner, surfaceProvider: Preview.SurfaceProvider) {
        val provider = cameraProvider ?: return
        val lens = _ui.value.activeLens ?: return

        val previewSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .build()
        val captureSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
            .build()

        val preview = Preview.Builder()
            .setResolutionSelector(previewSelector)
            .build()
            .also { it.setSurfaceProvider(surfaceProvider) }

        val capture = ImageCapture.Builder()
            .setResolutionSelector(captureSelector)
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setFlashMode(_ui.value.flash.imageCaptureMode)
            .build()

        try {
            provider.unbindAll()
            val cam = provider.bindToLifecycle(owner, selectorFor(lens), preview, capture)
            camera = cam
            imageCapture = capture
            _ui.update { it.copy(hasFlashUnit = cam.cameraInfo.hasFlashUnit()) }
            applyZoom(_ui.value.zoom)
        } catch (e: Exception) {
            _ui.update { it.copy(message = "Could not open camera: ${e.message}") }
        }
    }

    /** Call when the screen leaves composition so we don't keep a LifecycleOwner alive through [camera]. */
    fun release() {
        cameraProvider?.unbindAll()
        camera = null
        imageCapture = null
    }

    private fun selectorFor(lens: CameraLens): CameraSelector =
        CameraSelector.Builder()
            .requireLensFacing(lens.facing)
            .addCameraFilter { infos -> infos.filter { Camera2CameraInfo.from(it).cameraId == lens.cameraId } }
            .build()

    // ---------------------------------------------------------------- lenses & zoom

    fun selectLens(lens: CameraLens) = setZoom(lens.displayZoom)

    fun pinchZoom(scaleFactor: Float) = setZoom(_ui.value.zoom * scaleFactor)

    /**
     * [requested] is a "display zoom" relative to the main lens. We (1) clamp it, (2) pick the lens whose
     * range contains it, (3) rebind only if that lens lives on a different camera id, (4) otherwise just set
     * the zoom ratio - on a logical multi-camera the HAL swaps the physical sensor itself (optical zoom).
     */
    fun setZoom(requested: Float) {
        val s = _ui.value
        val active = s.activeLens ?: return
        val lenses = s.activeFacingLenses
        if (lenses.isEmpty()) return

        val top = lenses.last()
        val minZoom = lenses.first().displayZoom
        val maxZoom = min(MAX_DISPLAY_ZOOM, top.displayZoom * top.maxCameraZoom / top.cameraZoomRatio)
        val zoom = requested.coerceIn(minZoom, max(minZoom, maxZoom))
        val target = lenses.lastOrNull { it.displayZoom <= zoom + 0.001f } ?: lenses.first()

        _ui.update { it.copy(activeLens = target, zoom = zoom) }
        if (target.cameraId == active.cameraId) applyZoom(zoom)   // else the UI rebinds, and bind() applies zoom
    }

    private fun applyZoom(displayZoom: Float) {
        val cam = camera ?: return
        val lens = _ui.value.activeLens ?: return
        var ratio = lens.cameraZoomRatio * displayZoom / lens.displayZoom
        cam.cameraInfo.zoomState.value?.let { ratio = ratio.coerceIn(it.minZoomRatio, it.maxZoomRatio) }
        cam.cameraControl.setZoomRatio(ratio)
    }

    fun flipCamera() {
        val s = _ui.value
        val current = s.activeLens ?: return
        val other = if (current.facing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else CameraSelector.LENS_FACING_BACK
        val target = s.lenses.filter { it.facing == other }.minByOrNull { abs(it.displayZoom - 1f) } ?: return
        _ui.update { it.copy(activeLens = target, zoom = target.displayZoom) }
    }

    // ---------------------------------------------------------------- flash / focus / filter

    fun cycleFlash() {
        val next = _ui.value.flash.next()
        imageCapture?.flashMode = next.imageCaptureMode
        _ui.update { it.copy(flash = next) }
    }

    fun focusOn(point: MeteringPoint) {
        val cam = camera ?: return
        val action = FocusMeteringAction.Builder(
            point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
        ).setAutoCancelDuration(3, TimeUnit.SECONDS).build()
        if (cam.cameraInfo.isFocusMeteringSupported(action)) cam.cameraControl.startFocusAndMetering(action)
    }

    fun setFilter(filter: PhotoFilter) = _ui.update { it.copy(filter = filter) }

    fun consumeMessage() = _ui.update { it.copy(message = null) }

    // ---------------------------------------------------------------- capture

    fun takePhoto() {
        val capture = imageCapture ?: return
        val s = _ui.value
        if (s.isCapturing) return
        _ui.update { it.copy(isCapturing = true) }
        if (s.filter == PhotoFilter.NORMAL) saveOriginal(capture) else saveFiltered(capture, s.filter)
    }

    /** No filter: let CameraX write the full-resolution JPEG (with EXIF) straight into MediaStore. */
    private fun saveOriginal(capture: ImageCapture) {
        val options = ImageCapture.OutputFileOptions.Builder(
            appContext.contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            newImageValues(pending = false),
        ).build()

        capture.takePicture(options, ioExecutor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                _ui.update { it.copy(isCapturing = false, lastPhotoUri = output.savedUri ?: it.lastPhotoUri) }
            }

            override fun onError(exception: ImageCaptureException) = fail(exception)
        })
    }

    /** Filter chosen: capture in memory, bake the colour matrix into a Bitmap, then write to MediaStore. */
    private fun saveFiltered(capture: ImageCapture, filter: PhotoFilter) {
        capture.takePicture(ioExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val rotation = image.imageInfo.rotationDegrees
                val jpeg = try {
                    val buffer = image.planes[0].buffer
                    ByteArray(buffer.remaining()).also { buffer.get(it) }
                } finally {
                    image.close()
                }
                try {
                    val uri = writeFiltered(jpeg, rotation, requireNotNull(filter.matrix))
                    _ui.update { it.copy(isCapturing = false, lastPhotoUri = uri) }
                } catch (t: Throwable) {
                    fail(t)
                }
            }

            override fun onError(exception: ImageCaptureException) = fail(exception)
        })
    }

    private fun writeFiltered(jpeg: ByteArray, rotation: Int, matrix: android.graphics.ColorMatrix): Uri {
        var bitmap = ImageFilters.decodeSampled(jpeg, MAX_FILTERED_SIDE)
        if (rotation != 0) {
            val rotated = Bitmap.createBitmap(
                bitmap, 0, 0, bitmap.width, bitmap.height,
                Matrix().apply { postRotate(rotation.toFloat()) }, true,
            )
            bitmap.recycle()
            bitmap = rotated
        }
        val filtered = ImageFilters.applyMatrix(bitmap, matrix)
        bitmap.recycle()

        val resolver = appContext.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, newImageValues(pending = true))
            ?: error("MediaStore insert failed")
        resolver.openOutputStream(uri)?.use { filtered.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            ?: error("Cannot open output stream")
        filtered.recycle()
        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        return uri
    }

    private fun newImageValues(pending: Boolean) = ContentValues().apply {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        put(MediaStore.Images.Media.DISPLAY_NAME, "IMG_$stamp")
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/$ALBUM")
        if (pending) put(MediaStore.Images.Media.IS_PENDING, 1)
    }

    private fun fail(t: Throwable) {
        _ui.update { it.copy(isCapturing = false, message = "Capture failed: ${t.message}") }
    }

    /** Newest photo in our own album (apps can read their own MediaStore entries without a permission on API 29+). */
    private fun refreshLastPhoto() {
        ioExecutor.execute {
            runCatching {
                appContext.contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Images.Media._ID),
                    "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?",
                    arrayOf("Pictures/$ALBUM%"),
                    "${MediaStore.Images.Media.DATE_ADDED} DESC",
                )?.use { c ->
                    if (c.moveToFirst()) {
                        val id = c.getLong(0)
                        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                        _ui.update { it.copy(lastPhotoUri = it.lastPhotoUri ?: uri) }
                    }
                }
            }
        }
    }

    override fun onCleared() {
        ioExecutor.shutdown()
        super.onCleared()
    }

    private companion object {
        const val ALBUM = "MultiLens"
        const val MAX_DISPLAY_ZOOM = 10f
        const val MAX_FILTERED_SIDE = 4096
    }
}
