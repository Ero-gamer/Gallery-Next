package org.fossify.gallery.extensions

import android.annotation.SuppressLint
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Bitmap.CompressFormat
import android.media.AudioManager
import android.net.Uri
import android.os.Process
import android.provider.MediaStore.Files
import android.provider.MediaStore.Images
import android.widget.ImageView
import com.github.panpf.sketch.cache.CachePolicy
import com.github.panpf.sketch.loadImage
import com.github.panpf.sketch.request.ImageRequest
import com.github.panpf.sketch.request.ImageResult
import com.github.panpf.sketch.request.Listener
import com.github.panpf.sketch.resize.Precision
import com.github.panpf.sketch.resize.Scale
import com.github.panpf.sketch.sketch
import com.github.panpf.sketch.transform.RoundedCornersTransformation
import org.fossify.commons.extensions.doesThisOrParentHaveNoMedia
import org.fossify.commons.extensions.getDocumentFile
import org.fossify.commons.extensions.getDoesFilePathExist
import org.fossify.commons.extensions.getDuration
import org.fossify.commons.extensions.getFilenameFromPath
import org.fossify.commons.extensions.getLongValue
import org.fossify.commons.extensions.getMimeTypeFromUri
import org.fossify.commons.extensions.getOTGPublicPath
import org.fossify.commons.extensions.getParentPath
import org.fossify.commons.extensions.getStringValue
import org.fossify.commons.extensions.humanizePath
import org.fossify.commons.extensions.internalStoragePath
import org.fossify.commons.extensions.isGif
import org.fossify.commons.extensions.isPathOnOTG
import org.fossify.commons.extensions.isPathOnSD
import org.fossify.commons.extensions.isPng
import org.fossify.commons.extensions.isPortrait
import org.fossify.commons.extensions.isRawFast
import org.fossify.commons.extensions.isSvg
import org.fossify.commons.extensions.isVideoFast
import org.fossify.commons.extensions.isWebP
import org.fossify.commons.extensions.normalizeString
import org.fossify.commons.extensions.otgPath
import org.fossify.commons.extensions.recycleBinPath
import org.fossify.commons.extensions.sdCardPath
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.AlphanumericComparator
import org.fossify.commons.helpers.FAVORITES
import org.fossify.commons.helpers.NOMEDIA
import org.fossify.commons.helpers.SORT_BY_COUNT
import org.fossify.commons.helpers.SORT_BY_CUSTOM
import org.fossify.commons.helpers.SORT_BY_DATE_MODIFIED
import org.fossify.commons.helpers.SORT_BY_DATE_TAKEN
import org.fossify.commons.helpers.SORT_BY_NAME
import org.fossify.commons.helpers.SORT_BY_PATH
import org.fossify.commons.helpers.SORT_BY_RANDOM
import org.fossify.commons.helpers.SORT_BY_SIZE
import org.fossify.commons.helpers.SORT_DESCENDING
import org.fossify.commons.helpers.SORT_USE_NUMERIC_VALUE
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.helpers.sumByLong
import org.fossify.commons.views.MySquareImageView
import org.fossify.gallery.R
import org.fossify.gallery.asynctasks.GetMediaAsynctask
import org.fossify.gallery.databases.GalleryDatabase
import org.fossify.gallery.helpers.Config
import org.fossify.gallery.helpers.GROUP_BY_DATE_TAKEN_DAILY
import org.fossify.gallery.helpers.GROUP_BY_DATE_TAKEN_MONTHLY
import org.fossify.gallery.helpers.GROUP_BY_LAST_MODIFIED_DAILY
import org.fossify.gallery.helpers.GROUP_BY_LAST_MODIFIED_MONTHLY
import org.fossify.gallery.helpers.IsoTypeReader
import org.fossify.gallery.helpers.LOCATION_INTERNAL
import org.fossify.gallery.helpers.LOCATION_OTG
import org.fossify.gallery.helpers.LOCATION_SD
import org.fossify.gallery.helpers.MediaFetcher
import org.fossify.gallery.helpers.MyWidgetProvider
import org.fossify.gallery.helpers.RECYCLE_BIN
import org.fossify.gallery.helpers.ROUNDED_CORNERS_NONE
import org.fossify.gallery.helpers.ROUNDED_CORNERS_SMALL
import org.fossify.gallery.helpers.SHOW_ALL
import org.fossify.gallery.helpers.THUMBNAIL_FADE_DURATION_MS
import org.fossify.gallery.helpers.TYPE_GIFS
import org.fossify.gallery.helpers.TYPE_IMAGES
import org.fossify.gallery.helpers.TYPE_PORTRAITS
import org.fossify.gallery.helpers.TYPE_RAWS
import org.fossify.gallery.helpers.TYPE_SVGS
import org.fossify.gallery.helpers.TYPE_VIDEOS
import org.fossify.gallery.interfaces.DateTakensDao
import org.fossify.gallery.interfaces.DirectoryDao
import org.fossify.gallery.interfaces.FavoritesDao
import org.fossify.gallery.interfaces.MediumDao
import org.fossify.gallery.interfaces.WidgetsDao
import org.fossify.gallery.models.AlbumCover
import org.fossify.gallery.models.Directory
import org.fossify.gallery.models.Favorite
import org.fossify.gallery.models.Medium
import org.fossify.gallery.models.ThumbnailItem
import org.fossify.gallery.svg.SvgSoftwareLayerSetter
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

