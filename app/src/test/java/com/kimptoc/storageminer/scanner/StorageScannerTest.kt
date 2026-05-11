package com.kimptoc.storageminer.scanner

import com.kimptoc.storageminer.model.StorageItem
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class StorageScannerTest {

    private lateinit var root: File
    private val scanner = StorageScanner()

    @Before
    fun setUp() {
        root = createTempDirectory(prefix = "storageminer-test-").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun mkdir(relPath: String): File {
        val d = File(root, relPath)
        d.mkdirs()
        return d
    }

    private fun mkfile(relPath: String, contents: ByteArray): File {
        val f = File(root, relPath)
        f.parentFile?.mkdirs()
        f.writeBytes(contents)
        return f
    }

    @Test
    fun `empty subdirectory has size zero and zero file count`() = runBlocking {
        mkdir("empty")

        val items = scanner.scan(root.absolutePath)

        val item = items.find { it.name == "empty" }
        assertNotNull("Expected an item for 'empty/'", item)
        assertEquals(true, item!!.isDirectory)
        assertEquals(0L, item.sizeBytes)
        assertEquals(0, item.fileCount)
    }

    @Test
    fun `directory with a single file has matching size and count one`() = runBlocking {
        mkfile("dir/a.txt", ByteArray(10))

        val items = scanner.scan(root.absolutePath)

        val item = items.find { it.name == "dir" }
        assertNotNull(item)
        assertEquals(true, item!!.isDirectory)
        assertEquals(10L, item.sizeBytes)
        assertEquals(1, item.fileCount)
    }

    @Test
    fun `directory counts files in nested subdirectories recursively`() = runBlocking {
        mkfile("dir/a.txt", ByteArray(5))
        mkfile("dir/sub/b.txt", ByteArray(3))
        mkfile("dir/sub/c.txt", ByteArray(2))

        val items = scanner.scan(root.absolutePath)

        val item = items.find { it.name == "dir" }
        assertNotNull(item)
        assertEquals(true, item!!.isDirectory)
        assertEquals(10L, item.sizeBytes)
        assertEquals(3, item.fileCount)
    }

    @Test
    fun `top-level file is reported as non-directory with count one`() = runBlocking {
        mkfile("loose.txt", ByteArray(7))

        val items = scanner.scan(root.absolutePath)

        val item = items.find { it.name == "loose.txt" }
        assertNotNull(item)
        assertEquals(false, item!!.isDirectory)
        assertEquals(7L, item.sizeBytes)
        assertEquals(1, item.fileCount)
    }
}
