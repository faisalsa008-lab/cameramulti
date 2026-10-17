package com.example.multilenscamera

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private val Accent = Color(0xFFFFD60A)

private data class FocusTap(val offset: Offset, val id: Long = System.nanoTime())

private fun Context.hasCameraPermission() =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

// ===================================================================== permission gate

@Composable
fun CameraRoute(vm: CameraViewModel = viewModel()) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(context.hasCameraPermission()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }

    // User may come back from system settings having flipped the switch.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { granted = context.hasCameraPermission() }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }

    if (granted) {
        CameraScreen(vm)
    } else {
        PermissionPrompt(
            onRequest = { launcher.launch(Manifest.permission.CAMERA) },
            onOpenSettings = {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                )
            },
        )
    }
}

@Composable
private fun PermissionPrompt(onRequest: () -> Unit, onOpenSettings: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Camera access needed", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.size(8.dp))
        Text(
            "MultiLens needs the camera to show the live preview and take photos.",
            color = Color.LightGray,
        )
        Spacer(Modifier.size(24.dp))
        Button(onClick = onRequest) { Text("Allow camera") }
        TextButton(onClick = onOpenSettings) { Text("Open app settings") }
    }
}

// ===================================================================== main screen

@Composable
fun CameraScreen(vm: CameraViewModel) {
    val state by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // COMPATIBLE = TextureView, which lets us colour-filter the live preview through a layer paint.
    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FIT_CENTER
        }
    }
    var focusTap by remember { mutableStateOf<FocusTap?>(null) }

    // Rebind only when the *camera id* changes (front/back flip, or a lens that is its own camera id).
    LaunchedEffect(state.activeLens?.cameraId, lifecycleOwner) {
        if (state.activeLens != null) vm.bind(lifecycleOwner, previewView.surfaceProvider)
    }
    LaunchedEffect(state.filter) { previewView.applyColorFilter(state.filter.matrix) }
    LaunchedEffect(focusTap) { if (focusTap != null) { delay(1200); focusTap = null } }
    LaunchedEffect(state.message) {
        state.message?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            vm.consumeMessage()
        }
    }
    DisposableEffect(lifecycleOwner) { onDispose { vm.release() } }

    Column(
        Modifier.fillMaxSize().background(Color.Black).systemBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TopBar(state, onFlash = vm::cycleFlash, onFlip = vm::flipCamera)

        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .pointerInput(Unit) {                       // pinch-to-zoom
                    detectTransformGestures { _, _, zoom, _ -> if (zoom != 1f) vm.pinchZoom(zoom) }
                }
                .pointerInput(Unit) {                       // tap-to-focus
                    detectTapGestures { offset ->
                        focusTap = FocusTap(offset)
                        vm.focusOn(previewView.meteringPointFactory.createPoint(offset.x, offset.y))
                    }
                }
        ) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            focusTap?.let { FocusRing(it) }
            if (state.isCapturing) Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.25f)))
            LensPill(
                lenses = state.activeFacingLenses,
                active = state.activeLens,
                zoom = state.zoom,
                onSelect = vm::selectLens,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
            )
        }

        Spacer(Modifier.weight(1f))
        FilterRow(selected = state.filter, onSelect = vm::setFilter)

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            GalleryThumb(state.lastPhotoUri) { state.lastPhotoUri?.let { openInGallery(context, it) } }
            ShutterButton(enabled = !state.isCapturing && state.activeLens != null, onClick = vm::takePhoto)
            Spacer(Modifier.size(52.dp)) // keeps the shutter centred
        }
    }
}

// ===================================================================== pieces

@Composable
private fun TopBar(state: CameraUiState, onFlash: () -> Unit, onFlip: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        IconButton(onClick = onFlash, enabled = state.hasFlashUnit) {
            Icon(
                imageVector = when (state.flash) {
                    FlashOption.AUTO -> Icons.Default.FlashAuto
                    FlashOption.ON -> Icons.Default.FlashOn
                    FlashOption.OFF -> Icons.Default.FlashOff
                },
                contentDescription = "Flash ${state.flash.label}",
                tint = if (state.hasFlashUnit) Color.White else Color.DarkGray,
            )
        }
        Text(
            String.format(Locale.US, "%.1fx", state.zoom),
            color = Accent, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
        )
        IconButton(onClick = onFlip, enabled = state.canFlip) {
            Icon(Icons.Default.FlipCameraAndroid, "Switch camera", tint = Color.White)
        }
    }
}

@Composable
private fun LensPill(
    lenses: List<CameraLens>,
    active: CameraLens?,
    zoom: Float,
    onSelect: (CameraLens) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (lenses.size < 2) return
    Row(
        modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        lenses.forEach { lens ->
            val selected = lens == active
            // Like stock camera apps: the selected button shows the live zoom if it's between lens stops.
            val text = if (selected && abs(zoom - lens.displayZoom) > 0.05f)
                String.format(Locale.US, "%.1fx", zoom) else lens.label
            Box(
                Modifier
                    .size(if (selected) 44.dp else 36.dp)
                    .clip(CircleShape)
                    .background(if (selected) Color.White.copy(alpha = 0.25f) else Color.Transparent)
                    .clickable { onSelect(lens) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text,
                    color = if (selected) Accent else Color.White,
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun FocusRing(tap: FocusTap) {
    val scale = remember(tap.id) { Animatable(1.5f) }
    LaunchedEffect(tap.id) { scale.animateTo(1f, tween(250)) }
    val sizePx = with(LocalDensity.current) { 72.dp.toPx() }
    Box(
        Modifier
            .offset { IntOffset((tap.offset.x - sizePx / 2).roundToInt(), (tap.offset.y - sizePx / 2).roundToInt()) }
            .size(72.dp)
            .scale(scale.value)
            .border(2.dp, Accent, RoundedCornerShape(16.dp))
    )
}

@Composable
private fun FilterRow(selected: PhotoFilter, onSelect: (PhotoFilter) -> Unit) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(PhotoFilter.entries) { filter ->
            val isSelected = filter == selected
            Text(
                filter.label,
                color = if (isSelected) Color.Black else Color.White,
                fontSize = 13.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(if (isSelected) Accent else Color(0xFF2A2A2A))
                    .clickable { onSelect(filter) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun ShutterButton(enabled: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.9f else 1f, label = "shutter")
    Box(
        Modifier
            .size(78.dp)
            .scale(scale)
            .border(4.dp, Color.White, CircleShape)
            .padding(7.dp)
            .clip(CircleShape)
            .background(if (enabled) Color.White else Color.Gray)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
    )
}

@Composable
private fun GalleryThumb(uri: Uri?, onClick: () -> Unit) {
    Box(
        Modifier
            .size(52.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF2A2A2A))
            .border(1.dp, Color.White.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
            .clickable(enabled = uri != null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (uri != null) {
            AsyncImage(model = uri, contentDescription = "Last photo", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(Icons.Default.PhotoLibrary, null, tint = Color.Gray)
        }
    }
}

private fun openInGallery(context: Context, uri: Uri) {
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, "image/*")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { context.startActivity(intent) }
}
