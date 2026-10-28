package com.avip.solarcustomer

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Base64
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/** A photo ready to send: small JPEG as base64, plus a thumbnail to show. */
class Picked(val base64: String, val thumb: Bitmap)

/**
 * Camera or gallery photo, shrunk to at most 1600 px and turned upright.
 * Create it in onCreate (before the screen starts), then call [pick].
 */
class PhotoPicker(private val activity: AppCompatActivity, private val onPicked: (Picked) -> Unit) {
    private var cameraFile: File? = null

    private val camera = activity.registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = cameraFile
        if (ok && f != null && f.length() > 0) process(Uri.fromFile(f))
    }

    private val gallery = activity.registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) process(uri)
    }

    fun pick(title: String = "Photo लें") {
        AlertDialog.Builder(activity).setTitle(title)
            .setItems(arrayOf("📷  Camera से photo लें", "🖼  Gallery से चुनें")) { _, which ->
                if (which == 0) openCamera() else try { gallery.launch("image/*") } catch (_: Exception) { activity.toast("Gallery नहीं खुली") }
            }
            .show()
    }

    private fun openCamera() {
        try {
            val dir = File(activity.cacheDir, "camera").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val f = File(dir, "photo_${System.currentTimeMillis()}.jpg")
            cameraFile = f
            camera.launch(FileProvider.getUriForFile(activity, activity.packageName + ".files", f))
        } catch (_: Exception) {
            activity.toast("Camera नहीं खुला")
        }
    }

    private fun process(uri: Uri) {
        activity.lifecycleScope.launch {
            val p = withContext(Dispatchers.IO) { runCatching { Images.load(activity, uri) }.getOrNull() }
            if (p == null) activity.toast("Photo नहीं पढ़ी जा सकी") else onPicked(p)
        }
    }
}

object Images {
    private const val MAX = 1600

    fun load(c: Context, uri: Uri): Picked? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= MAX || bounds.outHeight / (sample * 2) >= MAX) sample *= 2
        var bmp = c.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val rotation = try {
            c.contentResolver.openInputStream(uri)?.use {
                when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        } catch (_: Exception) { 0f }
        val scale = minOf(1f, MAX.toFloat() / maxOf(bmp.width, bmp.height))
        if (rotation != 0f || scale < 1f) {
            val m = Matrix().apply { postScale(scale, scale); postRotate(rotation) }
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        }
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
        val tw = 240
        val thumb = Bitmap.createScaledBitmap(bmp, tw, maxOf(1, bmp.height * tw / bmp.width), true)
        return Picked(Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP), thumb)
    }
}

/** Opens a document sent by the server (PDF or photo) in the phone's viewer. */
fun Activity.openDocument(name: String, mime: String, base64: String) {
    try {
        val dir = File(cacheDir, "shared").apply { mkdirs() }
        val safe = name.replace(Regex("[^\\w.\\- ]"), "_").ifBlank { "document" }
        val f = File(dir, safe)
        f.writeBytes(Base64.decode(base64, Base64.DEFAULT))
        val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
        val type = mime.ifBlank { if (safe.endsWith(".pdf", true)) "application/pdf" else "image/*" }
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(view)
        } catch (_: Exception) {
            // no viewer: offer to share (WhatsApp, Drive, Files…)
            val send = Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(send, safe))
        }
    } catch (_: Exception) {
        toast("Document नहीं खुल सका")
    }
}
