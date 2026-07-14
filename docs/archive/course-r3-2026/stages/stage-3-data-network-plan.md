# KMD Reader Android 第三阶段数据与网络规划

> 项目阶段：Android 课程项目第三阶段
> 文档状态：草案
> 最近更新：2026-05-13

## 1. 阶段目标

第三阶段需要在当前 UI-only 原型上补齐数据层、网络层、Repository 和测试。

课程要求：

- 使用 Retrofit 定义 2 到 3 个 GET/POST API 方法。
- 使用 Room 定义 `@Entity`、`@Dao`、`@Database`。
- Repository 作为单一数据源，先读数据库，再请求网络并更新数据库。
- 至少 3 个测试方法全部通过。
- 代码中能看到 `@GET`、`@POST`、`@Entity`、`@Dao`。

本项目会使用自建的 `kmd-community-api`，而不是 `jsonplaceholder`。这样 Android 的网络层、DTO 和 KMD 业务模型保持一致。

## 2. 技术栈

| 类别 | 选择 | 说明 |
|------|------|------|
| Network | Retrofit + OkHttp | Retrofit 提供接口注解，OkHttp 作为底层客户端 |
| JSON | kotlinx.serialization 或 Moshi | 推荐 kotlinx.serialization，与 Kotlin 数据类配合自然 |
| Database | Room | Android 官方推荐的 SQLite 抽象层 |
| Async | Coroutines | DAO、API、Repository 使用 suspend |
| Test | JUnit + kotlinx-coroutines-test | 使用 `runTest`，不使用已过时的 `runBlockingTest` |

说明：

- `runBlockingTest` 已过时，第三阶段实现使用 `runTest`。
- 如果老师按旧模板检查，可以在报告中说明“使用新版协程测试 API 替代 deprecated API”。

## 3. 推荐包结构

```text
com.example.kmd_reader/
  data/
    local/
      KmdReaderDatabase.kt
      WorkEntity.kt
      WorkDao.kt
      ScriptIssueEntity.kt
      ScriptIssueDao.kt
    remote/
      KmdCommunityApi.kt
      NetworkModule.kt
      dto/
        WorkDto.kt
        ScriptIssueDto.kt
        ReviewRequestDto.kt
        ReviewResponseDto.kt
    repository/
      WorkRepository.kt
      OfflineFirstWorkRepository.kt
      WorkMappers.kt
  domain/
    model/
      Work.kt
      ScriptIssue.kt
```

现有 `data/WorkRepository.kt` 和 `data/MockWorkRepository.kt` 后续可以迁移到 `data/repository/`。

## 4. Retrofit API 设计

Android 对接 `kmd-community-api`：

实现状态：已完成基础网络层。

当前已落地：

- `data/remote/KmdCommunityApi.kt`
- `data/remote/NetworkModule.kt`
- `data/remote/dto/WorkDto.kt`
- `data/remote/dto/ScriptIssueDto.kt`
- `data/remote/dto/ReviewDto.kt`

```kotlin
interface KmdCommunityApi {
    @GET("works")
    suspend fun getWorks(): List<WorkDto>

    @GET("works/{id}")
    suspend fun getWork(@Path("id") id: String): WorkDto

    @GET("works/{id}/issues")
    suspend fun getIssues(@Path("id") id: String): List<ScriptIssueDto>

    @POST("reviews")
    suspend fun submitReview(@Body request: ReviewRequestDto): ReviewResponseDto
}
```

课程只要求 2 到 3 个方法。我们可以实现 4 个，但提交说明中突出：

- `GET /works`
- `GET /works/{id}/issues`
- `POST /reviews`

## 5. Room 设计

实现状态：已完成基础数据库层。

当前已落地：

- `data/local/WorkEntity.kt`
- `data/local/ScriptIssueEntity.kt`
- `data/local/WorkDao.kt`
- `data/local/ScriptIssueDao.kt`
- `data/local/KmdReaderDatabase.kt`
- `data/repository/WorkMappers.kt`