val Context.config: Config get() = Config.newInstance(applicationContext)

val Context.galleryDB: GalleryDatabase get() = GalleryDatabase.getInstance(applicationContext)

val Context.directoryDB: DirectoryDao get() = galleryDB.DirectoryDao()

val Context.mediumDB: MediumDao get() = galleryDB.MediumDao()

val Context.favoritesDB: FavoritesDao get() = galleryDB.FavoritesDao()

val Context.widgetsDB: WidgetsDao get() = galleryDB.WidgetsDao()

val Context.dateTakensDB: DateTakensDao get() = galleryDB.DateTakensDao()

fun Context.getHumanizedFilename(path: String): String {
    val humanized = humanizePath(path)
    return humanized.substring(humanized.lastIndexOf("/") + 1)
}

fun Context.movePinnedDirectoriesToFront(dirs: ArrayList<Directory>): ArrayList<Directory> {
    val foundFolders = ArrayList<Directory>()
    val pinnedFolders = config.pinnedFolders

    dirs.forEach {
        if (pinnedFolders.contains(it.path)) {
            foundFolders.add(it)
        }
    }

    dirs.removeAll(foundFolders)
    dirs.addAll(0, foundFolders)
    if (config.tempSkipDeleteConfirmation) {
        config.tempSkipDeleteConfirmation = false
    }

    return dirs
}

@SuppressLint("UseCompatLoadingForDrawables")
fun Context.getSortedDirectories(source: ArrayList<Directory>): ArrayList<Directory> {
    val sorting = config.directorySorting
    val dirs = source.clone() as ArrayList<Directory>

    if (sorting and SORT_BY_RANDOM != 0) {
        dirs.shuffle()
        return movePinnedDirectoriesToFront(dirs)
    }

    dirs.sortWith(Comparator { o1, o2 ->
        o1 as Directory
        o2 as Directory
        var result = when {
            sorting and SORT_BY_NAME != 0 -> {
                if (sorting and SORT_USE_NUMERIC_VALUE != 0) {
                    AlphanumericComparator().compare(o1.name.lowercase(), o2.name.lowercase())
                } else {
                    o1.name.lowercase().compareTo(o2.name.lowercase())
                }
            }

            sorting and SORT_BY_PATH != 0 -> {
                if (sorting and SORT_USE_NUMERIC_VALUE != 0) {
                    AlphanumericComparator().compare(o1.path.lowercase(), o2.path.lowercase())
                } else {
                    o1.path.lowercase().compareTo(o2.path.lowercase())
                }
            }

            sorting and SORT_BY_SIZE != 0 -> o1.size.compareTo(o2.size)
            sorting and SORT_BY_DATE_MODIFIED != 0 -> o1.modified.compareTo(o2.modified)
            sorting and SORT_BY_DATE_TAKEN != 0 -> o1.taken.compareTo(o2.taken)
            else -> o1.mediaCnt.compareTo(o2.mediaCnt)
        }

        if (sorting and SORT_DESCENDING != 0) {
            result *= -1
        }
        result
    })

    return movePinnedDirectoriesToFront(dirs)
}

