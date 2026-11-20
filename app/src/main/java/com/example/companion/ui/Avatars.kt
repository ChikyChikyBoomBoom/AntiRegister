package com.example.companion.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import com.example.companion.contacts.AndroidContactLookup
import com.example.companion.contacts.ContactsState
import com.example.companion.data.ReadDiagnostics
import java.util.concurrent.Executors

/**
 * Round contact pictures. A contact's photo is read through ContentResolver from the thumbnail URI the Contacts provider
 * gave for that one number, cropped to a circle and kept in a small in-memory LRU (about 64 bitmaps, lost with the process).
 * Nothing is saved to disk. A contact without a photo gets its initial on a coloured circle; an unknown number gets a plain grey circle.
 *
 *  - [bind]: for list rows and the call screen. Draws what is known now and loads the photo on a background thread.
 *  - [notificationPhoto]: synchronous, for the large icon of a notification (a small thumbnail read).
 */
class Avatars(private val context: Context, private val lookup: AndroidContactLookup) {

    private val cache = LruCache<String, Bitmap>(64)
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "contacts-avatar").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())

    /** [sizePx] is the picture's width and height. Safe to call repeatedly; uses only what [lookup] already knows. */
    fun bind(view: ImageView, number: String?, sizePx: Int) {
        if (sizePx <= 0) return                                  // Stage 28: a not-yet-measured size would make bitmap creation throw
        val key = number?.trim().orEmpty()
        view.tag = key                                           // a late photo is applied only if the view still shows this number
        val match = lookup.findCached(number)
        val photoUri = match?.photoUri
        val ready = photoUri?.let { cache.get(photoKey(it, sizePx)) }
        view.setImageBitmap(ready ?: placeholder(match?.name, sizePx))
        if (ready == null && photoUri != null) {
            try {
                executor.execute {
                    // Stage 28: nothing thrown while loading a photo may reach the thread's uncaught-exception handler.
                    try {
                        val bmp = photo(photoUri, sizePx) ?: return@execute
                        main.post { if (view.tag == key) view.setImageBitmap(bmp) }
                    } catch (e: Exception) {
                        ReadDiagnostics.record(ReadDiagnostics.Area.CONTACT_PHOTO, "load contact photo", e)
                    }
                }
            } catch (e: Exception) {
                ReadDiagnostics.record(ReadDiagnostics.Area.CONTACT_PHOTO, "start photo load", e)     // the initial stays
            }
        }
    }

    /** The contact's photo as a circle, or null when the number is not a contact or the contact has no photo. */
    fun notificationPhoto(number: String?): Bitmap? {
        val uri = lookup.find(number)?.photoUri ?: return null
        val size = context.resources.getDimensionPixelSize(android.R.dimen.notification_large_icon_width)
        return photo(uri, size)
    }

    // ---------------------------------------------------------------- drawing

    private fun photo(uri: String, size: Int): Bitmap? {
        val key = photoKey(uri, size)
        cache.get(key)?.let { return it }
        val source: Bitmap = (try {
            context.contentResolver.openInputStream(Uri.parse(uri))?.use { BitmapFactory.decodeStream(it) }
        } catch (e: Exception) {
            null            // photo removed, provider gone, no access: the initial is shown instead
        }) ?: return null
        val circle = try {
            circle(source, size)
        } catch (e: Exception) {
            ReadDiagnostics.record(ReadDiagnostics.Area.CONTACT_PHOTO, "draw contact photo", e)      // zero-size or unusable bitmap: the initial is shown
            return null
        }
        cache.put(key, circle)
        return circle
    }

    private fun placeholder(name: String?, size: Int): Bitmap {
        val letter = if (name == null) "" else ContactsState.initial(name)
        val color = if (name == null) Color.rgb(189, 189, 189) else colorFor(name)
        val key = "p:$letter:$color@$size"
        cache.get(key)?.let { return it }
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
        if (letter.isNotEmpty()) {
            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.WHITE; textSize = size * 0.46f; textAlign = Paint.Align.CENTER }
            canvas.drawText(letter, size / 2f, size / 2f - (text.descent() + text.ascent()) / 2f, text)
        }
        cache.put(key, out)
        return out
    }

    private fun circle(src: Bitmap, size: Int): Bitmap {
        val side = minOf(src.width, src.height)
        val square = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
        val scaled = Bitmap.createScaledBitmap(square, size, size, true)
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = BitmapShader(scaled, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        Canvas(out).drawCircle(size / 2f, size / 2f, size / 2f, paint)
        return out
    }

    private fun photoKey(uri: String, size: Int) = "u:$uri@$size"

    /** Same name, same colour, every time (a fixed palette indexed by the name's hash). */
    private fun colorFor(name: String): Int = PALETTE[(name.hashCode() and Int.MAX_VALUE) % PALETTE.size]

    private companion object {
        val PALETTE = intArrayOf(
            Color.rgb(25, 118, 210), Color.rgb(56, 142, 60), Color.rgb(245, 124, 0),
            Color.rgb(123, 31, 162), Color.rgb(0, 121, 107), Color.rgb(194, 24, 91)
        )
    }
}
