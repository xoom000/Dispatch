# Security Crypto — Quick Reference

**What:** Encrypt files and SharedPreferences using Android Keystore. No key management hassle.
**Context7 ID:** `/websites/developer_android_guide` (query: "security crypto EncryptedSharedPreferences")
**Source:** https://developer.android.com/topic/security/data
**Version:** 1.1.0-alpha06

## Gradle Dependencies

```kotlin
dependencies {
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    // For Kotlin extensions:
    implementation("androidx.security:security-crypto-ktx:1.1.0-alpha06")
}
```

## EncryptedSharedPreferences

```kotlin
val masterKey = MasterKey.Builder(context)
    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
    .build()

val securePrefs = EncryptedSharedPreferences.create(
    context,
    "secure_prefs",           // file name
    masterKey,
    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
)

// Use like normal SharedPreferences
securePrefs.edit()
    .putString("api_key", "sk-abc123")
    .putString("device_token", fcmToken)
    .apply()

val apiKey = securePrefs.getString("api_key", null)
```

## EncryptedFile

```kotlin
val masterKey = MasterKey.Builder(context)
    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
    .build()

val encryptedFile = EncryptedFile.Builder(
    context,
    File(context.filesDir, "secret.txt"),
    masterKey,
    EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
).build()

// Write
encryptedFile.openFileOutput().use { output ->
    output.write("sensitive data".toByteArray())
}

// Read
encryptedFile.openFileInput().use { input ->
    val data = input.readBytes().toString(Charsets.UTF_8)
}
```

## Key Gotchas

- **Alpha version** — 1.1.0-alpha06 has been "alpha" for years. It's stable enough for production, Google just hasn't promoted it
- **MasterKey** — Created once, stored in Android Keystore. Survives app updates but NOT factory reset
- **Performance** — Encryption adds overhead. Don't use for frequently-accessed, non-sensitive data. Regular SharedPreferences or DataStore is faster
- **File already exists** — `EncryptedFile.Builder` throws if the file already exists and wasn't created by EncryptedFile. Delete first if migrating
- **Backup exclusion** — Add encrypted files to `android:fullBackupContent` exclude rules. Restoring to a different device with different Keystore = unreadable
- **API 23+ only** — Uses Android Keystore which requires API 23 (Android 6.0)
- **Thread safety** — EncryptedSharedPreferences is thread-safe (same as regular SharedPreferences). EncryptedFile is NOT — synchronize access yourself
