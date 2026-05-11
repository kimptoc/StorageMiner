package com.kimptoc.storageminer.util

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatUtilsTest {

    @Test
    fun `formatFileCount uses singular for one`() {
        assertEquals("1 file", formatFileCount(1))
    }

    @Test
    fun `formatFileCount uses plural for zero`() {
        assertEquals("0 files", formatFileCount(0))
    }

    @Test
    fun `formatFileCount uses plural for many`() {
        assertEquals("42 files", formatFileCount(42))
    }
}
