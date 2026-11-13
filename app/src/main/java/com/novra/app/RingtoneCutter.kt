package com.novra.app

import android.content.ContentValues
import android.content.Context
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(UnstableApi::class)
@Composable
internal fun RingtoneCutterDialog(
    context: Context,
    media: LocalMedia,
    controller: MediaController?,
    durationMs: Long,
    language: String,
    onDismiss: () -> Unit
) {
    val total = durationMs.coerceAtLeast(1_000L)
    var startMs by remember(media.id) { mutableLongStateOf(0L) }
    var endMs by remember(media.id) { mutableLongStateOf(total) }
    var exporting by remember { mutableStateOf(false) }
    var previewing by remember { mutableStateOf(false) }
    var returnPosition by remember { mutableLongStateOf(0L) }
    var wasPlaying by remember { mutableStateOf(false) }
    var pendingRingtone by remember { mutableStateOf<Uri?>(null) }
    val scope = rememberCoroutineScope()

    val settingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val uri = pendingRingtone
        if (uri != null) {
            if (Settings.System.canWrite(context)) {
                runCatching { RingtoneManager.setActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE, uri) }
                    .onSuccess { Toast.makeText(context, if (language == "ar") "تم تعيين نغمة الرنين" else "Default ringtone updated", Toast.LENGTH_LONG).show() }
                    .onFailure { Toast.makeText(context, if (language == "ar") "تم حفظ النغمة لكن تعذر تعيينها تلقائيًا" else "Saved, but Android could not set it automatically", Toast.LENGTH_LONG).show() }
            } else {
                Toast.makeText(context, if (language == "ar") "تم حفظ الملف داخل مجلد Ringtones ويمكنك اختياره من إعدادات الصوت." else "Saved in Ringtones. You can select it from Android sound settings.", Toast.LENGTH_LONG).show()
            }
            pendingRingtone = null
            onDismiss()
        }
    }

    fun stopPreview() {
        if (previewing) {
            controller?.pause()
            controller?.seekTo(returnPosition)
            if (wasPlaying) controller?.play()
            previewing = false
        }
    }

    LaunchedEffect(previewing, endMs, controller) {
        if (previewing) {
            while (previewing) {
                delay(100)
                if ((controller?.currentPosition ?: 0L) >= endMs) stopPreview()
            }
        }
    }

    val safeStart = startMs.coerceIn(0L, total - 1_000L)
    val safeEnd = endMs.coerceIn(safeStart + 1_000L, total)

    AlertDialog(
        onDismissRequest = { if (!exporting) { stopPreview(); onDismiss() } },
        title = { Text(if (language == "ar") "قص الأغنية كنغمة رنين" else "Trim ringtone") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(if (language == "ar") "حدد بداية ونهاية الجزء الذي تريد استخدامه." else "Choose the start and end of the ringtone clip.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(if (language == "ar") "البداية" else "Start")
                    Text(formatDuration(safeStart), color = MaterialTheme.colorScheme.primary)
                }
                Slider(
                    value = safeStart.toFloat(),
                    onValueChange = { startMs = it.toLong().coerceIn(0L, (safeEnd - 1_000L).coerceAtLeast(0L)) },
                    valueRange = 0f..(total - 1_000L).coerceAtLeast(0L).toFloat()
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(if (language == "ar") "النهاية" else "End")
                    Text(formatDuration(safeEnd), color = MaterialTheme.colorScheme.primary)
                }
                Slider(
                    value = safeEnd.toFloat(),
                    onValueChange = { endMs = it.toLong().coerceIn((safeStart + 1_000L).coerceAtMost(total), total) },
                    valueRange = (safeStart + 1_000L).coerceAtMost(total).toFloat()..total.toFloat()
                )
                Text(if (language == "ar") "مدة النغمة: ${formatDuration(safeEnd-safeStart)}" else "Clip length: ${formatDuration(safeEnd-safeStart)}", style = MaterialTheme.typography.labelSmall)
                if (exporting) {
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(if (language == "ar") "جارٍ تجهيز ملف النغمة..." else "Exporting ringtone…", style = MaterialTheme.typography.labelSmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !exporting && safeEnd - safeStart >= 1_000L,
                onClick = {
                    stopPreview()
                    exporting = true
                    val outputFile = File(context.cacheDir, "viora_ringtone_${System.currentTimeMillis()}.m4a")
                    val clipped = MediaItem.Builder().setUri(media.uri)
                        .setClippingConfiguration(
                            MediaItem.ClippingConfiguration.Builder()
                                .setStartPositionMs(safeStart)
                                .setEndPositionMs(safeEnd)
                                .build()
                        ).build()
                    val listener = object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            scope.launch(Dispatchers.IO) {
                                var saved: Uri? = null
                                runCatching {
                                    val safeTitle = media.title.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().take(42).ifBlank { "Viora" }
                                    val values = ContentValues().apply {
                                        put(MediaStore.MediaColumns.DISPLAY_NAME, "${safeTitle}_ringtone.m4a")
                                        put(MediaStore.MediaColumns.MIME_TYPE, "audio/mp4")
                                        put(MediaStore.Audio.Media.IS_RINGTONE, 1)
                                        put(MediaStore.Audio.Media.IS_NOTIFICATION, 0)
                                        put(MediaStore.Audio.Media.IS_ALARM, 0)
                                        put(MediaStore.Audio.Media.IS_MUSIC, 0)
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_RINGTONES)
                                    }
                                    val uri = context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                                        ?: error("Could not create ringtone entry")
                                    try {
                                        outputFile.inputStream().use { input ->
                                            context.contentResolver.openOutputStream(uri)?.use { output -> input.copyTo(output) }
                                                ?: error("Could not write ringtone")
                                        }
                                        saved = uri
                                    } catch (error: Throwable) {
                                        context.contentResolver.delete(uri, null, null)
                                        throw error
                                    }
                                }
                                outputFile.delete()
                                withContext(Dispatchers.Main) {
                                    exporting = false
                                    val uri = saved
                                    if (uri != null) {
                                        pendingRingtone = uri
                                        if (Settings.System.canWrite(context)) {
                                            runCatching { RingtoneManager.setActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE, uri) }
                                                .onSuccess { Toast.makeText(context, if (language == "ar") "تم حفظ النغمة وتعيينها كنغمة رنين" else "Ringtone saved and set as default", Toast.LENGTH_LONG).show() }
                                                .onFailure { Toast.makeText(context, if (language == "ar") "تم حفظ النغمة في مجلد Ringtones" else "Ringtone saved in Ringtones", Toast.LENGTH_LONG).show() }
                                            pendingRingtone = null
                                            onDismiss()
                                        } else {
                                            Toast.makeText(context, if (language == "ar") "تم حفظ النغمة. اسمح لـ Viora بتعديل إعدادات النظام لتعيينها تلقائيًا." else "Saved. Allow Viora to modify system settings to set it as default.", Toast.LENGTH_LONG).show()
                                            settingsLauncher.launch(android.content.Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}")))
                                        }
                                    } else {
                                        Toast.makeText(context, if (language == "ar") "تعذر حفظ النغمة. قد لا يكون تنسيق الملف مدعومًا." else "Could not save ringtone. This audio format may not be supported.", Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        }
                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                            outputFile.delete()
                            scope.launch {
                                exporting = false
                                Toast.makeText(context, if (language == "ar") "فشل قص الأغنية: ${exportException.message.orEmpty()}" else "Ringtone export failed: ${exportException.message.orEmpty()}", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                    runCatching { Transformer.Builder(context).addListener(listener).build().start(clipped, outputFile.absolutePath) }
                        .onFailure {
                            outputFile.delete()
                            exporting = false
                            Toast.makeText(context, if (language == "ar") "تعذر بدء عملية القص" else "Could not start trimming", Toast.LENGTH_LONG).show()
                        }
                }
            ) { Text(if (exporting) (if (language == "ar") "جارٍ الحفظ..." else "Saving…") else (if (language == "ar") "حفظ كنغمة" else "Save ringtone")) }
        },
        dismissButton = {
            Row {
                TextButton(enabled = !exporting, onClick = {
                    if (previewing) stopPreview() else {
                        returnPosition = controller?.currentPosition ?: 0L
                        wasPlaying = controller?.isPlaying == true
                        controller?.seekTo(safeStart)
                        controller?.play()
                        previewing = true
                    }
                }) { Text(if (previewing) (if (language == "ar") "إيقاف المعاينة" else "Stop preview") else (if (language == "ar") "معاينة" else "Preview")) }
                TextButton(enabled = !exporting, onClick = { stopPreview(); onDismiss() }) { Text(if (language == "ar") "إغلاق" else "Close") }
            }
        }
    )
}