fun Context.getDirsToShow(
    dirs: ArrayList<Directory>,
    allDirs: ArrayList<Directory>,
    currentPathPrefix: String,
): ArrayList<Directory> {
    return if (config.groupDirectSubfolders) {
        dirs.forEach { it.subfoldersCount = 0 }
        val dirFolders = dirs.map { it.path }.toHashSet()
        val foldersWithoutParent = ArrayList<Directory>()
        val rootDirs = ArrayList<Directory>()

        dirs.forEach { dir ->
            val parent = dir.path.getParentPath()
            if (dirFolders.contains(parent)) {
                val parentDir = dirs.find { it.path == parent }
                parentDir?.subfoldersCount = (parentDir?.subfoldersCount ?: 0) + 1
                parentDir?.subfoldersMediaCount = (parentDir?.subfoldersMediaCount ?: 0) + dir.mediaCnt
            } else {
                foldersWithoutParent.add(dir)
            }
        }

        foldersWithoutParent.forEach { dir ->
            if (currentPathPrefix.isEmpty()) {
                rootDirs.add(dir)
            } else if (dir.path.startsWith(currentPathPrefix)) {
                val folder = allDirs.find { it.path == dir.path.getParentPath() }
                if (folder != null) {
                    rootDirs.add(dir)
                }
            }
        }

        if (!config.showRecycleBinLast) {
            rootDirs
        } else {
            val recycleBinDir = rootDirs.find { it.isRecycleBin() }
            if (recycleBinDir != null) {
                rootDirs.remove(recycleBinDir)
                rootDirs.add(recycleBinDir)
            }
            rootDirs
        }
    } else {
        if (!config.showRecycleBinLast) {
            dirs
        } else {
            val recycleBinDir = dirs.find { it.isRecycleBin() }
            if (recycleBinDir != null) {
                dirs.remove(recycleBinDir)
                dirs.add(recycleBinDir)
            }
            dirs
        }
    }
}

fun Context.fillWithSharedDirectParents(dirs: ArrayList<Directory>): ArrayList<Directory> {
    val allDirs = dirs.clone() as ArrayList<Directory>
    val childDirs = ArrayList<Directory>()

    dirs.forEach { currentDir ->
        val parentPath = currentDir.path.getParentPath()
        if (dirs.find { it.path == parentPath } == null && !allDirs.any { it.path == parentPath }) {
            val parentDir = allDirs.find {
                it.path.startsWith(parentPath) && it.path != parentPath && it.parentPath != parentPath
            }

            if (parentDir != null) {
                val newParentDir = parentDir.copy(
                    path = parentPath,
                    tmb = "",
                    name = parentPath.getFilenameFromPath(),
                    mediaCnt = 0,
                    sortValue = getDirectorySortingValue(parentPath),
                    containsMediaFilesDirectly = false
                )
                allDirs.add(newParentDir)
                childDirs.add(currentDir)
            }
        }
    }

    return allDirs
}

fun Context.getDirectParentSubfolders(path: String, allDirs: ArrayList<Directory>): ArrayList<Directory> {
    val folders = ArrayList<Directory>()
    allDirs.forEach { dir ->
        if (dir.path != path && dir.path.startsWith(path)) {
            val relativePath = dir.path.removePrefix(path)
            if (relativePath.substring(1).contains('/')) {
                val longestValidPath = "$path/${relativePath.substring(1).substringBefore('/')}"
                if (allDirs.find { it.path == longestValidPath } == null) {
                    folders.add(dir)
                }
            } else {
                folders.add(dir)
            }
        }
    }

    return folders
}

fun Context.updateSubfolderCounts(children: ArrayList<Directory>, root: ArrayList<Directory>) {
    for (child in children) {
        var longestSharedPath = ""
        for (rootDir in root) {
            if (child.path.startsWith(rootDir.path) && rootDir.path.length > longestSharedPath.length) {
                longestSharedPath = rootDir.path
            }
        }
        root.find { it.path == longestSharedPath }?.apply {
            subfoldersCount++
            subfoldersMediaCount += child.mediaCnt
        }
    }
}

fun Context.getNoMediaFolders(callback: (folders: ArrayList<String>) -> Unit) {
    ensureBackgroundThread {
        callback(getNoMediaFoldersSync())
    }
}

fun Context.getNoMediaFoldersSync(): ArrayList<String> {
    val folders = ArrayList<String>()
    val uri = Files.getContentUri("external")
    val projection = arrayOf(Images.Media.DATA)
    val selection = "${Images.Media.DATA} LIKE ? AND ${Images.Media.DATA} NOT LIKE ?"
    val selectionArgs = arrayOf("%/$NOMEDIA/%", "%/$NOMEDIA/%/%")
    var cursor: Cursor? = null

    try {
        cursor = contentResolver.query(uri, projection, selection, selectionArgs, null)
        if (cursor?.moveToFirst() == true) {
            do {
                val path = cursor.getStringValue(Images.Media.DATA) ?: continue
                val noMediaFile = File(path)
                if (getDoesFilePathExist(noMediaFile.absolutePath)) {
                    folders.add("${noMediaFile.parent}")
                }
            } while (cursor.moveToNext())
        }
    } catch (ignored: Exception) {
    } finally {
        cursor?.close()
    }

    return folders
}

fun Context.rescanFolderMedia(path: String) {
    ensureBackgroundThread {
        rescanFolderMediaSync(path)
    }
}

