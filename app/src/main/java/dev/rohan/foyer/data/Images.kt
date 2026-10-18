package dev.rohan.foyer.data

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.drawable.Drawable
import androidx.annotation.DrawableRes
import java.io.File
import kotlin.math.max

/**
 * Every bitmap decode in Foyer goes through here, so every bitmap is decoded at the size it's drawn.
 * On the TV, display bitmaps are HARDWARE: pixels live in graphics memory only, no Java-heap copy.
 * Robolectric can't make HARDWARE bitmaps, so tests flip [hardware] off.
 */
object Images {
    @Volatile var hardware: Boolean = true

    const val WALL_W = 1920
    const val WALL_H = 1080
    /** A focused tile is 152 x 85.5 dp at scale 1.13, on a 2 px/dp screen. */
    const val ART_W = 344
    const val ART_H = 194
    const val THUMB_W = 256
    const val THUMB_H = 144

    fun resource(res: Resources, @DrawableRes id: Int, w: Int, h: Int): Bitmap? = runCatching {
        if (hardware) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(res, id)) { d, _, _ ->
                d.setTargetSize(w, h)
                d.allocator = ImageDecoder.ALLOCATOR_HARDWARE
            }
        } else {
            val o = BitmapFactory.Options().apply { inSampleSize = sampleSize(res, id, w, h) }
            BitmapFactory.decodeResource(res, id, o)?.let { scaleTo(it, w, h) }
        }
    }.getOrNull()

    fun file(f: File, w: Int, h: Int): Bitmap? = runCatching {
        if (hardware) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(f)) { d, _, _ ->
                d.setTargetSize(w, h)
                d.allocator = ImageDecoder.ALLOCATOR_HARDWARE
            }
        } else {
            BitmapFactory.decodeFile(f.path)?.let { scaleTo(it, w, h) }
        }
    }.getOrNull()

    /**
     * A wallpaper for the screen: decoded once in software at 1920x1080, the mockup's three scrims
     * painted in, then moved to graphics memory. On screen that's one full-screen pass instead of
     * four, which is what this GPU can afford. Transient cost: one 8 MB software bitmap.
     */
    fun wallResource(res: Resources, @DrawableRes id: Int): Bitmap? = bakeWall {
        if (hardware) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(res, id)) { d, _, _ ->
                d.setTargetSize(WALL_W, WALL_H)
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                d.isMutableRequired = true
            }
        } else {
            BitmapFactory.decodeResource(res, id, BitmapFactory.Options().apply { inScaled = false; inMutable = true })
                ?.let { scaleTo(it, WALL_W, WALL_H) }
        }
    }

    fun wallFile(f: File): Bitmap? = bakeWall {
        if (hardware) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(f)) { d, _, _ ->
                d.setTargetSize(WALL_W, WALL_H)
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                d.isMutableRequired = true
            }
        } else {
            BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inMutable = true })?.let { scaleTo(it, WALL_W, WALL_H) }
        }
    }

    private inline fun bakeWall(decode: () -> Bitmap?): Bitmap? = runCatching {
        val src = decode() ?: return null
        val b = if (src.isMutable && src.config == Bitmap.Config.ARGB_8888) src
        else src.copy(Bitmap.Config.ARGB_8888, true).also { if (it !== src) src.recycle() }
        bakeScrims(b)
        toDisplay(b)
    }.getOrNull()

    /** The mockup's scrims in its own px (1920-wide canvas): top-left radial, top fade, bottom fade. */
    fun bakeScrims(b: Bitmap) {
        val c = android.graphics.Canvas(b)
        val w = b.width.toFloat()
        val h = b.height.toFloat()
        val k = w / 1920f
        fun shade(a: Float) = (Math.round(a * 255) shl 24) or 0x05070B
        val p = android.graphics.Paint(android.graphics.Paint.DITHER_FLAG)
        val clamp = android.graphics.Shader.TileMode.CLAMP
        val rx = 900f * k
        val ry = 520f * k
        p.shader = android.graphics.RadialGradient(0f, 0f, rx, intArrayOf(shade(0.62f), shade(0f)), floatArrayOf(0f, 0.7f), clamp)
        c.save(); c.scale(1f, ry / rx); c.drawRect(0f, 0f, rx * 0.7f, rx * 0.7f, p); c.restore()
        p.shader = android.graphics.LinearGradient(0f, 0f, 0f, h * 0.24f, shade(0.35f), shade(0f), clamp)
        c.drawRect(0f, 0f, w, h * 0.24f, p)
        p.shader = android.graphics.LinearGradient(
            0f, h * 0.44f, 0f, h, intArrayOf(shade(0f), shade(0.42f), shade(0.82f)), floatArrayOf(0f, 0.26f / 0.56f, 1f), clamp,
        )
        c.drawRect(0f, h * 0.44f, w, h, p)
    }

    /** A small software decode (1/8 size) for the frosted-glass copy and thumbnails. */
    fun smallResource(res: Resources, @DrawableRes id: Int): Bitmap? = runCatching {
        BitmapFactory.decodeResource(res, id, BitmapFactory.Options().apply { inSampleSize = 8; inScaled = false })
    }.getOrNull()

    fun smallFile(f: File): Bitmap? = runCatching {
        BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = 8 })
    }.getOrNull()

    /** Draws an app banner (or any drawable) to fill a tile, centre-cropped. */
    fun fromDrawable(d: Drawable, w: Int, h: Int): Bitmap {
        val soft = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(soft)
        val iw = d.intrinsicWidth.takeIf { it > 0 } ?: w
        val ih = d.intrinsicHeight.takeIf { it > 0 } ?: h
        val s = max(w / iw.toFloat(), h / ih.toFloat())
        val dw = (iw * s).toInt()
        val dh = (ih * s).toInt()
        d.setBounds((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2)
        d.draw(c)
        return toDisplay(soft)
    }

    /** Moves a software bitmap into graphics memory on the TV. */
    fun toDisplay(soft: Bitmap): Bitmap {
        if (!hardware) return soft
        val hw = soft.copy(Bitmap.Config.HARDWARE, false) ?: return soft
        soft.recycle()
        return hw
    }

    private fun scaleTo(b: Bitmap, w: Int, h: Int): Bitmap {
        if (b.width == w && b.height == h) return b
        val out = Bitmap.createScaledBitmap(b, w, h, true)
        if (out !== b) b.recycle()
        return out
    }

    private fun sampleSize(res: Resources, id: Int, w: Int, h: Int): Int {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true; inScaled = false }
        BitmapFactory.decodeResource(res, id, o)
        var s = 1
        while (o.outWidth / (s * 2) >= w && o.outHeight / (s * 2) >= h) s *= 2
        return s
    }

    /** Centre-crop to 16:9 and scale to 1920x1080 (for Rohan's own photos, cached once). */
    fun cropToWall(src: Bitmap): Bitmap {
        val target = WALL_W / WALL_H.toFloat()
        val (cw, ch) = if (src.width / src.height.toFloat() > target) {
            (src.height * target).toInt() to src.height
        } else {
            src.width to (src.width / target).toInt()
        }
        val cropped = Bitmap.createBitmap(src, (src.width - cw) / 2, (src.height - ch) / 2, cw, ch)
        val out = Bitmap.createScaledBitmap(cropped, WALL_W, WALL_H, true)
        if (cropped !== src && cropped !== out) cropped.recycle()
        return out
    }

    /**
     * Decodes an arbitrary user photo (JPEG, PNG, WebP, HEIC) at roughly wall size without loading
     * it full-res. ImageDecoder honours EXIF rotation; BitmapFactory is the fallback.
     */
    fun decodeUserPhoto(f: File): Bitmap? = runCatching {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(f)) { d, info, _ ->
            var s = 1
            while (info.size.width / (s * 2) >= WALL_W && info.size.height / (s * 2) >= WALL_H) s *= 2
            d.setTargetSampleSize(s)
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }.getOrNull() ?: decodeUserPhotoCompat(f)

    private fun decodeUserPhotoCompat(f: File): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var s = 1
        while (bounds.outWidth / (s * 2) >= WALL_W && bounds.outHeight / (s * 2) >= WALL_H) s *= 2
        BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })
    }.getOrNull()
}
