package com.spartantech.fitsviewer

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nom.tam.fits.Fits
import nom.tam.fits.ImageHDU
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val startUri = resolveUri(intent)
        setContent {
            MaterialTheme {
                FitsApp(startUri)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val newUri = resolveUri(intent)
        if (newUri != null) {
            val restartIntent = Intent(this, MainActivity::class.java)
            restartIntent.data = newUri
            restartIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(restartIntent)
        }
    }

    private fun resolveUri(intent: Intent?): Uri? {
        if (intent == null) return null
        if (intent.data != null) return intent.data
        if (intent.clipData != null && intent.clipData!!.itemCount > 0) {
            return intent.clipData!!.getItemAt(0).uri
        }
        if (intent.action == Intent.ACTION_SEND) {
            @Suppress("DEPRECATION")
            return intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
        return null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FitsApp(initialUri: Uri?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var rawHdu by remember { mutableStateOf<ImageHDU?>(null) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var headers by remember { mutableStateOf<List<String>>(emptyList()) }

    var isStretched by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("No file loaded") }
    var isError by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var showHeaderDialog by remember { mutableStateOf(false) }
    var showLogDialog by remember { mutableStateOf(false) }
    val logMessages = remember { mutableStateListOf<String>() }

    fun addLog(msg: String) {
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        logMessages.add("$time: $msg")
        Log.d("FitsViewer", msg)
    }

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(bitmap) {
        scale = 1f
        offset = Offset.Zero
    }

    fun processImage(hdu: ImageHDU?, stretch: Boolean) {
        if (hdu == null) return
        isLoading = true
        addLog("Starting render process. Stretch: $stretch")
        scope.launch(Dispatchers.Default) {
            val result = renderBitmap(hdu, stretch) { logMsg ->
                scope.launch(Dispatchers.Main) { addLog(logMsg) }
            }

            withContext(Dispatchers.Main) {
                if (result != null) {
                    bitmap = result.first
                    statusMessage = "Loaded (${result.second})"
                    isError = false
                    addLog("Render successful: ${result.second}")
                } else {
                    isError = true
                    statusMessage = "Error rendering image."
                    addLog("Render failed (returned null)")
                }
                isLoading = false
            }
        }
    }

    fun loadFits(uri: Uri) {
        isLoading = true
        isError = false
        statusMessage = "Opening file..."
        rawHdu = null
        bitmap = null
        logMessages.clear()
        addLog("Loading file: $uri")

        scope.launch(Dispatchers.IO) {
            try {
                val stream: InputStream? = context.contentResolver.openInputStream(uri)
                if (stream == null) {
                    withContext(Dispatchers.Main) {
                        isError = true
                        statusMessage = "Could not open file stream."
                        addLog("Error: InputStream is null")
                        isLoading = false
                    }
                    return@launch
                }
                addLog("Stream opened successfully")

                val fits = Fits(stream)
                addLog("Parsing FITS...")
                val hdu = fits.readHDU()
                if (hdu == null) {
                    withContext(Dispatchers.Main) {
                        isError = true
                        statusMessage = "Invalid FITS file."
                        addLog("Error: readHDU() returned null")
                        isLoading = false
                    }
                    return@launch
                }
                addLog("HDU read. Type: ${hdu.javaClass.simpleName}")

                val headerList = mutableListOf<String>()
                val iter = hdu.header.iterator()
                while (iter.hasNext()) headerList.add(iter.next().toString())
                addLog("Headers extracted: ${headerList.size} cards")

                if (hdu is ImageHDU) {
                    withContext(Dispatchers.Main) {
                        headers = headerList
                        rawHdu = hdu
                        processImage(hdu, isStretched)
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        isError = true
                        statusMessage = "File contains Table, not Image."
                        addLog("Error: HDU is not an ImageHDU")
                        headers = headerList
                        isLoading = false
                    }
                }
                fits.close()
                stream.close()

            } catch (t: Throwable) {
                t.printStackTrace()
                withContext(Dispatchers.Main) {
                    isError = true
                    val msg = if (t is OutOfMemoryError) "Out of Memory" else t.localizedMessage ?: "Unknown Error"
                    statusMessage = "Error: $msg"
                    addLog("EXCEPTION: $msg")
                    addLog(t.stackTraceToString())
                    isLoading = false
                }
            }
        }
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) loadFits(uri)
    }

    LaunchedEffect(initialUri) {
        if (initialUri != null) loadFits(initialUri)
    }

    LaunchedEffect(isStretched) {
        if (rawHdu != null) processImage(rawHdu, isStretched)
    }

    Scaffold(
        contentWindowInsets = WindowInsets.systemBars,
        bottomBar = {
            BottomAppBar(
                containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp),
                contentColor = MaterialTheme.colorScheme.onSurface
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { launcher.launch(arrayOf("*/*")) }) {
                        Icon(Icons.Default.Search, contentDescription = "Open")
                    }
                    IconButton(
                        onClick = { showHeaderDialog = true },
                        enabled = rawHdu != null
                    ) {
                        Icon(Icons.Default.Info, contentDescription = "Headers")
                    }
                    IconButton(
                        onClick = { showLogDialog = true }
                    ) {
                        Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Logs")
                    }
                    FilterChip(
                        selected = isStretched,
                        enabled = rawHdu != null,
                        onClick = { isStretched = !isStretched },
                        label = { Text(if (isStretched) "Linear" else "Auto Stretch") },
                        leadingIcon = {
                            if (isStretched) Icon(Icons.Default.Close, contentDescription = "Reset")
                            else Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Histogram")
                        }
                    )
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .padding(paddingValues)
                .onSizeChanged { containerSize = it }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val newScale = (scale * zoom).coerceIn(1f, 5f)
                            scale = newScale

                            val maxX = (containerSize.width.toFloat() * (scale - 1)) / 2f
                            val maxY = (containerSize.height.toFloat() * (scale - 1)) / 2f

                            if (scale > 1f) {
                                val newOffset = offset + pan
                                offset = Offset(
                                    x = newOffset.x.coerceIn(-maxX, maxX),
                                    y = newOffset.y.coerceIn(-maxY, maxY)
                                )
                            } else {
                                offset = Offset.Zero
                            }
                        }
                    }
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap!!.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(
                                scaleX = scale,
                                scaleY = scale,
                                translationX = offset.x,
                                translationY = offset.y
                            ),
                        alignment = Alignment.Center
                    )
                }
                if (bitmap == null && !isLoading) {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (isError) {
                            Icon(Icons.Default.Warning, null, tint = Color.Red, modifier = Modifier.size(48.dp))
                            Spacer(Modifier.height(8.dp))
                            Text(text = statusMessage, color = Color.Red, style = MaterialTheme.typography.bodyLarge)
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = { showLogDialog = true }) { Text("View Logs") }
                        } else {
                            Icon(Icons.Default.Search, null, tint = Color.Gray, modifier = Modifier.size(64.dp))
                            Spacer(Modifier.height(16.dp))
                            Text("No FITS image loaded", color = Color.Gray)
                            Spacer(Modifier.height(16.dp))
                            Button(onClick = { launcher.launch(arrayOf("*/*")) }) { Text("Browse Files") }
                        }
                    }
                }
                if (isLoading) {
                    Column(modifier = Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Color.White)
                        Spacer(Modifier.height(16.dp))
                        Text(statusMessage, color = Color.White)
                    }
                }
            }
            if (showLogDialog) {
                Dialog(onDismissRequest = { showLogDialog = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth(0.95f).fillMaxHeight(0.85f).padding(16.dp)) {
                        Column {
                            Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("Logs", style = MaterialTheme.typography.headlineSmall)
                                IconButton(onClick = { showLogDialog = false }) { Icon(Icons.Default.Close, null) }
                            }
                            HorizontalDivider()
                            SelectionContainer {
                                LazyColumn(contentPadding = PaddingValues(16.dp), modifier = Modifier.weight(1f)) {
                                    items(logMessages) { log ->
                                        Text(text = log, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(vertical = 2.dp))
                                        HorizontalDivider(color = Color.LightGray.copy(alpha = 0.2f))
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (showHeaderDialog) {
                Dialog(onDismissRequest = { showHeaderDialog = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth(0.95f).fillMaxHeight(0.85f).padding(16.dp)) {
                        Column {
                            Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("Headers", style = MaterialTheme.typography.headlineSmall)
                                IconButton(onClick = { showHeaderDialog = false }) { Icon(Icons.Default.Close, null) }
                            }
                            HorizontalDivider()
                            var searchText by remember { mutableStateOf("") }
                            OutlinedTextField(value = searchText, onValueChange = { searchText = it }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), placeholder = { Text("Filter headers...") }, leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) }, singleLine = true)
                            val filteredHeaders = remember(headers, searchText) { headers.sorted().filter { it.contains(searchText, ignoreCase = true) } }
                            LazyColumn(contentPadding = PaddingValues(16.dp), modifier = Modifier.weight(1f)) {
                                if (filteredHeaders.isEmpty()) {
                                    item { Text("No matching headers found.", color = Color.Gray, modifier = Modifier.padding(top = 16.dp)) }
                                } else {
                                    items(filteredHeaders) { headerLine ->
                                        val key = headerLine.take(8).trim()
                                        val rawValue = if (headerLine.length > 8) headerLine.substring(8) else ""
                                        val value = if (rawValue.startsWith("= ")) rawValue.substring(2).trim() else rawValue.trim()
                                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text(text = key, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(0.4f))
                                            Text(text = value, fontFamily = FontFamily.Monospace, textAlign = TextAlign.End, modifier = Modifier.weight(0.6f))
                                        }
                                        HorizontalDivider(color = Color.LightGray.copy(alpha = 0.2f))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private interface DirectAccessor {
    fun get(x: Int, y: Int, plane: Int): Float
}

data class ChannelStats(val min: Float, val max: Float, val midtones: Float)

suspend fun renderBitmap(hdu: ImageHDU, useAutoStretch: Boolean, logger: (String) -> Unit): Pair<Bitmap, String>? {
    try {
        val axes = hdu.axes ?: return null
        val trueWidth = axes[axes.size - 1]
        val trueHeight = axes[axes.size - 2]

        var sampleSize = 1
        while ((trueWidth / sampleSize) * (trueHeight / sampleSize) > 16_000_000) { sampleSize *= 2 }
        if (sampleSize > 1) logger("Downsample: ${sampleSize}x")

        val width = trueWidth / sampleSize
        val height = trueHeight / sampleSize

        if (width.toLong() * height * 4 > 200_000_000) throw OutOfMemoryError("Bitmap too large")

        val header = hdu.header
        val bzero = header.getDoubleValue("BZERO", 0.0)
        val bscale = header.getDoubleValue("BSCALE", 1.0)

        val is3dRGB = (axes.size == 3 && axes[0] == 3)
        var bayerPat = header.getStringValue("BAYERPAT")
        if (bayerPat.isNullOrEmpty()) bayerPat = header.getStringValue("COLORTYP")
        if (bayerPat.isNullOrEmpty()) bayerPat = header.getStringValue("XBAYERPAT")
        if (bayerPat.isNullOrEmpty()) bayerPat = header.getStringValue("CFA")
        bayerPat = bayerPat?.replace("'", "")?.replace("\"", "")?.trim()?.uppercase()
        val isBayer = (!is3dRGB && bayerPat != null && bayerPat.length == 4)

        val patternName = when {
            is3dRGB -> "RGB 3D"
            isBayer -> "Bayer $bayerPat"
            else -> "Greyscale 2D"
        }
        logger("Mode: $patternName")

        val rawArray = hdu.kernel ?: return null
        val accessor = try {
            createAccessor(kernel = rawArray, w = trueWidth, h = trueHeight, bzero = bzero, bscale = bscale)
        } catch (e: Exception) { logger("Accessor Error: ${e.message}"); return null }

        if (accessor == null) { logger("Unsupported Data"); return null }

        // --- PASS 1: STATISTICS PER CHANNEL ---
        logger("Calculating Stats...")

        val samplesR = ArrayList<Float>(20000)
        val samplesG = ArrayList<Float>(20000)
        val samplesB = ArrayList<Float>(20000)

        val stride = max(1, (width * height) / 50000)

        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                val sx = x * sampleSize
                val sy = y * sampleSize

                if (is3dRGB) {
                    val r = accessor.get(sx, sy, 0)
                    val g = accessor.get(sx, sy, 1)
                    val b = accessor.get(sx, sy, 2)
                    if(!r.isNaN()) samplesR.add(r)
                    if(!g.isNaN()) samplesG.add(g)
                    if(!b.isNaN()) samplesB.add(b)
                } else if (isBayer) {
                    val isEvenRow = (sy % 2 == 0)
                    val isEvenCol = (sx % 2 == 0)

                    val v = accessor.get(sx, sy, 0)
                    if (!v.isNaN()) {
                        val colorType = when {
                            bayerPat == "RGGB" -> if (isEvenRow) (if (isEvenCol) 0 else 1) else (if (isEvenCol) 1 else 2)
                            bayerPat == "BGGR" -> if (isEvenRow) (if (isEvenCol) 2 else 1) else (if (isEvenCol) 1 else 0)
                            bayerPat == "GRBG" -> if (isEvenRow) (if (isEvenCol) 1 else 0) else (if (isEvenCol) 2 else 1)
                            bayerPat == "GBRG" -> if (isEvenRow) (if (isEvenCol) 1 else 2) else (if (isEvenCol) 0 else 1)
                            else -> 1
                        }
                        when (colorType) {
                            0 -> samplesR.add(v)
                            2 -> samplesB.add(v)
                            else -> samplesG.add(v)
                        }
                    }
                } else {
                    val v = accessor.get(sx, sy, 0)
                    if (!v.isNaN()) samplesG.add(v)
                }
                x += stride
            }
            y += stride
        }

        fun calcChannelStats(samples: ArrayList<Float>): ChannelStats {
            if (samples.isEmpty()) return ChannelStats(0f, 65535f, 0.5f)
            samples.sort()

            val minVal: Float
            val maxVal: Float
            var midtones = 0.5f

            val actualMin = samples.first()
            val actualMax = samples.last()

            if (useAutoStretch) {
                val median = samples[samples.size / 2]
                val deviations = ArrayList<Float>(samples.size)
                for(s in samples) deviations.add(abs(s - median))
                deviations.sort()
                val mad = deviations[deviations.size / 2]

                var shadows = median - (2.8f * mad)
                if (shadows < actualMin) shadows = actualMin
                minVal = shadows

                val highIdx = (samples.size * 0.9995).toInt().coerceIn(0, samples.lastIndex)
                maxVal = samples[highIdx]

                val targetBg = 0.10f
                val range = maxVal - minVal

                if (range > 0.0001f) {
                    val normMed = (median - minVal) / range
                    if (normMed > 0f && normMed < 1f) {
                        val x = normMed
                        val L = targetBg
                        midtones = (x * (L - 1f)) / (2f * x * L - x - L)
                    }
                }
            } else {
                if (actualMax <= 1.0f) {
                    minVal = 0f; maxVal = 1f
                } else {
                    minVal = 0f; maxVal = 65535f
                }
            }
            return ChannelStats(minVal, maxVal, midtones)
        }

        val statsR = calcChannelStats(samplesR)
        val statsG = calcChannelStats(samplesG)
        val statsB = calcChannelStats(samplesB)

        val rStats = if (samplesR.isEmpty()) statsG else statsR
        val gStats = statsG
        val bStats = if (samplesB.isEmpty()) statsG else statsB

        logger("R: ${rStats.min}..${rStats.max} m=${String.format("%.3f", rStats.midtones)}")
        logger("G: ${gStats.min}..${gStats.max} m=${String.format("%.3f", gStats.midtones)}")
        logger("B: ${bStats.min}..${bStats.max} m=${String.format("%.3f", bStats.midtones)}")

        // --- PASS 2: RENDER ---
        val pixels = IntArray(width * height)
        val cores = Runtime.getRuntime().availableProcessors()
        val chunkH = max(1, height / cores)

        coroutineScope {
            val jobs = (0 until height step chunkH).map { startY ->
                async(Dispatchers.Default) {
                    val endY = min(height, startY + chunkH)

                    if (is3dRGB) {
                        for (yLoop in startY until endY) {
                            for (xLoop in 0 until width) {
                                val sx = xLoop * sampleSize
                                val sy = yLoop * sampleSize
                                val idx = yLoop * width + xLoop

                                val r = mapMTF(accessor.get(sx, sy, 0), rStats)
                                val g = mapMTF(accessor.get(sx, sy, 1), gStats)
                                val b = mapMTF(accessor.get(sx, sy, 2), bStats)
                                pixels[idx] = AndroidColor.rgb(r, g, b)
                            }
                        }
                    } else if (isBayer) {
                        for (yLoop in startY until endY) {
                            for (xLoop in 0 until width) {
                                val sx = xLoop * sampleSize
                                val sy = yLoop * sampleSize
                                val idx = yLoop * width + xLoop

                                val isEvenRow = (sy % 2 == 0); val isEvenCol = (sx % 2 == 0)
                                val center = accessor.get(sx, sy, 0)

                                var rVal = 0f; var gVal = 0f; var bVal = 0f

                                // Fast integer lookup for Bayer pattern (0=R, 1=G, 2=B)
                                val colorType = when {
                                    bayerPat == "RGGB" -> if (isEvenRow) (if (isEvenCol) 0 else 1) else (if (isEvenCol) 1 else 2)
                                    bayerPat == "BGGR" -> if (isEvenRow) (if (isEvenCol) 2 else 1) else (if (isEvenCol) 1 else 0)
                                    bayerPat == "GRBG" -> if (isEvenRow) (if (isEvenCol) 1 else 0) else (if (isEvenCol) 2 else 1)
                                    bayerPat == "GBRG" -> if (isEvenRow) (if (isEvenCol) 1 else 2) else (if (isEvenCol) 0 else 1)
                                    else -> 1
                                }

                                when (colorType) {
                                    0 -> { // Red
                                        rVal = center
                                        gVal = accessor.get(min(trueWidth-1, sx+1), sy, 0)
                                        bVal = accessor.get(min(trueWidth-1, sx+1), min(trueHeight-1, sy+1), 0)
                                    }
                                    2 -> { // Blue
                                        bVal = center
                                        gVal = accessor.get(min(trueWidth-1, sx+1), sy, 0)
                                        rVal = accessor.get(max(0, sx-1), max(0, sy-1), 0)
                                    }
                                    else -> { // Green
                                        gVal = center
                                        rVal = accessor.get(min(trueWidth-1, sx+1), sy, 0)
                                        bVal = accessor.get(sx, min(trueHeight-1, sy+1), 0)
                                    }
                                }

                                pixels[idx] = AndroidColor.rgb(
                                    mapMTF(rVal, rStats),
                                    mapMTF(gVal, gStats),
                                    mapMTF(bVal, bStats)
                                )
                            }
                        }
                    } else {
                        // Mono
                        for (yLoop in startY until endY) {
                            for (xLoop in 0 until width) {
                                val sx = xLoop * sampleSize
                                val sy = yLoop * sampleSize
                                val p = mapMTF(accessor.get(sx, sy, 0), gStats)
                                pixels[yLoop * width + xLoop] = AndroidColor.rgb(p, p, p)
                            }
                        }
                    }
                }
            }
            jobs.awaitAll()
        }

        return Pair(Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888), patternName)

    } catch (t: Throwable) {
        logger("EXCEPTION: ${t.message}")
        t.printStackTrace()
        return null
    }
}

private inline fun mapMTF(v: Float, stats: ChannelStats): Int {
    if (v.isNaN() || v <= stats.min) return 0
    if (v >= stats.max) return 255

    val rangeInv = 1f / (stats.max - stats.min)
    val x = (v - stats.min) * rangeInv

    val y = if (abs(stats.midtones - 0.5f) < 0.001f) {
        x // Linear
    } else {
        // MTF
        val m = stats.midtones
        val m1 = m - 1f
        val m2 = 2f * m - 1f
        (m1 * x) / (m2 * x - m)
    }
    return (y * 255f).toInt().coerceIn(0, 255)
}

@Suppress("UNCHECKED_CAST")
private fun createAccessor(kernel: Any, w: Int, h: Int, bzero: Double, bscale: Double): DirectAccessor? {
    if (kernel is Array<*>) {
        val firstRow = kernel[0]
        if (firstRow is Array<*>) {
            if (firstRow[0] is ShortArray) {
                val data = kernel as Array<Array<ShortArray>>
                return object : DirectAccessor { override fun get(x: Int, y: Int, plane: Int) = (data[plane][y][x].toDouble() * bscale + bzero).toFloat() }
            }
            if (firstRow[0] is FloatArray) {
                val data = kernel as Array<Array<FloatArray>>
                return object : DirectAccessor { override fun get(x: Int, y: Int, plane: Int) = (data[plane][y][x].toDouble() * bscale + bzero).toFloat() }
            }
            if (firstRow[0] is IntArray) {
                val data = kernel as Array<Array<IntArray>>
                return object : DirectAccessor { override fun get(x: Int, y: Int, plane: Int) = (data[plane][y][x].toDouble() * bscale + bzero).toFloat() }
            }
            if (firstRow[0] is ByteArray) {
                val data = kernel as Array<Array<ByteArray>>
                return object : DirectAccessor { override fun get(x: Int, y: Int, plane: Int) = ((data[plane][y][x].toInt() and 0xFF).toDouble() * bscale + bzero).toFloat() }
            }
            if (firstRow[0] is DoubleArray) {
                val data = kernel as Array<Array<DoubleArray>>
                return object : DirectAccessor { override fun get(x: Int, y: Int, plane: Int) = (data[plane][y][x] * bscale + bzero).toFloat() }
            }
        }
        if (firstRow is ShortArray) {
            val data = kernel as Array<ShortArray>
            return object : DirectAccessor { override fun get(x: Int, y: Int, plane: Int) = (data[y][x].toDouble() * bscale + bzero).toFloat() }
        }
        if (firstRow is FloatArray) {
            val data = kernel as Array<FloatArray>
            return object : DirectAccessor { override fun get(x: Int, y: Int, plane: Int) = (data[y][x].toDouble() * bscale + bzero).toFloat() }
        }
        if (firstRow is IntArray) {
            val data = kernel as Array<IntArray>
            return object : DirectAccessor { override fun get(x: Int, y: Int, plane: Int) = (data[y][x].toDouble() * bscale + bzero).toFloat() }
        }
        if (firstRow is ByteArray) {
            val data = kernel as Array<ByteArray>
            return object : DirectAccessor { override fun get(x: Int, y: Int, plane: Int) = ((data[y][x].toInt() and 0xFF).toDouble() * bscale + bzero).toFloat() }
        }
        if (firstRow is DoubleArray) {
            val data = kernel as Array<DoubleArray>
            return object : DirectAccessor { override fun get(x: Int, y: Int, plane: Int) = (data[y][x] * bscale + bzero).toFloat() }
        }
    }
    return null
}