fun Context.rescanFolderMediaSync(path: String) {
    getCachedMedia(path) {
        val cachedMedia = it
        GetMediaAsynctask(applicationContext, path, false, false, false) {
            ensureBackgroundThread {
                val newMedia = it
                if (newMedia.hashCode() != cachedMedia.hashCode()) {
                    cachedMedia.filter { !newMedia.contains(it) }.filter { it is Medium }.forEach {
                        try {
                            (it as Medium).let { medium ->
                                deleteDBPath(medium.path)
                            }
                        } catch (ignored: Exception) {
                        }
                    }
                }
            }
        }.execute()
    }
}

fun Context.storeDirectoryItems(items: ArrayList<Directory>) {
    ensureBackgroundThread {
        directoryDB.insertAll(items)
    }
}

fun Context.checkAppendingHidden(
    path: String,
    hidden: String,
    includedFolders: MutableSet<String>,
    noMediaFolderPaths: ArrayList<String>,
): String {
    val dirName = when (path) {
        internalStoragePath -> getString(org.fossify.commons.R.string.internal)
        sdCardPath -> getString(org.fossify.commons.R.string.sd_card)
        otgPath -> getString(org.fossify.commons.R.string.otg)
        recycleBinPath -> getString(org.fossify.commons.R.string.recycle_bin)
        else -> {
            if (path == FAVORITES) {
                getString(org.fossify.commons.R.string.favorites)
            } else {
                path.getFilenameFromPath()
            }
        }
    }

    return if ((path.doesThisOrParentHaveNoMedia(HashMap(), null) && !includedFolders.contains(path))
        || noMediaFolderPaths.contains(path)
    ) {
        "$dirName $hidden"
    } else {
        dirName
    }
}

fun Context.getFolderNameFromPath(path: String): String {
    return when (path) {
        internalStoragePath -> getString(org.fossify.commons.R.string.internal)
        sdCardPath -> getString(org.fossify.commons.R.string.sd_card)
        otgPath -> getString(org.fossify.commons.R.string.otg)
        recycleBinPath -> getString(org.fossify.commons.R.string.recycle_bin)
        FAVORITES -> getString(org.fossify.commons.R.string.favorites)
        else -> path.getFilenameFromPath()
    }
}

// ---------------------------------------------------------------------------
// Image loading via Sketch (replaces Glide + Picasso)
// ---------------------------------------------------------------------------

/**
 * Load a thumbnail for the given [path] into [target] using Sketch.
 *
 * SVG files use Sketch's built-in SVG decoder (sketch-svg). All other formats
 * are handled by BitmapFactoryDecoder and the optional animated decoders, so
 * the old Picasso fallback for large PNGs is no longer needed.
 *
 * [signature] is a plain String derived from [Medium.getKey] or [Directory.getKey]
 * and used as a Sketch memory/result cache-key extra so stale entries are evicted
 * when a file changes.
 */
fun Context.loadImage(
    type: Int,
    path: String,
    target: MySquareImageView,
    horizontalScroll: Boolean,
    animateGifs: Boolean,
    cropThumbnails: Boolean,
    roundCorners: Int,
    signature: String,
    skipMemoryCacheAtPaths: ArrayList<String>? = null,
    onError: (() -> Unit)? = null,
) {
    target.isHorizontalScrolling = horizontalScroll
    if (type == TYPE_SVGS) {
        loadSVG(
            path = path,
            target = target,
            cropThumbnails = cropThumbnails,
            roundCorners = roundCorners,
            signature = signature,
        )
    } else {
        loadImageBase(
            path = path,
            target = target,
            cropThumbnails = cropThumbnails,
            roundCorners = roundCorners,
            signature = signature,
            skipMemoryCacheAtPaths = skipMemoryCacheAtPaths,
            animate = animateGifs,
            onError = onError,
        )
    }
}

fun Context.addTempFolderIfNeeded(dirs: ArrayList<Directory>): ArrayList<Directory> {
    val tempFolderPath = config.tempFolderPath
    return if (tempFolderPath.isNotEmpty()) {
        val directories = ArrayList<Directory>()
        val newFolder = Directory(
            id = null,
            path = tempFolderPath,
            tmb = "",
            name = tempFolderPath.getFilenameFromPath(),
            mediaCnt = 0,
            modified = 0,
            taken = 0,
            size = 0L,
            location = getPathLocation(tempFolderPath),
            types = 0,
            sortValue = ""
        )
        directories.add(newFolder)
        directories.addAll(dirs)
        directories
    } else {
        dirs
    }
}

fun Context.getPathLocation(path: String): Int {
    return when {
        isPathOnSD(path) -> LOCATION_SD
        isPathOnOTG(path) -> LOCATION_OTG
        else -> LOCATION_INTERNAL
    }
}