数据库只保存作品元信息和脚本检查问题，不保存完整 KMD 脚本文本。

### 5.1 WorkEntity

```kotlin
@Entity(tableName = "works")
data class WorkEntity(
    @PrimaryKey val id: String,
    val title: String,
    val authorName: String,
    val description: String,
    val tags: String,
    val category: String,
    val sourceType: String,
    val lifecycleStatus: String,
    val presentationMode: String,
    val orientationHint: String,
    val aspectRatio: String,
    val interactionLevel: String,
    val previewMode: String,
    val contentUri: String,
    val previewUri: String?,
    val estimatedDurationSec: Int,
    val syncedAt: Long
)
```

### 5.2 ScriptIssueEntity

```kotlin
@Entity(tableName = "script_issues")
data class ScriptIssueEntity(
    @PrimaryKey val id: String,
    val workId: String,
    val severity: String,
    val source: String,
    val location: String,
    val message: String,
    val suggestion: String,
    val syncedAt: Long
)
```

### 5.3 DAO

```kotlin
@Dao
interface WorkDao {
    @Query("SELECT * FROM works")
    suspend fun getAll(): List<WorkEntity>

    @Query("SELECT * FROM works WHERE id = :id")
    suspend fun getById(id: String): WorkEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(works: List<WorkEntity>)

    @Query("DELETE FROM works")
    suspend fun clear()
}
```

```kotlin
@Dao
interface ScriptIssueDao {
    @Query("SELECT * FROM script_issues WHERE workId = :workId")
    suspend fun getByWorkId(workId: String): List<ScriptIssueEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(issues: List<ScriptIssueEntity>)

    @Query("DELETE FROM script_issues WHERE workId = :workId")
    suspend fun clearForWork(workId: String)
}
```

### 5.4 Database

```kotlin
@Database(
    entities = [WorkEntity::class, ScriptIssueEntity::class],
    version = 1
)
abstract class KmdReaderDatabase : RoomDatabase() {
    abstract fun workDao(): WorkDao
    abstract fun scriptIssueDao(): ScriptIssueDao
}
```

## 6. Repository 设计

Repository 作为单一数据源：

实现状态：已完成 `OfflineFirstWorkRepository`。

```kotlin
class OfflineFirstWorkRepository(
    private val workDao: WorkDao,
    private val issueDao: ScriptIssueDao,
    private val api: KmdCommunityApi
) {
    suspend fun listWorks(refresh: Boolean = true): List<Work> {
        val cached = workDao.getAll().map { it.toDomain() }
        if (!refresh) return cached

        return try {
            val remote = api.getWorks()
            workDao.upsertAll(remote.map { it.toEntity() })
            workDao.getAll().map { it.toDomain() }
        } catch (_: Exception) {
            cached
        }
    }
}
```

策略：

- UI 不直接调用 DAO 或 Retrofit。
- Repository 先返回/使用本地缓存。
- 网络成功后更新 Room。
- 网络失败时不让 App 崩溃，保留本地数据。

当前已实现：

- `listWorks(refresh: Boolean = true)`
- `getWork(id: String, refresh: Boolean = true)`
- `listIssues(workId: String, refresh: Boolean = true)`
- `submitReview(request: ReviewRequestDto)`

## 7. DTO / Entity / Domain 分离

不要把一个数据类同时用于网络、数据库和 UI。

```text
WorkDto       网络返回
WorkEntity    Room 持久化
Work          domain/UI 使用
```

转换函数集中到：

```text
data/repository/WorkMappers.kt
```

示例：

```kotlin
fun WorkDto.toEntity(now: Long): WorkEntity
fun WorkEntity.toDomain(): Work
fun ScriptIssueDto.toEntity(now: Long): ScriptIssueEntity
fun ScriptIssueEntity.toDomain(): ScriptIssue
```

## 8. 测试计划

至少 3 个测试：

### 8.1 DAO 插入和查询

```kotlin
@Test
fun insertWorks_thenQueryAll_returnsInsertedWorks() = runTest {
    // in-memory Room database
}
```

