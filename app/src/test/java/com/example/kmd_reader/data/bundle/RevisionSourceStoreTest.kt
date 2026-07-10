package com.example.kmd_reader.data.bundle

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R3-E：RevisionSourceStore source snapshot 文件读写 + 路径安全 guard。
 * 纯 JVM 测试，不依赖 Android Context。
 */
class RevisionSourceStoreTest {

  private fun newStore(): Pair<RevisionSourceStore, File> {
    val filesDir = Files.createTempDirectory("rsst").toFile()
    return RevisionSourceStore(filesDir) to filesDir
  }

  // —— 写→读 round-trip ——

  @Test
  fun bundleRevisionPathWriteReadRoundTrip() {
    val (store, filesDir) = newStore()
    val path = store.bundleRevisionPath("bundle-1", "rev-001")
    val content = "title: test\n---\nbody"
    store.writeSource(path, content)

    assertEquals(content, store.readSource(path))
    assertTrue(File(filesDir, "bundles/bundle-1/revisions/rev-001.kmd").exists())
  }

  @Test
  fun plainKmdRevisionPathWriteReadRoundTrip() {
    val (store, filesDir) = newStore()
    val workKey = workKey("local-abc12345")
    val path = store.plainKmdRevisionPath(workKey, "rev-002")
    val content = "title: plain\n---\ncommitted"
    store.writeSource(path, content)

    assertEquals(content, store.readSource(path))
    assertTrue(File(filesDir, "local-revisions/$workKey/revisions/rev-002.kmd").exists())
  }

  // —— 路径穿越 guard ——

  @Test
  fun rejectsPathTraversalDotDot() {
    val (store, _) = newStore()
    assertThrows(IllegalArgumentException::class.java) {
      store.writeSource("bundles/../escape.kmd", "x")
    }
  }

  @Test
  fun rejectsAbsolutePath() {
    val (store, _) = newStore()
    assertThrows(IllegalArgumentException::class.java) {
      store.writeSource("/abs/path.kmd", "x")
    }
  }

  @Test
  fun rejectsNonKmdExtension() {
    val (store, _) = newStore()
    assertThrows(IllegalArgumentException::class.java) {
      store.writeSource("bundles/bundle-1/revisions/rev-1.txt", "x")
    }
  }

  @Test
  fun rejectsBlankPath() {
    val (store, _) = newStore()
    assertThrows(IllegalArgumentException::class.java) {
      store.readSource("")
    }
  }

  // —— canonical guard：符号链接逃逸 ——

  @Test
  fun rejectsSymlinkEscape() {
    val (store, filesDir) = newStore()
    val outside = Files.createTempDirectory("rsst-outside").toFile()
    val outsideTarget = File(outside, "escape.kmd").apply { writeText("escaped") }
    // 在 filesDir 下建 local-revisions/，把 revisions 指向 outside 的 symlink
    val linkParent = File(filesDir, "local-revisions/symlink-test/revisions").apply { mkdirs() }
    File(linkParent, "rev.kmd").apply {
      // java.nio symlink
      java.nio.file.Files.createSymbolicLink(
        toPath(),
        outsideTarget.canonicalFile.toPath()
      )
    }
    // readSource 经过 canonical guard 应拒绝（target canonical 在 filesDir 外）
    assertThrows(IllegalArgumentException::class.java) {
      store.readSource("local-revisions/symlink-test/revisions/rev.kmd")
    }
  }

  // —— deleteSource ——

  @Test
  fun deleteSourceRemovesFile() {
    val (store, filesDir) = newStore()
    val path = store.bundleRevisionPath("bundle-del", "rev-del")
    store.writeSource(path, "to be deleted")
    val file = File(filesDir, path)
    assertTrue(file.exists())

    store.deleteSource(path)

    assertFalse(file.exists())
  }

  @Test
  fun deleteSourceMissingFileIsSilent() {
    val (store, _) = newStore()
    val path = store.bundleRevisionPath("bundle-missing", "rev-missing")
    // 不存在也不抛
    store.deleteSource(path)
  }

  // —— readSource miss ——

  @Test
  fun readSourceReturnsNullForMissingFile() {
    val (store, _) = newStore()
    val path = store.plainKmdRevisionPath(workKey("nope"), "rev-none")
    assertNull(store.readSource(path))
  }

  // —— workKey 派生 ——

  @Test
  fun workKeyIsSafeSingleSegment() {
    val key = workKey("local-abc12345")
    assertEquals(16, key.length)
    assertFalse(key.contains("/"))
    assertFalse(key.contains("\\"))
    assertFalse(key.contains(".."))
    // 不同 workId 派生不同 key（无碰撞）
    assertEquals(workKey("local-abc12345"), workKey("local-abc12345"))
    assertTrue(workKey("local-abc12345") != workKey("local-abc12346"))
  }

  // —— R3-E 审查修复：writeSource 覆写保护 ——

  @Test
  fun writeSourceRefusesToOverwriteExistingFile() {
    val (store, _) = newStore()
    val path = store.bundleRevisionPath("bundle-ow", "rev-ow")
    val original = "title: 原始\n---\noriginal body"
    store.writeSource(path, original)

    // 再次写同一 sourcePath 应抛 IllegalStateException，不覆写
    assertThrows(IllegalStateException::class.java) {
      store.writeSource(path, "title: 篡改\n---\ntampered")
    }

    // 原内容不变
    assertEquals(original, store.readSource(path))
  }

  @Test
  fun writeSourceDifferentRevisionsDoNotCollide() {
    val (store, _) = newStore()
    val path1 = store.bundleRevisionPath("bundle-multi", "rev-001")
    val path2 = store.bundleRevisionPath("bundle-multi", "rev-002")
    val content1 = "title: 第一版\n---\nv1"
    val content2 = "title: 第二版\n---\nv2"

    store.writeSource(path1, content1)
    store.writeSource(path2, content2)

    // 两个 revision 各自独立，互不覆写
    assertEquals(content1, store.readSource(path1))
    assertEquals(content2, store.readSource(path2))
  }
}