/**
 * Core thumbnail loader backed by Sketch.
 *
 * Key behavioural notes vs the old Glide implementation:
 * - Animated GIF and animated WebP are shown animated when [animate] is true and
 *   [roundCorners] is [ROUNDED_CORNERS_NONE] – Sketch handles both automatically
 *   via sketch-animated-gif and sketch-animated-webp.
 * - Rounded-corner thumbnails are produced with [RoundedCornersTransformation] from
 *   sketch-core (no Glide RoundedCorners needed).
 * - The Picasso fallback for large PNGs is removed; Sketch's BitmapFactoryDecoder
 *   handles these correctly on all supported API levels.
 */
fun Context.loadImageBase(
    path: String,
    target: MySquareImageView,
    cropThumbnails: Boolean,
    roundCorners: Int,
    signature: String,
    skipMemoryCacheAtPaths: ArrayList<String>? = null,
    animate: Boolean = false,
    crossFadeDuration: Int = THUMBNAIL_FADE_DURATION_MS,
    onError: (() -> Unit)? = null,
) {
    val skipMemory = skipMemoryCacheAtPaths?.contains(path) == true

    target.loadImage(path) {
        // Cache-key extras so that rotated/edited files bust the cache properly.
        memoryCacheKeyExtras(mapOf("sig" to signature))
        resultCacheKeyExtras(mapOf("sig" to signature))

        if (skipMemory) {
            memoryCachePolicy(CachePolicy.DISABLED)
        }

        // Scale / crop mode
        if (cropThumbnails) {
            scale(Scale.CENTER_CROP)
            precision(Precision.EXACTLY)
        } else {
            scale(Scale.CENTER_INSIDE)
            precision(Precision.LESS_PIXELS)
        }

        // Animated images: allow animation only when no rounded corners are wanted.
        if (!animate || roundCorners != ROUNDED_CORNERS_NONE) {
            disallowAnimatedImage()
        }

        // Rounded corners via Sketch transformation.
        if (roundCorners != ROUNDED_CORNERS_NONE) {
            val cornerSize = if (roundCorners == ROUNDED_CORNERS_SMALL) {
                resources.getDimension(org.fossify.commons.R.dimen.rounded_corner_radius_small)
            } else {
                resources.getDimension(org.fossify.commons.R.dimen.rounded_corner_radius_big)
            }
            transformations(RoundedCornersTransformation(cornerSize))
            // When rounded corners are requested we force CENTER_CROP so corners clip cleanly.
            scale(Scale.CENTER_CROP)
            precision(Precision.EXACTLY)
        }

        // Crossfade transition (skips fade for memory-cache hits via alwaysUse = false).
        crossfade(durationMillis = crossFadeDuration, alwaysUse = false)

        // Error callback forwarded from caller (e.g. MediaAdapter).
        if (onError != null) {
            addListener(
                onError = { _, _ -> onError() }
            )
        }
    }
}

/**
 * SVG thumbnail loader backed by Sketch's built-in [SvgDecoder].
 * Software layer is set automatically by [SvgSoftwareLayerSetter] via a Sketch listener.
 */
fun Context.loadSVG(
    path: String,
    target: MySquareImageView,
    cropThumbnails: Boolean,
    roundCorners: Int,
    signature: String,
    crossFadeDuration: Int = THUMBNAIL_FADE_DURATION_MS,
) {
    target.scaleType = if (cropThumbnails) {
        ImageView.ScaleType.CENTER_CROP
    } else {
        ImageView.ScaleType.FIT_CENTER
    }

    target.loadImage(path) {
        memoryCacheKeyExtras(mapOf("sig" to signature))
        resultCacheKeyExtras(mapOf("sig" to signature))

        if (roundCorners != ROUNDED_CORNERS_NONE) {
            val cornerSize = when (roundCorners) {
                ROUNDED_CORNERS_SMALL -> resources.getDimension(org.fossify.commons.R.dimen.rounded_corner_radius_small)
                else -> resources.getDimension(org.fossify.commons.R.dimen.rounded_corner_radius_big)
            }
            transformations(RoundedCornersTransformation(cornerSize))
            scale(Scale.CENTER_CROP)
            precision(Precision.EXACTLY)
        }

        crossfade(durationMillis = crossFadeDuration, alwaysUse = false)

        // SVG rendering requires a software layer on the target view.
        addListener(SvgSoftwareLayerSetter(target))
    }
}

// ---------------------------------------------------------------------------
// Remaining Context extension functions (unchanged logic, only imports differ)
// ---------------------------------------------------------------------------

