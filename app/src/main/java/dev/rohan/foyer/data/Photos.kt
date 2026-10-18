package dev.rohan.foyer.data

import android.content.Context
import android.graphics.Bitmap
import androidx.annotation.DrawableRes
import dev.rohan.foyer.R
import java.io.File
import java.security.MessageDigest

/** A wallpaper: the built-in backdrop, or one of your photos (cached at 1920x1080). */
sealed interface Photo {
    val id: String
    val caption: String?

    data class Builtin(override val id: String, @DrawableRes val res: Int, override val caption: String?) : Photo
    data class Own(override val id: String, val cached: File, override val caption: String?) : Photo
}

/**
 * Your photos, from /sdcard/Pictures/Foyer/, pushed there over adb (README "photos"). Fire OS 8 won't
 * let adb write into the app's private folder, and Pictures is readable with READ_EXTERNAL_STORAGE
 * (granted once over adb). The caption is the file name ("Pangong Tso, Ladakh.jpg"), unless it looks
 * like a camera name. With an empty folder, Foyer shows one built-in backdrop so it's never blank.
 */
class PhotoRepository(private val context: Context) {

    /** Dusk over hills, generated for Foyer (so it ships under the repo's licence). */
    val builtins: List<Photo.Builtin> = listOf(Photo.Builtin("b:dusk", R.drawable.wall_default, null))

    @Suppress("DEPRECATION")
    val folder: File? get() = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES), "Foyer")
    private val cacheDir: File get() = File(context.cacheDir, "walls")

    /** Blocking: scans the folder, caching any new photo. Corrupt files are skipped silently. */
    fun scanOwn(): List<Photo.Own> {
        val dir = folder ?: return emptyList()
        if (!dir.isDirectory) return emptyList()
        val files = dir.listFiles().orEmpty()
            .filter { it.isFile && it.extension.lowercase() in IMAGE_EXT }
            .sortedBy { it.name.lowercase() }
        cacheDir.mkdirs()
        val keep = HashSet<String>()
        val out = ArrayList<Photo.Own>()
        for (f in files) {
            val key = hash("${f.name}|${f.length()}|${f.lastModified()}")
            val cached = File(cacheDir, "$key.jpg")
            if (!cached.exists() && !cache(f, cached)) continue
            keep += cached.name
            // A <name>.txt sidecar wins when Foyer can see it; otherwise the file name.
            val caption = File(dir, f.nameWithoutExtension + ".txt").takeIf { it.isFile }
                ?.let { runCatching { it.useLines { l -> l.firstOrNull() } }.getOrNull() }
                ?.trim()?.takeIf { it.isNotEmpty() }
                ?: captionFromName(f.nameWithoutExtension)
            out += Photo.Own("u:${f.name}", cached, caption)
        }
        cacheDir.listFiles().orEmpty().filter { it.name !in keep }.forEach { it.delete() }
        return out
    }

    private fun cache(src: File, dst: File): Boolean {
        val decoded = Images.decodeUserPhoto(src) ?: return false
        val wall = Images.cropToWall(decoded)
        if (wall !== decoded) decoded.recycle()
        val tmp = File(dst.path + ".tmp")
        val ok = runCatching { tmp.outputStream().use { wall.compress(Bitmap.CompressFormat.JPEG, 90, it) } }.getOrDefault(false)
        wall.recycle()
        return ok && tmp.renameTo(dst)
    }

    fun full(p: Photo): Bitmap? = when (p) {
        is Photo.Builtin -> Images.wallResource(context.resources, p.res)
        is Photo.Own -> Images.wallFile(p.cached)
    }

    /** Small software decode (240x135), source for the frosted copy and thumbnails. */
    fun small(p: Photo): Bitmap? = when (p) {
        is Photo.Builtin -> Images.smallResource(context.resources, p.res)
        is Photo.Own -> Images.smallFile(p.cached)
    }

    private fun hash(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).take(8).joinToString("") { "%02x".format(it) }

    companion object {
        private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "heic", "heif")
        private val CAMERA = Regex("^(img|pxl|dsc|dscn|dscf|dji|gopr|mvimg|vid|screenshot|screen shot|photo|image|pic|wp|whatsapp image|signal|sam|p)[ \\-]?\\d")
        private val HASHY = Regex("^[0-9a-f\\- ]{12,}$")

        /** "Pangong Tso, Ladakh" -> itself; "IMG_2041", "PXL_20260412_0931", "1699999999" -> none. */
        fun captionFromName(base: String): String? {
            val s = base.replace('_', ' ').replace(Regex("\\s+"), " ").trim()
            val lower = s.lowercase()
            return when {
                s.count { it.isLetter() } < 3 -> null
                CAMERA.containsMatchIn(lower) -> null
                HASHY.matches(lower) -> null
                else -> s
            }
        }
    }
}
