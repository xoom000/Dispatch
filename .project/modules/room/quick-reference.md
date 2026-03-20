# Room Database — Quick Reference

Room provides an abstraction layer over SQLite with compile-time SQL verification,
observable query results via Flow/LiveData, and structured migration support.

- **Context7 library ID:** `/websites/developer_android_guide`
- **Context7 query:** `Room database`
- **Version:** 2.8.4
- **Docs:** https://developer.android.com/training/data-storage/room

---

## 1. Gradle Dependencies

```kotlin
// build.gradle.kts (app)
plugins {
    id("com.google.devtools.ksp") version "2.1.0-1.0.29" // match your Kotlin version
}

dependencies {
    val roomVersion = "2.8.4"

    // Runtime
    implementation("androidx.room:room-runtime:$roomVersion")
    // KTX extensions (coroutines, Flow support)
    implementation("androidx.room:room-ktx:$roomVersion")
    // KSP annotation processor (replaces kapt)
    ksp("androidx.room:room-compiler:$roomVersion")

    // Optional: testing helpers
    testImplementation("androidx.room:room-testing:$roomVersion")
}
```

---

## 2. @Entity Definition

```kotlin
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "users")
data class User(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,                        // autoGenerate = true requires Int/Long, default 0

    @ColumnInfo(name = "first_name")
    val firstName: String,

    @ColumnInfo(name = "last_name")
    val lastName: String,

    @ColumnInfo(name = "email", defaultValue = "")
    val email: String = "",

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
)
```

**Multi-column primary key:**

```kotlin
@Entity(
    tableName = "user_roles",
    primaryKeys = ["user_id", "role_id"]
)
data class UserRole(
    val userId: Int,
    val roleId: Int
)
```

---

## 3. @Dao Interface

```kotlin
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface UserDao {

    // --- Reads ---
    @Query("SELECT * FROM users ORDER BY last_name ASC")
    suspend fun getAllUsers(): List<User>

    @Query("SELECT * FROM users WHERE id = :userId")
    suspend fun getUserById(userId: Int): User?

    @Query("SELECT * FROM users WHERE email = :email LIMIT 1")
    suspend fun getUserByEmail(email: String): User?

    // Flow-based — emits every time the table changes (see Section 7)
    @Query("SELECT * FROM users ORDER BY last_name ASC")
    fun observeAllUsers(): Flow<List<User>>

    // --- Writes ---
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(user: User): Long           // returns new rowId

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(users: List<User>): List<Long>

    @Update
    suspend fun update(user: User): Int            // returns rows affected

    @Delete
    suspend fun delete(user: User): Int

    @Query("DELETE FROM users WHERE id = :userId")
    suspend fun deleteById(userId: Int): Int

    // --- Upsert (Room 2.5+) ---
    @Upsert
    suspend fun upsert(user: User): Long

    @Upsert
    suspend fun upsertAll(users: List<User>): List<Long>
}
```

---

## 4. @Database Abstract Class

```kotlin
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [User::class, UserRole::class],
    version = 3,
    exportSchema = true                     // REQUIRED for migrations; set false only in tests
)
@TypeConverters(Converters::class)          // register TypeConverters here
abstract class AppDatabase : RoomDatabase() {

    abstract fun userDao(): UserDao
    // Add more DAOs here for each entity group
}
```

---

## 5. TypeConverters for Complex Types

```kotlin
import androidx.room.TypeConverter
import java.util.Date

class Converters {

    // --- Date <-> Long ---
    @TypeConverter
    fun fromTimestamp(value: Long?): Date? = value?.let { Date(it) }

    @TypeConverter
    fun dateToTimestamp(date: Date?): Long? = date?.time

    // --- List<String> <-> String ---
    @TypeConverter
    fun fromStringList(value: List<String>?): String? = value?.joinToString(",")

    @TypeConverter
    fun toStringList(value: String?): List<String>? =
        value?.split(",")?.filter { it.isNotBlank() }

    // --- Enum <-> String ---
    @TypeConverter
    fun fromStatus(status: UserStatus?): String? = status?.name

    @TypeConverter
    fun toStatus(value: String?): UserStatus? =
        value?.let { runCatching { UserStatus.valueOf(it) }.getOrNull() }
}

enum class UserStatus { ACTIVE, INACTIVE, PENDING }
```

> Apply to the `@Database` class: `@TypeConverters(Converters::class)`

---

## 6. Flow-Based Queries

Flow queries re-emit automatically whenever the underlying table is modified.
Collect them in a coroutine scope tied to a lifecycle.