fun Context.getCachedDirectories(
    getVideosOnly: Boolean = false,
    getImagesOnly: Boolean = false,
    forceShowHidden: Boolean = false,
    forceShowExcluded: Boolean = false,
    callback: (ArrayList<Directory>) -> Unit,
) {
    ensureBackgroundThread {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_MORE_FAVORABLE)
        } catch (ignored: Exception) {
        }

        val directories = try {
            directoryDB.getAll() as ArrayList<Directory>
        } catch (e: Exception) {
            ArrayList()
        }

        if (!config.showRecycleBinAtFolders) {
            directories.removeAll { it.isRecycleBin() }
        }

        val shouldShowHidden = config.shouldShowHidden || forceShowHidden
        val excludedPaths = if (config.temporarilyShowExcluded || forceShowExcluded) {
            HashSet()
        } else {
            config.excludedFolders
        }

        val includedPaths = config.includedFolders

        val folderNoMediaStatuses = HashMap<String, Boolean>()
        val noMediaFolders = getNoMediaFoldersSync()
        noMediaFolders.forEach { folder ->
            folderNoMediaStatuses["$folder/$NOMEDIA"] = true
        }

        var filteredDirectories = directories.filter {
            it.path.shouldFolderBeVisible(
                excludedPaths = excludedPaths,
                includedPaths = includedPaths,
                showHidden = shouldShowHidden,
                folderNoMediaStatuses = folderNoMediaStatuses
            ) { path, hasNoMedia ->
                folderNoMediaStatuses[path] = hasNoMedia
            }
        } as ArrayList<Directory>

        val filterMedia = config.filterMedia
        filteredDirectories = (when {
            getVideosOnly -> filteredDirectories.filter { it.types and TYPE_VIDEOS != 0 }
            getImagesOnly -> filteredDirectories.filter { it.types and TYPE_IMAGES != 0 }
            else -> filteredDirectories.filter {
                it.types and filterMedia != 0
            }
        }) as ArrayList<Directory>

        callback(filteredDirectories)
    }
}

fun Context.getCachedMedia(
    path: String,
    getVideosOnly: Boolean = false,
    getImagesOnly: Boolean = false,
    callback: (ArrayList<ThumbnailItem>) -> Unit,
) {
    ensureBackgroundThread {
        val mediaFetcher = MediaFetcher(applicationContext)
        val getProperDateTaken = config.sorting and SORT_BY_DATE_TAKEN != 0 ||
                config.groupBy and GROUP_BY_DATE_TAKEN_DAILY != 0 ||
                config.groupBy and GROUP_BY_DATE_TAKEN_MONTHLY != 0

        val getProperLastModified = config.sorting and SORT_BY_DATE_MODIFIED != 0 ||
                config.groupBy and GROUP_BY_LAST_MODIFIED_DAILY != 0 ||
                config.groupBy and GROUP_BY_LAST_MODIFIED_MONTHLY != 0

        val getProperFileSize = config.sorting and SORT_BY_SIZE != 0

        val folders = if (path.isEmpty()) mediaFetcher.getFoldersToScan() else arrayListOf(path)
        var media = ArrayList<Medium>()
        val shouldShowHidden = config.shouldShowHidden

        folders.forEach { folder ->
            val newMedia = mediaFetcher.getFilesFrom(
                curPath = folder,
                getImagesOnly = getImagesOnly,
                getVideosOnly = getVideosOnly,
                getProperDateTaken = getProperDateTaken,
                getProperLastModified = getProperLastModified,
                getProperFileSize = getProperFileSize,
                favoritePaths = getFavoritePaths(),
                getVideoDurations = false,
            )
            media.addAll(newMedia)
        }

        if (!shouldShowHidden) {
            media = media.filter { !it.path.contains("/.") } as ArrayList<Medium>
        }

        val filterMedia = config.filterMedia
        media = (when {
            getVideosOnly -> media.filter { it.type == TYPE_VIDEOS }
            getImagesOnly -> media.filter { it.type == TYPE_IMAGES }
            else -> media.filter { it.type and filterMedia != 0 }
        }) as ArrayList<Medium>

        callback(mediaFetcher.groupMedia(media, path))
    }
}

fun Context.removeInvalidDBDirectories(dirs: ArrayList<Directory>? = null) {
    val dirsToCheck = dirs ?: directoryDB.getAll() as ArrayList<Directory>
    val invalidDirs = dirsToCheck.filter { !it.areFavorites() && !it.isRecycleBin() && !getDoesFilePathExist(it.path) }
    if (invalidDirs.isNotEmpty()) {
        directoryDB.deleteAll(invalidDirs)
    }
}

