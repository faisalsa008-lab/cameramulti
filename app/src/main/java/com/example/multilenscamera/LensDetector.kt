@file:OptIn(ExperimentalCamera2Interop::class)

package com.example.multilenscamera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import kotlin.math.hypot

/**
 * Discovers the lenses of the device.
 *
 * Strategy:
 *  1. Take CameraX's default back camera (usually the LOGICAL multi-camera, id "0").
 *  2. Ask Camera2 for its physical sub-cameras, compute each one's 35mm-equivalent focal length and
 *     divide by the main lens' value => zoom ratio (0.5x ultra-wide, 3x tele, ...).
 *  3. Add any OTHER back camera ids the OEM exposes (standalone ultra-wide / tele), de-duplicating
 *     macro/depth/duplicate sensors by field-of-view.
 *  4. If there is no optical lens >= 2x, add a digital 2x crop preset.
 *
 * This is heuristic: OEMs report characteristics differently, so test on real hardware.
 */
object LensDetector {

    private const val SAME_FOV_MIN = 0.87f
    private const val SAME_FOV_MAX = 1.15f

    fun detect(context: Context, provider: ProcessCameraProvider): List<CameraLens> {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val infos = provider.availableCameraInfos
        val lenses = mutableListOf<CameraLens>()
        runCatching { detectBack(manager, infos) }.onSuccess { lenses += it }
        runCatching { detectFront(manager, infos) }.onSuccess { lenses += it }
        return lenses
    }

    private fun detectBack(manager: CameraManager, infos: List<CameraInfo>): List<CameraLens> {
        val back = CameraSelector.LENS_FACING_BACK
        val mainId = idOf(CameraSelector.DEFAULT_BACK_CAMERA.filter(infos).first())
        val mainChars = manager.getCameraCharacteristics(mainId)
        val mainEquiv = equivFocal(mainChars) ?: 26f
        val (zoomMin, zoomMax) = zoomRange(mainChars)

        val lenses = mutableListOf<CameraLens>()
        fun add(id: String, ratio: Float, cameraZoom: Float, maxZoom: Float, optical: Boolean) {
            lenses += CameraLens(id, back, typeFor(ratio), ratio, cameraZoom, maxZoom, optical)
        }

        // 1) main lens
        add(mainId, 1f, 1f, zoomMax, true)

        // 2) physical lenses behind the logical camera (reachable through zoom ratio)
        mainChars.physicalCameraIds
            .mapNotNull { pid ->
                runCatching { manager.getCameraCharacteristics(pid) }.getOrNull()
                    ?.let { equivFocal(it) }?.div(mainEquiv)
            }
            .filter { it !in SAME_FOV_MIN..SAME_FOV_MAX && it >= zoomMin * 0.85f && it <= zoomMax }
            .sorted()
            .forEach { r ->
                val z = r.coerceAtLeast(zoomMin)
                if (lenses.none { z / it.displayZoom in SAME_FOV_MIN..SAME_FOV_MAX }) {
                    add(mainId, z, z, zoomMax, true)
                }
            }

        // 3) lenses the OEM exposes as separate camera ids
        for (info in infos) {
            if (info.lensFacing != back) continue
            val id = idOf(info)
            if (id == mainId) continue
            val chars = runCatching { manager.getCameraCharacteristics(id) }.getOrNull() ?: continue
            val ratio = (equivFocal(chars) ?: continue) / mainEquiv
            if (lenses.any { ratio / it.displayZoom in SAME_FOV_MIN..SAME_FOV_MAX }) continue
            add(id, ratio, 1f, zoomRange(chars).second, true)
        }

        // 4) digital fallback preset
        if (lenses.none { it.displayZoom >= 2f } && zoomMax >= 2f) {
            lenses += CameraLens(mainId, back, LensType.WIDE, 2f, 2f, zoomMax, false)
        }
        return lenses.sortedBy { it.displayZoom }
    }

    private fun detectFront(manager: CameraManager, infos: List<CameraInfo>): List<CameraLens> {
        val id = idOf(CameraSelector.DEFAULT_FRONT_CAMERA.filter(infos).first())
        val maxZoom = zoomRange(manager.getCameraCharacteristics(id)).second
        return listOf(
            CameraLens(id, CameraSelector.LENS_FACING_FRONT, LensType.FRONT, 1f, 1f, maxZoom, true)
        )
    }

    private fun idOf(info: CameraInfo): String = Camera2CameraInfo.from(info).cameraId

    private fun typeFor(ratio: Float) = when {
        ratio < SAME_FOV_MIN -> LensType.ULTRA_WIDE
        ratio > SAME_FOV_MAX -> LensType.TELEPHOTO
        else -> LensType.WIDE
    }

    /** 35mm-equivalent focal length in mm. */
    private fun equivFocal(c: CameraCharacteristics): Float? {
        val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull() ?: return null
        val size = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return null
        val diagonal = hypot(size.width, size.height)
        return if (diagonal > 0f) focal * 43.27f / diagonal else null
    }

    private fun zoomRange(c: CameraCharacteristics): Pair<Float, Float> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.let { return it.lower to it.upper }
        }
        return 1f to (c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f)
    }
}