```kotlin
// ViewModel
class UserViewModel(private val db: AppDatabase) : ViewModel() {

    val users: StateFlow<List<User>> = db.userDao()
        .observeAllUsers()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    fun addUser(user: User) {
        viewModelScope.launch {
            db.userDao().upsert(user)
        }
    }
}

// Fragment / Composable
lifecycleScope.launch {
    repeatOnLifecycle(Lifecycle.State.STARTED) {
        viewModel.users.collect { users ->
            adapter.submitList(users)
        }
    }
}
```

---

## 7. Migration Pattern

```kotlin
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

// Migration from version 2 → 3: added "phone" column to users table
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE users ADD COLUMN phone TEXT NOT NULL DEFAULT ''"
        )
    }
}

// Migration from version 1 → 2: renamed column via table rebuild
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE users_new (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                first_name TEXT NOT NULL,
                last_name TEXT NOT NULL,
                email TEXT NOT NULL DEFAULT '',
                created_at INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("""
            INSERT INTO users_new (id, first_name, last_name, email, created_at)
            SELECT id, first_name, last_name, email, created_at FROM users
        """.trimIndent())
        db.execSQL("DROP TABLE users")
        db.execSQL("ALTER TABLE users_new RENAME TO users")
    }
}
```

Register migrations in the builder (see Section 8).

---

## 8. Building the Database — Singleton Pattern

```kotlin
import android.content.Context
import androidx.room.Room

@Database(entities = [User::class], version = 3, exportSchema = true)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun userDao(): UserDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,  // always use applicationContext
                    AppDatabase::class.java,
                    "app_database"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    // .fallbackToDestructiveMigration()  // CAUTION: wipes data (see Gotchas)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
```

**With Hilt (recommended):**

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "app_database")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .build()

    @Provides
    fun provideUserDao(db: AppDatabase): UserDao = db.userDao()
}
```

---

## 9. Testing Pattern

```kotlin
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UserDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var userDao: UserDao

    @Before
    fun setUp() {
        // In-memory database: no migration needed, data discarded after test
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        )
            .allowMainThreadQueries()   // OK in tests only
            .build()
        userDao = db.userDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun insertAndRetrieveUser() = runTest {
        val user = User(firstName = "Ada", lastName = "Lovelace")
        val id = userDao.insert(user)

        val loaded = userDao.getUserById(id.toInt())
        assert(loaded?.firstName == "Ada")
    }

    @Test
    fun upsertUpdatesExistingRecord() = runTest {
        val user = User(firstName = "Grace", lastName = "Hopper")
        val id = userDao.insert(user).toInt()

        val updated = user.copy(id = id, email = "grace@example.com")
        userDao.upsert(updated)

        val loaded = userDao.getUserById(id)
        assert(loaded?.email == "grace@example.com")
    }
}
```

**Migration test:**

```kotlin
import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule

class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    @Test
    fun migrate1To2() {
        helper.createDatabase("test_db", 1).close()
        val db = helper.runMigrationsAndValidate("test_db", 2, true, MIGRATION_1_2)
        db.close()
    }
}
```

---

## 10. Key Gotchas

| Gotcha | Detail |
|---|---|
| **No main-thread queries** | Room throws `IllegalStateException` if you query on the main thread. Always use `suspend` functions or `allowMainThreadQueries()` (tests only). |
| **`exportSchema = true`** | Required for production databases that will ever be migrated. Commit the generated JSON schema files to version control. Set `false` only for in-memory test databases. |
| **`fallbackToDestructiveMigration()`** | Drops and recreates the database when no migration path exists. Use only in dev/debug builds — it silently wipes all user data in production. |
| **`autoGenerate = true` requires default value** | `@PrimaryKey(autoGenerate = true) val id: Int = 0` — the default `0` tells Room to auto-assign the ID on insert. |
| **`@Upsert` vs `@Insert(REPLACE)`** | `@Upsert` uses SQLite `INSERT OR IGNORE` + `UPDATE`, preserving foreign key constraints. `REPLACE` deletes and re-inserts, which can break foreign keys. |
| **Flow on background thread** | Room emits Flow values on a background thread automatically; do not add `flowOn` unless you have a specific reason. |
| **Schema export path** | Configure the KSP argument so generated schemas land in a tracked directory: `ksp { arg("room.schemaLocation", "$projectDir/schemas") }` |
| **`@ColumnInfo(defaultValue)` for new columns** | When adding a non-null column via migration, add a SQLite `DEFAULT` in the migration SQL *and* set `defaultValue` in `@ColumnInfo` so the schema hash matches. |
| **Type safety of `@Query` params** | Room verifies SQL at compile time. A typo in a column name is a build error, not a runtime crash. |
| **Multiple DAOs** | Split DAOs by feature/entity group rather than putting all queries in one interface — improves compile times and separation of concerns. |

---

*Generated: 2026-03-19 | Room 2.8.4 | Context7: `/websites/developer_android_guide`*