fun Context.updateDBMediaPath(oldPath: String, newPath: String) {
    val newFilename = newPath.getFilenameFromPath()
    val newParentPath = newPath.getParentPath()
    mediumDB.updateMedium(oldPath, newPath, newParentPath, newFilename)
}

fun Context.updateDBDirectory(directory: Directory) {
    directoryDB.updateDirectory(
        directory.path, directory.tmb, directory.mediaCnt, directory.modified,
        directory.taken, directory.size, directory.types, directory.sortValue
    )
}

fun Context.getOTGFolderChildren(path: String) = getDocumentFile(path)?.listFiles()

fun Context.getOTGFolderChildrenNames(path: String): MutableList<String?>? {
    return getOTGFolderChildren(path)?.map { it.name }?.toMutableList()
}

fun Context.getFavoritePaths(): ArrayList<String> {
    return try {
        favoritesDB.getValidFavorites() as ArrayList<String>
    } catch (e: Exception) {
        ArrayList()
    }
}

fun Context.getFavoriteFromPath(path: String): Favorite {
    return Favorite(null, path, path.getFilenameFromPath(), path.getParentPath())
}

fun Context.updateFavorite(path: String, isFavorite: Boolean) {
    if (isFavorite) {
        favoritesDB.insert(getFavoriteFromPath(path))
    } else {
        favoritesDB.deleteFavoritePath(path)
    }
}

fun Context.getUpdatedDeletedMedia(): ArrayList<Medium> {
    val media = try {
        mediumDB.getDeletedMedia() as ArrayList<Medium>
    } catch (e: Exception) {
        ArrayList()
    }

    media.forEach {
        it.deletedTS = it.deletedTS * 1000
    }

    return media
}

fun Context.deleteDBPath(path: String) {
    deleteMediumWithPath(path)
}

fun Context.deleteMediumWithPath(path: String) {
    mediumDB.deleteMediumPath(path)
}

fun Context.updateWidgets() {
    val widgetIDs = AppWidgetManager.getInstance(applicationContext)?.getAppWidgetIds(
        ComponentName(applicationContext, MyWidgetProvider::class.java)
    ) ?: return

    if (widgetIDs.isNotEmpty()) {
        Intent(applicationContext, MyWidgetProvider::class.java).apply {
            action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, widgetIDs)
            sendBroadcast(this)
        }
    }
}

fun Context.parseFileChannel(
    path: String,
    fc: FileChannel,
    level: Int,
    start: Long,
    end: Long,
    callback: () -> Unit,
) {
    val MARKER_BEGINNING_OF_SEGMENT = 0x02
    val MARKER_CREATIVE_COMMONS = "CreativeCommons"
    try {
        if (level == 0) {
            fc.position(start)
        }

        val buffer = ByteBuffer.allocate((end - start).toInt())
        fc.read(buffer)
        buffer.rewind()

        if (end - start == 0L) {
            return
        }

        while (buffer.position() < buffer.capacity() - MARKER_BEGINNING_OF_SEGMENT) {
            if (buffer.get() == MARKER_BEGINNING_OF_SEGMENT.toByte() && buffer.get() == MARKER_BEGINNING_OF_SEGMENT.toByte()) {
                val position = buffer.position()
                val segmentLength = (buffer.get().toInt() and 0xFF shl 8) or (buffer.get().toInt() and 0xFF)
                val segmentStart = start + position
                val segmentEnd = segmentStart + segmentLength

                if (segmentStart < end && segmentEnd <= end) {
                    parseFileChannel(path, fc, level + 1, segmentStart, segmentEnd, callback)
                }

                val bytes = ByteArray(MARKER_CREATIVE_COMMONS.length)
                buffer.get(bytes)
                val s = String(bytes)
                if (s == MARKER_CREATIVE_COMMONS) {
                    callback()
                    return
                }

                buffer.position(position)
            }
        }
    } catch (ignored: Exception) {
    }
}

fun Context.addPathToDB(path: String) {
    ensureBackgroundThread {
        if (!getDoesFilePathExist(path)) {
            return@ensureBackgroundThread
        }

        val type = when {
            path.isVideoFast() -> TYPE_VIDEOS
            path.isGif() -> TYPE_GIFS
            path.isRawFast() -> TYPE_RAWS
            path.isSvg() -> TYPE_SVGS
            path.isPortrait() -> TYPE_PORTRAITS
            else -> TYPE_IMAGES
        }

        try {
            val dominated = false
            val videoDuration = if (path.isVideoFast()) getDuration(path) ?: 0 else 0
            val medium = Medium(
                id = null,
                name = path.getFilenameFromPath(),
                path = path,
                parentPath = path.getParentPath(),
                modified = File(path).lastModified(),
                taken = 0,
                size = File(path).length(),
                type = type,
                videoDuration = videoDuration,
                isFavorite = false,
                deletedTS = 0,
                mediaStoreId = 0,
            )
            mediumDB.insert(medium)
        } catch (ignored: Exception) {
        }
    }
}