### 8.2 DAO 替换或删除

```kotlin
@Test
fun upsertWork_withSameId_replacesOldValue() = runTest {
    // insert old, insert new, assert updated
}
```

### 8.3 Mapper / Repository 转换

```kotlin
@Test
fun workDto_toEntity_toDomain_preservesPresentationMode() = runTest {
    // DTO -> Entity -> Domain
}
```

可选增强：

```kotlin
@Test
fun repository_whenNetworkFails_returnsCachedWorks() = runTest
```

当前新增测试覆盖：

- DAO 插入查询。
- DAO upsert 替换。
- issue 替换。
- domain/entity mapper round-trip。
- Repository 远端刷新后写入缓存。
- Repository 网络失败时返回缓存。
- Repository 刷新脚本问题时替换旧缓存。
- 可选本地联调测试：通过 Retrofit 请求正在运行的 `kmd-community-api`，再经 Repository 写入 Room。

本地联调命令：

```bash
# 在 KMD 主仓库启动 API
pnpm community-api:dev

# 在 Android Reader 仓库执行真实 API 联调
./gradlew :app:testDebugUnitTest \
  --tests com.example.kmd_reader.data.remote.CommunityApiIntegrationTest \
  -Pkmd.integration=true \
  -Pkmd.apiBaseUrl=http://127.0.0.1:3000/
```

说明：

- JVM 联调测试使用 `127.0.0.1` 访问宿主机 API。
- Android 模拟器运行 App 时使用 `http://10.0.2.2:3000/`。
- 未传 `-Pkmd.integration=true` 时，联调测试会被跳过，避免后端未启动导致日常测试失败。

## 9. 依赖规划

需要新增：

```text
Room runtime
Room ktx
Room compiler via KSP
Retrofit
OkHttp logging interceptor
kotlinx serialization converter or Moshi converter
kotlinx-coroutines-test
androidx.room:room-testing
```

由于项目使用 Kotlin 2.x，推荐使用 KSP 而不是 kapt。

当前 AGP 9 built-in Kotlin 与 KSP 组合需要在 `gradle.properties` 中加入：

```properties
android.disallowKotlinSourceSets=false
```

这是为了允许 KSP 将 Room 生成代码接入 Kotlin sourceSets。后续如果 KSP/AGP 改善了内置 Kotlin 支持，可以回收这个兼容开关。

Room DAO 的 JVM 测试使用 Robolectric，并将测试 SDK 固定到 28，避免 Robolectric 对项目 target SDK 36 支持滞后的问题。

## 10. 验收清单

- [x] `./gradlew :app:assembleDebug` 通过。
- [x] `./gradlew :app:testDebugUnitTest` 通过。
- [x] 代码中存在 `@GET`、`@POST`。
- [x] 代码中存在 `@Entity`、`@Dao`、`@Database`。
- [x] 存在 `OfflineFirstWorkRepository` 或等价 Repository。
- [x] Repository 至少有一个 `suspend` 函数。
- [x] 至少 3 个测试为绿色。

## 11. 实现顺序

1. 添加 Gradle 依赖。已完成 Room / KSP / Retrofit / OkHttp / kotlinx serialization / Room testing / coroutines-test / Robolectric。
2. 新增 remote DTO 和 `KmdCommunityApi`。已完成。
3. 新增 Room Entity、Dao、Database。已完成。
4. 新增 mapper。已完成。
5. 新增 `OfflineFirstWorkRepository`。已完成。
6. 让现有 UI 继续可以使用 mock 或 repository。
7. 编写 DAO 和 mapper/repository 测试。已完成。
8. 接入 `kmd-community-api` 本地服务。

## 12. 风险与边界

- 后端不可用时，Android 必须可用缓存或 seed 数据运行。
- 第三阶段不接真实登录。
- 第三阶段不接真实 KMD Reader Runtime。
- Room 不保存完整 KMD 脚本正文，只保存元信息和检查问题。
- 审阅 POST 可以只演示网络请求和本地提示，不必实现完整权限系统。
