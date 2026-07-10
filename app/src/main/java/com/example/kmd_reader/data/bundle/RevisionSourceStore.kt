package com.example.kmd_reader.data.bundle

import java.io.File
import java.security.MessageDigest

/**
 * R3-E：本地提交（commit）的 source snapshot 文件读写（r3-local-reader-plan.md §2.7）。
 *
 * `LocalRevision.sourcePath` 是 filesDir 下相对路径，两类形态由路径本身区分：
 * - `.kmdwork` / `bundleId != null`：`bundles/<bundleId>/revisions/<revId>.kmd`，与 BundleStore 同根。
 * - 裸 `.kmd` / `bundleId == null`：`local-revisions/<workKey>/revisions/<revId>.kmd`，
 *   `workKey` 由 [workKey] 从 `workId` 派生的安全单路径段，不得直接信任外部 workId 作目录名。
 *
 * 安全 guard（对齐 BundleStore 的 defense-in-depth）：
 * - sourcePath 禁止 `..`、绝对路径前导 `/`、空、反斜杠。
 * - canonical guard：解析后 canonicalPath 必须以 filesDir.canonicalPath + File.separator 开头。
 * - 只允许 `.kmd` 扩展名（revision snapshot 格式固定）。
 *
 * 写入是 atomic（tmp 文件 + rename），失败不留半成品。提交不可变——修改即新提交（新 revId），
 * 不原地覆写已有 sourcePath 的文件。writeSource 在目标文件已存在时抛 IllegalStateException，
 * 与 DAO 的 ABORT（防重复 revision id）互补：DAO 不防不同 revision 指向同一 sourcePath，
 * 文件层 exists() 检查堵住这个缺口。
 */
class RevisionSourceStore(private val filesDir: File) {

  // ── 路径构造 helper（调用方构造 LocalRevision.sourcePath 时用）──

  /** `.kmdwork` 作品的 revision source 路径（filesDir 下相对路径）。 */
  fun bundleRevisionPath(bundleId: String, revId: String): String =
    "bundles/$bundleId/revisions/$revId.kmd"

  /** 裸 `.kmd` 作品的 revision source 路径（filesDir 下相对路径）。 */
  fun plainKmdRevisionPath(workKey: String, revId: String): String =
    "local-revisions/$workKey/revisions/$revId.kmd"

  // ── 读写 ──

  /** 读 revision source；文件不存在返回 null（miss，由调用方决定 fallback）。 */
  fun readSource(sourcePath: String): String? {
    val file = resolveSourceFile(sourcePath)
    if (!file.exists() || !file.isFile) return null
    return runCatching { file.readText() }.getOrNull()
  }

  /**
   * 写 revision source（atomic：先写 tmp 文件再 rename，失败清理 tmp + 目标不留半成品）。
   * 提交不可变：调用方应写新 sourcePath（新 revId），不覆写已有文件。
   *
   * 覆写保护：若目标文件已存在直接抛 IllegalStateException。这防止不同 revision 指向同一
   * sourcePath 时静默改写旧 snapshot，也防止"先写文件后 DB insert 失败"导致旧 snapshot
   * 被改写后 revision 记录却未落盘（append-only 保证需要文件层和 DB 层共同守护）。
   */
  fun writeSource(sourcePath: String, content: String) {
    val file = resolveSourceFile(sourcePath)
    if (file.exists()) {
      throw IllegalStateException("sourcePath already exists (append-only): $sourcePath")
    }
    file.parentFile?.mkdirs()
    val tmp = File(file.parentFile, ".${file.name}.tmp")
    try {
      tmp.writeText(content)
      if (!tmp.renameTo(file)) {
        // renameTo 可能跨文件系统失败——fallback 手动复制 + 删 tmp。
        // overwrite=false：目标已存在时抛错（双重保护，与上方 exists() 检查互补）。
        tmp.copyTo(file, overwrite = false)
        tmp.delete()
      }
    } catch (e: Exception) {
      tmp.delete()
      throw e
    }
  }

  /** 删 revision source 文件；不存在静默通过。 */
  fun deleteSource(sourcePath: String) {
    val file = resolveSourceFile(sourcePath)
    if (file.exists()) file.delete()
  }

  // ── 安全 guard ──

  /**
   * 把 sourcePath（filesDir 下相对路径）解析为实际 File，执行全部安全 guard。
   * 内部使用，外部只通过 read/write/delete 间接消费。
   */
  private fun resolveSourceFile(sourcePath: String): File {
    if (sourcePath.isBlank()) throw IllegalArgumentException("blank sourcePath")
    // 禁止 `..`（路径穿越）、绝对路径前导 `/`、反斜杠（Windows 分隔符逃逸）。
    if (sourcePath.startsWith("/") || sourcePath.contains("\\") ||
      sourcePath.split("/").any { it == ".." }
    ) {
      throw IllegalArgumentException("unsafe sourcePath: $sourcePath")
    }
    // 只允许 .kmd 扩展名（revision snapshot 格式固定）。
    if (!sourcePath.endsWith(".kmd")) {
      throw IllegalArgumentException("sourcePath must end with .kmd: $sourcePath")
    }
    val target = File(filesDir, sourcePath)
    // canonical guard：解析后必须在 filesDir 子树内（防符号链接逃逸）。
    val canonicalTarget = runCatching { target.canonicalFile }.getOrNull()
      ?: throw IllegalArgumentException("cannot canonicalize: $target")
    val canonicalRoot = runCatching { filesDir.canonicalFile }.getOrNull()
      ?: throw IllegalArgumentException("cannot canonicalize filesDir: $filesDir")
    if (!canonicalTarget.path.startsWith(canonicalRoot.path + File.separator)) {
      throw IllegalArgumentException("sourcePath escapes filesDir: $sourcePath")
    }
    return canonicalTarget
  }
}

/**
 * 由 `workId` 派生安全单路径段，作为裸 `.kmd` 作品 revision 存储目录名。
 * 不得直接信任外部 workId 作目录名——workId 可能含 `/`、`..` 等。
 */
internal fun workKey(workId: String): String {
  val digest = MessageDigest.getInstance("SHA-256").digest(workId.toByteArray(Charsets.UTF_8))
  return buildString(digest.size * 2) { digest.forEach { append("%02x".format(it)) } }.take(16)
}