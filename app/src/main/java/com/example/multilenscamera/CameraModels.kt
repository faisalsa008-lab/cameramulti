package com.example.multilenscamera

import android.graphics.ColorMatrix
import android.net.Uri
import androidx.camera.core.ImageCapture
import kotlin.math.roundToInt

enum class LensType { ULTRA_WIDE, WIDE, TELEPHOTO, FRONT }

/**
 * One selectable "lens" in the UI.
 *
 * Two kinds exist:
 *  - Lenses behind ONE logical camera (Pixel, Samsung, ...): same [cameraId], different [cameraZoomRatio].
 *    The HAL switches the physical sensor for us when the zoom ratio crosses the lens boundary.
 *  - Lenses exposed as their own camera id (some OEMs): different [cameraId], [cameraZoomRatio] = 1.
 */
data class CameraLens(
    val cameraId: String,
    val facing: Int,               // CameraSelector.LENS_FACING_BACK / LENS_FACING_FRONT
    val type: LensType,
    val displayZoom: Float,        // zoom relative to the main lens: 0.5, 1, 3, ...
    val cameraZoomRatio: Float,    // zoom ratio to set on [cameraId] to get this lens
    val maxCameraZoom: Float,      // max zoom ratio of [cameraId]
    val isOptical: Boolean,        // false => digital crop preset
) {
    val label: String
        get() {
            val r = if (displayZoom < 1f) (displayZoom * 10).roundToInt() / 10f
            else (displayZoom * 2).roundToInt() / 2f
            return (if (r % 1f == 0f) r.toInt().toString() else r.toString()) + "x"
        }
}

enum class FlashOption(val imageCaptureMode: Int, val label: String) {
    AUTO(ImageCapture.FLASH_MODE_AUTO, "Auto"),
    ON(ImageCapture.FLASH_MODE_ON, "On"),
    OFF(ImageCapture.FLASH_MODE_OFF, "Off");

    fun next(): FlashOption = entries[(ordinal + 1) % entries.size]
}

/** Each filter is a 4x5 color matrix: used for the live preview AND for the saved photo. */
enum class PhotoFilter(val label: String, val matrix: ColorMatrix?) {
    NORMAL("Normal", null),
    BW("B&W", ColorMatrix().apply { setSaturation(0f) }),
    VINTAGE("Vintage", vintageMatrix()),
    CONTRAST("Contrast", contrastMatrix(1.5f)),
}

private fun vintageMatrix() = ColorMatrix().apply {
    setSaturation(0.6f)
    // warm tint + lifted blacks for a faded look
    postConcat(
        ColorMatrix(
            floatArrayOf(
                1.08f, 0f, 0f, 0f, 12f,
                0f, 0.98f, 0f, 0f, 6f,
                0f, 0f, 0.80f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            )
        )
    )
}

private fun contrastMatrix(c: Float): ColorMatrix {
    val t = 128f * (1f - c)
    return ColorMatrix(
        floatArrayOf(
            c, 0f, 0f, 0f, t,
            0f, c, 0f, 0f, t,
            0f, 0f, c, 0f, t,
            0f, 0f, 0f, 1f, 0f,
        )
    )
}

data class CameraUiState(
    val lenses: List<CameraLens> = emptyList(),
    val activeLens: CameraLens? = null,
    val zoom: Float = 1f,                       // display zoom (0.5x, 1x, 3.7x ...)
    val flash: FlashOption = FlashOption.AUTO,
    val hasFlashUnit: Boolean = false,
    val filter: PhotoFilter = PhotoFilter.NORMAL,
    val isCapturing: Boolean = false,
    val lastPhotoUri: Uri? = null,
    val message: String? = null,
) {
    /** Lenses of the camera facing currently in use (drives the lens pill). */
    val activeFacingLenses: List<CameraLens>
        get() = lenses.filter { it.facing == activeLens?.facing }.sortedBy { it.displayZoom }

    val canFlip: Boolean get() = lenses.map { it.facing }.distinct().size > 1
}