fun Context.createDirectoryFromMedia(
    path: String,
    curMedia: ArrayList<Medium>,
    albumCovers: ArrayList<AlbumCover>,
    hiddenString: String,
    includedFolders: MutableSet<String>,
    isSortingAscending: Boolean,
    noMediaFolderPaths: ArrayList<String>,
): Directory {
    var thumbnail = albumCovers.firstOrNull { it.path == path && File(it.tmb).exists() }?.tmb ?: ""
    if (thumbnail.isEmpty()) {
        thumbnail = curMedia.firstOrNull { it.type != TYPE_VIDEOS }?.path ?: curMedia.firstOrNull()?.path ?: ""
    }

    val mediaTypes = curMedia.distinctBy { it.type }.sumOf { it.type }
    val dirName = checkAppendingHidden(path, hiddenString, includedFolders, noMediaFolderPaths)
    val lastModified = if (isSortingAscending) {
        curMedia.minBy { it.modified }.modified
    } else {
        curMedia.maxBy { it.modified }.modified
    }
    val dateTaken = if (isSortingAscending) {
        curMedia.minBy { it.taken }.taken
    } else {
        curMedia.maxBy { it.taken }.taken
    }
    val size = curMedia.sumByLong { it.size }
    val sortValue = getDirectorySortingValue(path)
    return Directory(
        id = null,
        path = path,
        tmb = thumbnail,
        name = dirName,
        mediaCnt = curMedia.size,
        modified = lastModified,
        taken = dateTaken,
        size = size,
        location = getPathLocation(path),
        types = mediaTypes,
        sortValue = sortValue,
    )
}

fun Context.getDirectorySortingValue(path: String): String {
    val sorting = config.directorySorting
    val isSortingAscending = sorting and SORT_DESCENDING == 0
    val sortValue = when {
        sorting and SORT_BY_NAME != 0 -> path.getFilenameFromPath().lowercase()
        sorting and SORT_BY_SIZE != 0 -> "0"
        sorting and SORT_BY_DATE_MODIFIED != 0 -> "0"
        sorting and SORT_BY_DATE_TAKEN != 0 -> "0"
        else -> "0"
    }

    return sortValue
}

fun Context.updateDirectoryPath(path: String) {
    val mediaFetcher = MediaFetcher(applicationContext)
    val getProperDateTaken = config.sorting and SORT_BY_DATE_TAKEN != 0 ||
            config.groupBy and GROUP_BY_DATE_TAKEN_DAILY != 0 ||
            config.groupBy and GROUP_BY_DATE_TAKEN_MONTHLY != 0

    val getProperLastModified = config.sorting and SORT_BY_DATE_MODIFIED != 0 ||
            config.groupBy and GROUP_BY_LAST_MODIFIED_DAILY != 0 ||
            config.groupBy and GROUP_BY_LAST_MODIFIED_MONTHLY != 0

    val getProperFileSize = config.sorting and SORT_BY_SIZE != 0

    val curMedia = mediaFetcher.getFilesFrom(
        curPath = path,
        getImagesOnly = false,
        getVideosOnly = false,
        getProperDateTaken = getProperDateTaken,
        getProperLastModified = getProperLastModified,
        getProperFileSize = getProperFileSize,
        favoritePaths = getFavoritePaths(),
        getVideoDurations = false,
    )

    val directory = createDirectoryFromMedia(
        path = path,
        curMedia = curMedia,
        albumCovers = config.parseAlbumCovers(),
        hiddenString = getString(org.fossify.commons.R.string.hidden),
        includedFolders = config.includedFolders,
        isSortingAscending = config.sorting and SORT_DESCENDING == 0,
        noMediaFolderPaths = getNoMediaFoldersSync(),
    )
    updateDBDirectory(directory)
}

fun Context.getFileDateTaken(path: String): Long {
    val projection = arrayOf(
        Images.Media.DATE_TAKEN
    )

    val uri = Files.getContentUri("external")
    val selection = "${Images.Media.DATA} = ?"
    val selectionArgs = arrayOf(path)

    try {
        val cursor = contentResolver.query(uri, projection, selection, selectionArgs, null)
        cursor?.use {
            if (it.moveToFirst()) {
                return it.getLongValue(Images.Media.DATE_TAKEN)
            }
        }
    } catch (ignored: Exception) {
    }

    return 0L
}
