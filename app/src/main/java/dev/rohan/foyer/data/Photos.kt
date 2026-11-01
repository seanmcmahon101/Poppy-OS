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

    /** Sean's cat (DSC02102, cropped 16:9 from the top, 1920x1080). Always there, no permission needed. */
    val builtins: List<Photo.Builtin> = listOf(Photo.Builtin("b:home", R.drawable.wall_default, null))

    val folder: File? get() = PhotoRepository.folder()
    private val cacheDir: File get() = File(context.cacheDir, "walls")

    private var lastListing: String? = null
    private var lastScan: List<Photo.Own> = emptyList()

    /**
     * Blocking: scans the folder, caching any new photo. Corrupt files are skipped silently.
     *
     * Runs every minute, so when nothing in the folder has changed (names, sizes and times of every
     * entry, .txt captions included) and the cached copies are still there, it answers from memory.
     * One scan at a time: two at once could both write the same cache file.
     */
    @Synchronized
    fun scanOwn(): List<Photo.Own> {
        val dir = folder ?: return emptyList()
        if (!dir.isDirectory) return emptyList()
        val entries = dir.listFiles().orEmpty().sortedBy { it.name }
        val listing = entries.joinToString("\n") { "${it.name}|${it.length()}|${it.lastModified()}" }
        if (listing == lastListing && lastScan.all { it.cached.isFile }) return lastScan

        val names = entries.mapTo(HashSet()) { it.name }
        val files = entries
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
            val caption = "${f.nameWithoutExtension}.txt".takeIf { it in names }
                ?.let { runCatching { File(dir, it).useLines { l -> l.firstOrNull() } }.getOrNull() }
                ?.trim()?.takeIf { it.isNotEmpty() }
                ?: captionFromName(f.nameWithoutExtension)
            out += Photo.Own("u:${f.name}", cached, caption)
        }
        cacheDir.listFiles().orEmpty().filter { it.name !in keep }.forEach { it.delete() }
        lastListing = listing
        lastScan = out
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
        /** Where photos are pushed (`homectl wall`). */
        @Suppress("DEPRECATION")
        fun folder(): File = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES), "Foyer")

        /** Where tile art is pushed (`homectl art`): <package>.jpg. Not a photo: the scan skips folders. */
        fun artFolder(): File = File(folder(), "art")

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
