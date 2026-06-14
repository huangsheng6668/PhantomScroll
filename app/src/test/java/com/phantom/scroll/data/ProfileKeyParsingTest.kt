package com.phantom.scroll.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileKeyParsingTest {

    @Test
    fun extracts_dotted_package_name_for_every_field() {
        // Regression: dotted package names must survive the round-trip.
        assertEquals("com.example.app", profilePackageFromKey("profile.com.example.app.duration"))
        assertEquals("com.example.app", profilePackageFromKey("profile.com.example.app.interval"))
        assertEquals("com.example.app", profilePackageFromKey("profile.com.example.app.distanceRatio"))
        assertEquals("com.example.app", profilePackageFromKey("profile.com.example.app.direction"))
    }

    @Test
    fun extracts_single_segment_package_name() {
        assertEquals("foo", profilePackageFromKey("profile.foo.duration"))
    }

    @Test
    fun returns_null_for_non_profile_keys() {
        assertNull(profilePackageFromKey("global.duration"))
        assertNull(profilePackageFromKey("perapp.enabled"))
        assertNull(profilePackageFromKey("stats.swipe"))
    }
}
