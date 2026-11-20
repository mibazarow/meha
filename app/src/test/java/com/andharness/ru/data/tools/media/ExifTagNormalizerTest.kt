package com.andharness.ru.data.tools.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [ExifTagNormalizer]: both spellings of every writable tag must resolve to the
 * canonical `ExifInterface.TAG_*` value, matching is case-insensitive, and unknown/blank input
 * stays rejected. Pure string logic, no Android runtime needed.
 */
class ExifTagNormalizerTest {

    @Test
    fun shortNameAndConstantNameResolveToSameCanonicalValue() {
        assertEquals("DateTimeOriginal", ExifTagNormalizer.normalize("DateTimeOriginal"))
        assertEquals("DateTimeOriginal", ExifTagNormalizer.normalize("TAG_DATETIME_ORIGINAL"))
    }

    @Test
    fun alreadyWorkingShortNameIsUnchanged() {
        val canonical = listOf(
            "DateTime", "DateTimeOriginal", "Make", "Model", "Orientation", "Software", "Artist",
            "ImageDescription", "Copyright", "GPSLatitude", "GPSLatitudeRef", "GPSLongitude",
            "GPSLongitudeRef",
        )
        canonical.forEach { assertEquals(it, ExifTagNormalizer.normalize(it)) }
    }

    @Test
    fun gpsAndMultiWordConstantsMapCorrectly() {
        assertEquals("GPSLatitude", ExifTagNormalizer.normalize("TAG_GPS_LATITUDE"))
        assertEquals("GPSLatitudeRef", ExifTagNormalizer.normalize("TAG_GPS_LATITUDE_REF"))
        assertEquals("GPSLongitude", ExifTagNormalizer.normalize("TAG_GPS_LONGITUDE"))
        assertEquals("GPSLongitudeRef", ExifTagNormalizer.normalize("TAG_GPS_LONGITUDE_REF"))
        assertEquals("ImageDescription", ExifTagNormalizer.normalize("TAG_IMAGE_DESCRIPTION"))
    }

    @Test
    fun matchingIsCaseInsensitiveAndTrimmed() {
        assertEquals("DateTimeOriginal", ExifTagNormalizer.normalize("datetimeoriginal"))
        assertEquals("DateTimeOriginal", ExifTagNormalizer.normalize("tag_datetime_original"))
        assertEquals("DateTimeOriginal", ExifTagNormalizer.normalize("  TAG_DateTimeOriginal  "))
    }

    @Test
    fun unknownTagIsRejected() {
        assertNull(ExifTagNormalizer.normalize("TAG_NOT_A_REAL_TAG"))
        assertNull(ExifTagNormalizer.normalize("DateTimeDigitized"))
        assertNull(ExifTagNormalizer.normalize(""))
        assertNull(ExifTagNormalizer.normalize("   "))
    }

    @Test
    fun acceptedFormsListBothSpellings() {
        val forms = ExifTagNormalizer.acceptedForms
        assertTrue(forms.contains("DateTimeOriginal (TAG_DATETIME_ORIGINAL)"))
        assertTrue(forms.contains("GPSLatitude (TAG_GPS_LATITUDE)"))
    }
}
