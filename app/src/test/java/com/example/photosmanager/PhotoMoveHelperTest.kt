package com.example.photosmanager

import com.example.photosmanager.data.model.AlbumInfo
import com.example.photosmanager.data.repository.PhotoRepository
import com.example.photosmanager.data.repository.PreferencesRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhotoMoveHelperTest {

    @Test
    fun sanitizeAlbumName_acceptsValidAndTrimsWhitespace() {
        assertEquals("旅行照片", PhotoRepository.sanitizeAlbumName("  旅行照片  "))
        assertEquals("2026_Summer", PhotoRepository.sanitizeAlbumName("2026_Summer"))
    }

    @Test
    fun sanitizeAlbumName_rejectsBlankAndForbiddenCharacters() {
        assertNull(PhotoRepository.sanitizeAlbumName(""))
        assertNull(PhotoRepository.sanitizeAlbumName("   "))
        assertNull(PhotoRepository.sanitizeAlbumName("."))
        assertNull(PhotoRepository.sanitizeAlbumName(".."))
        assertNull(PhotoRepository.sanitizeAlbumName("DCIM/Camera"))
        assertNull(PhotoRepository.sanitizeAlbumName("a\\b"))
        assertNull(PhotoRepository.sanitizeAlbumName("bad:name"))
        assertNull(PhotoRepository.sanitizeAlbumName("bad*name"))
        assertNull(PhotoRepository.sanitizeAlbumName("a".repeat(61)))
    }

    @Test
    fun buildNewAlbumRelativePath_andNormalizeRelativePath() {
        assertEquals("Pictures/旅行/", PhotoRepository.buildNewAlbumRelativePath(" 旅行 "))
        assertEquals("DCIM/Camera/", PhotoRepository.normalizeRelativePath("/DCIM//Camera"))
        assertEquals("Pictures/My Album/", PhotoRepository.normalizeRelativePath("Pictures\\My Album\\"))
        assertEquals("", PhotoRepository.normalizeRelativePath(" / / "))
    }

    @Test
    fun deduplicateFileName_appendsIncrementingSuffixCaseInsensitively() {
        val existing = setOf("IMG_001.JPG", "img_001 (1).jpg", "note")
        assertEquals("IMG_002.jpg", PhotoRepository.deduplicateFileName("IMG_002.jpg", existing))
        assertEquals("IMG_001 (2).jpg", PhotoRepository.deduplicateFileName("IMG_001.jpg", existing))
        assertEquals("note (1)", PhotoRepository.deduplicateFileName("note", existing))
    }

    @Test
    fun sortAlbumsForMove_excludesCurrentAlbumAndPinsRecentsFirst() {
        val camera = AlbumInfo(1L, "Camera", "DCIM/Camera/", "/storage/emulated/0/DCIM/Camera", 500)
        val screenshots = AlbumInfo(2L, "Screenshots", "Pictures/Screenshots/", "/storage/emulated/0/Pictures/Screenshots", 300)
        val travel = AlbumInfo(3L, "Travel", "Pictures/Travel/", "/storage/emulated/0/Pictures/Travel", 20)
        val work = AlbumInfo(4L, "Work", "Pictures/Work/", "/storage/emulated/0/Pictures/Work", 80)

        val sorted = PhotoRepository.sortAlbumsForMove(
            albums = listOf(camera, screenshots, travel, work),
            currentBucketId = 1L,
            currentAlbumName = "Camera",
            recentRelativePaths = listOf("Pictures/Travel/", "Pictures/Work")
        )

        assertEquals(listOf("Travel", "Work", "Screenshots"), sorted.map { it.name })
    }

    @Test
    fun updateRecentTargets_deduplicatesMovesToFrontAndCapsSize() {
        val initial = listOf("Pictures/A/", "Pictures/B/", "Pictures/C/", "Pictures/D/", "Pictures/E/")
        val promoted = PreferencesRepository.updateRecentTargets(initial, "Pictures/C/")
        assertEquals(
            listOf("Pictures/C/", "Pictures/A/", "Pictures/B/", "Pictures/D/", "Pictures/E/"),
            promoted
        )

        val added = PreferencesRepository.updateRecentTargets(promoted, "Pictures/New/")
        assertEquals(5, added.size)
        assertEquals("Pictures/New/", added.first())
        assertEquals("Pictures/D/", added.last())
    }
}
