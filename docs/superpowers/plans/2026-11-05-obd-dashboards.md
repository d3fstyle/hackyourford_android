# OBD Dashboards Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let users save OBD signal selections as named dashboards (per vehicle profile + global), apply them in one tap (replace), and restore a favorite after catalog refresh.

**Architecture:** JSON files under `filesDir/obd_dashboards/` via a File-rooted store (testable without Robolectric) wrapped by `ObdDashboardRepository`. Favorite pointer in `AppPreferences`. Pure `applyDashboardSelection` for replace/skip logic. ViewModel owns CRUD + auto-apply; `TabObd` adds a dashboard bar.

**Tech Stack:** Kotlin, kotlinx.serialization JSON, Android `filesDir` + SharedPreferences, Jetpack Compose Material3, JUnit4 unit tests.

**Spec:** [docs/superpowers/specs/2026-11-05-obd-dashboards-design.md](../specs/2026-11-05-obd-dashboards-design.md)

## Global Constraints

- Apply always **replaces** selection (never merges).
- Dashboards are **per profile + global**, with **copy global → current profile**.
- Full management: save-as, update active, rename, delete, favorite/default.
- Persist with kotlinx.serialization JSON on disk (same spirit as `ProfileRepository`).
- Dashboard `id` must match `^[a-zA-Z0-9_\\-]+$`.
- Labels are never stored; only signal keys (`source`, `id`, `moduleId?`).
- No dirty indicator; `toggleObdSignal` does not clear active dashboard id.
- Favorite applies after each successful `refreshObdCatalog()` when valid for current profile (or global).
- No Robolectric: filesystem logic must accept a `File` root for JVM tests.
- Do not embed dashboards in `VehicleProfile`.

## File map

| File | Responsibility |
|------|----------------|
| `app/src/main/java/com/forddiag/android/core/ObdDashboard.kt` | Model, scope enum, File store, Context repository, id helpers, key mapping |
| `app/src/main/java/com/forddiag/android/core/AppPreferences.kt` | Favorite pointer get/set/clear |
| `app/src/main/java/com/forddiag/android/ui/ObdDashboardSelection.kt` | Pure apply-selection helper (testable) |
| `app/src/main/java/com/forddiag/android/ui/FordDiagViewModel.kt` | State fields + dashboard methods + favorite after catalog |
| `app/src/main/java/com/forddiag/android/ui/TabObd.kt` | Dashboard bar UI + dialogs |
| `app/src/main/java/com/forddiag/android/ui/FordDiagApp.kt` | Wire new ObdScreen callbacks |
| `app/src/test/java/com/forddiag/android/core/ObdDashboardTest.kt` | Serialization + store + copy |
| `app/src/test/java/com/forddiag/android/core/AppPreferencesFavoriteTest.kt` | Favorite encode/decode helpers if extracted; else skip and cover via pointer data class tests in ObdDashboardTest |
| `app/src/test/java/com/forddiag/android/ui/ObdDashboardSelectionTest.kt` | Replace + missing keys |

---

### Task 1: Model, serialization, File store

**Files:**
- Create: `app/src/main/java/com/forddiag/android/core/ObdDashboard.kt`
- Test: `app/src/test/java/com/forddiag/android/core/ObdDashboardTest.kt`

**Interfaces:**
- Consumes: none
- Produces:
  - `enum class ObdDashboardScope { GLOBAL, PROFILE }`
  - `@Serializable data class ObdDashboardSignalKey(val source: String, val id: Int, val moduleId: String? = null)`
  - `@Serializable data class ObdDashboard(val id: String, val name: String, val signals: List<ObdDashboardSignalKey>, val updatedAtMs: Long)`
  - `object ObdDashboardIds` with `fun isValidId(id: String): Boolean`, `fun newId(name: String): String`
  - `class ObdDashboardStore(private val rootDir: File)` with list/load/save/delete/copyGlobalToProfile
  - `object ObdDashboardRepository` wrapping `Context.filesDir` → `File(filesDir, "obd_dashboards")`

- [ ] **Step 1: Write the failing test**

Create `ObdDashboardTest.kt`:

```kotlin
package com.forddiag.android.core

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ObdDashboardTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    @Test
    fun roundTripJson() {
        val dash = ObdDashboard(
            id = "engine",
            name = "Engine",
            signals = listOf(
                ObdDashboardSignalKey("PID", 0x0C, null),
                ObdDashboardSignalKey("DID", 0xF190, "PCM"),
            ),
            updatedAtMs = 1000L,
        )
        val text = json.encodeToString(ObdDashboard.serializer(), dash)
        val back = json.decodeFromString(ObdDashboard.serializer(), text)
        assertEquals(dash, back)
    }

    @Test
    fun rejectsInvalidId() {
        assertFalse(ObdDashboardIds.isValidId(""))
        assertFalse(ObdDashboardIds.isValidId("bad id"))
        assertTrue(ObdDashboardIds.isValidId("engine_01"))
    }

    @Test
    fun saveListDeleteGlobalAndProfile() {
        val store = ObdDashboardStore(tmp.root)
        val dash = ObdDashboard(
            id = "engine",
            name = "Engine",
            signals = listOf(ObdDashboardSignalKey("PID", 0x0C, null)),
            updatedAtMs = 1L,
        )
        store.save(ObdDashboardScope.GLOBAL, profileId = null, dash)
        assertEquals(listOf(dash), store.listGlobal())
        assertTrue(store.listForProfile("focus_2017_eu").isEmpty())

        store.save(ObdDashboardScope.PROFILE, profileId = "focus_2017_eu", dash.copy(id = "fuel", name = "Fuel"))
        assertEquals(1, store.listForProfile("focus_2017_eu").size)

        assertTrue(store.delete(ObdDashboardScope.GLOBAL, null, "engine"))
        assertTrue(store.listGlobal().isEmpty())
    }

    @Test
    fun copyGlobalToProfileCreatesIndependentFile() {
        val store = ObdDashboardStore(tmp.root)
        val dash = ObdDashboard(
            id = "engine",
            name = "Engine",
            signals = listOf(ObdDashboardSignalKey("PID", 0x0D, null)),
            updatedAtMs = 1L,
        )
        store.save(ObdDashboardScope.GLOBAL, null, dash)
        val copy = store.copyGlobalToProfile("engine", "focus_2017_eu")
        assertEquals("Engine", copy.name)
        assertEquals(dash.signals, copy.signals)
        assertTrue(ObdDashboardIds.isValidId(copy.id))
        // Mutate profile copy must not change global
        store.save(
            ObdDashboardScope.PROFILE,
            "focus_2017_eu",
            copy.copy(signals = emptyList(), updatedAtMs = 2L),
        )
        assertEquals(1, store.load(ObdDashboardScope.GLOBAL, null, "engine").signals.size)
    }

    @Test
    fun saveRequiresProfileIdForProfileScope() {
        val store = ObdDashboardStore(tmp.root)
        val dash = ObdDashboard("x", "X", emptyList(), 1L)
        assertThrows(IllegalArgumentException::class.java) {
            store.save(ObdDashboardScope.PROFILE, profileId = null, dash)
        }
    }

    @Test
    fun corruptFileSkippedInList() {
        val store = ObdDashboardStore(tmp.root)
        val globalDir = File(tmp.root, "global").also { it.mkdirs() }
        File(globalDir, "broken.json").writeText("{not json")
        store.save(
            ObdDashboardScope.GLOBAL,
            null,
            ObdDashboard("ok", "OK", listOf(ObdDashboardSignalKey("PID", 1, null)), 1L),
        )
        assertEquals(1, store.listGlobal().size)
    }
}
```

Add `import java.io.File` to the test file.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests com.forddiag.android.core.ObdDashboardTest`

Expected: FAIL (types / class not found)

- [ ] **Step 3: Write minimal implementation**

Create `ObdDashboard.kt`:

```kotlin
package com.forddiag.android.core

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Locale
import java.util.UUID

enum class ObdDashboardScope { GLOBAL, PROFILE }

@Serializable
data class ObdDashboardSignalKey(
    val source: String,
    val id: Int,
    val moduleId: String? = null,
)

@Serializable
data class ObdDashboard(
    val id: String,
    val name: String,
    val signals: List<ObdDashboardSignalKey>,
    val updatedAtMs: Long,
)

object ObdDashboardIds {
    private val idRegex = Regex("^[a-zA-Z0-9_\\-]+$")

    fun isValidId(id: String): Boolean = id.isNotBlank() && id.matches(idRegex)

    /** Slug from name + short suffix; always valid id. */
    fun newId(name: String): String {
        val slug = name.trim().lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .ifBlank { "dashboard" }
            .take(32)
        val suffix = UUID.randomUUID().toString().take(8)
        val candidate = "${slug}_$suffix"
        require(isValidId(candidate))
        return candidate
    }
}

class ObdDashboardStore(private val rootDir: File) {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    private fun globalDir(): File = File(rootDir, "global").also { it.mkdirs() }

    private fun profileDir(profileId: String): File =
        File(File(rootDir, "by_profile"), profileId).also { it.mkdirs() }

    private fun fileFor(scope: ObdDashboardScope, profileId: String?, id: String): File =
        when (scope) {
            ObdDashboardScope.GLOBAL -> File(globalDir(), "$id.json")
            ObdDashboardScope.PROFILE -> {
                require(!profileId.isNullOrBlank()) { "profileId required for PROFILE scope" }
                File(profileDir(profileId), "$id.json")
            }
        }

    fun listGlobal(): List<ObdDashboard> = listDir(globalDir())

    fun listForProfile(profileId: String): List<ObdDashboard> {
        if (profileId.isBlank()) return emptyList()
        return listDir(profileDir(profileId))
    }

    private fun listDir(dir: File): List<ObdDashboard> {
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles { f -> f.extension.equals("json", ignoreCase = true) }
            ?.sortedBy { it.name }
            ?.mapNotNull { file ->
                runCatching {
                    json.decodeFromString(ObdDashboard.serializer(), file.readText())
                }.getOrNull()
            }
            .orEmpty()
    }

    fun load(scope: ObdDashboardScope, profileId: String?, id: String): ObdDashboard {
        val file = fileFor(scope, profileId, id)
        require(file.isFile) { "dashboard not found: $id" }
        return json.decodeFromString(ObdDashboard.serializer(), file.readText())
    }

    fun save(scope: ObdDashboardScope, profileId: String?, dashboard: ObdDashboard) {
        require(ObdDashboardIds.isValidId(dashboard.id)) { "invalid dashboard id" }
        require(dashboard.name.isNotBlank()) { "name required" }
        val file = fileFor(scope, profileId, dashboard.id)
        file.writeText(json.encodeToString(ObdDashboard.serializer(), dashboard))
    }

    fun delete(scope: ObdDashboardScope, profileId: String?, id: String): Boolean {
        val file = fileFor(scope, profileId, id)
        if (!file.isFile) return false
        return file.delete()
    }

    fun copyGlobalToProfile(dashboardId: String, profileId: String): ObdDashboard {
        val src = load(ObdDashboardScope.GLOBAL, null, dashboardId)
        val copy = src.copy(
            id = ObdDashboardIds.newId(src.name),
            updatedAtMs = System.currentTimeMillis(),
        )
        save(ObdDashboardScope.PROFILE, profileId, copy)
        return copy
    }
}

object ObdDashboardRepository {
    private fun store(context: Context): ObdDashboardStore =
        ObdDashboardStore(File(context.filesDir, "obd_dashboards"))

    fun listGlobal(context: Context) = store(context).listGlobal()
    fun listForProfile(context: Context, profileId: String) =
        store(context).listForProfile(profileId)
    fun load(context: Context, scope: ObdDashboardScope, profileId: String?, id: String) =
        store(context).load(scope, profileId, id)
    fun save(context: Context, scope: ObdDashboardScope, profileId: String?, dashboard: ObdDashboard) =
        store(context).save(scope, profileId, dashboard)
    fun delete(context: Context, scope: ObdDashboardScope, profileId: String?, id: String) =
        store(context).delete(scope, profileId, id)
    fun copyGlobalToProfile(context: Context, dashboardId: String, profileId: String) =
        store(context).copyGlobalToProfile(dashboardId, profileId)
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests com.forddiag.android.core.ObdDashboardTest`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/forddiag/android/core/ObdDashboard.kt \
  app/src/test/java/com/forddiag/android/core/ObdDashboardTest.kt
git commit -m "$(cat <<'EOF'
feat(obd): add dashboard model and file store

EOF
)"
```

---

### Task 2: Apply-selection helper

**Files:**
- Create: `app/src/main/java/com/forddiag/android/ui/ObdDashboardSelection.kt`
- Test: `app/src/test/java/com/forddiag/android/ui/ObdDashboardSelectionTest.kt`

**Interfaces:**
- Consumes: `ObdSignalId`, `ObdSignalRow`, `LiveSource` (existing); `ObdDashboardSignalKey` (Task 1)
- Produces:
  - `data class ObdDashboardApplyResult(val rows: List<ObdSignalRow>, val skipped: Int)`
  - `fun applyDashboardSelection(rows: List<ObdSignalRow>, keys: Set<ObdSignalId>): ObdDashboardApplyResult`
  - `fun ObdDashboardSignalKey.toObdSignalId(): ObdSignalId?`
  - `fun ObdSignalId.toDashboardKey(): ObdDashboardSignalKey`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.forddiag.android.ui

import com.forddiag.android.core.LiveSource
import com.forddiag.android.core.ObdDashboardSignalKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ObdDashboardSelectionTest {
    private fun row(source: LiveSource, id: Int, moduleId: String? = null, selected: Boolean = false) =
        ObdSignalRow(
            key = ObdSignalId(source, id, moduleId),
            label = "L",
            unit = null,
            selected = selected,
        )

    @Test
    fun replaceClearsPreviousSelection() {
        val rows = listOf(
            row(LiveSource.PID, 0x0C, selected = true),
            row(LiveSource.PID, 0x0D, selected = true),
            row(LiveSource.PID, 0x05, selected = false),
        )
        val keys = setOf(ObdSignalId(LiveSource.PID, 0x05, null))
        val result = applyDashboardSelection(rows, keys)
        assertEquals(0, result.skipped)
        assertFalse(result.rows[0].selected)
        assertFalse(result.rows[1].selected)
        assertTrue(result.rows[2].selected)
    }

    @Test
    fun missingKeysCountedAsSkipped() {
        val rows = listOf(row(LiveSource.PID, 0x0C))
        val keys = setOf(
            ObdSignalId(LiveSource.PID, 0x0C, null),
            ObdSignalId(LiveSource.PID, 0x0D, null),
            ObdSignalId(LiveSource.DID, 0xF190, "PCM"),
        )
        val result = applyDashboardSelection(rows, keys)
        assertEquals(2, result.skipped)
        assertTrue(result.rows.single().selected)
    }

    @Test
    fun keyMappingRoundTrip() {
        val pid = ObdSignalId(LiveSource.PID, 0x0C, null)
        assertEquals(ObdDashboardSignalKey("PID", 0x0C, null), pid.toDashboardKey())
        assertEquals(pid, ObdDashboardSignalKey("PID", 0x0C, null).toObdSignalId())

        val did = ObdSignalId(LiveSource.DID, 0xF190, "PCM")
        assertEquals(did, did.toDashboardKey().toObdSignalId())
        assertNull(ObdDashboardSignalKey("NOPE", 1, null).toObdSignalId())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests com.forddiag.android.ui.ObdDashboardSelectionTest`

Expected: FAIL (unresolved references)

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.forddiag.android.ui

import com.forddiag.android.core.LiveSource
import com.forddiag.android.core.ObdDashboardSignalKey

data class ObdDashboardApplyResult(
    val rows: List<ObdSignalRow>,
    val skipped: Int,
)

fun ObdSignalId.toDashboardKey(): ObdDashboardSignalKey =
    ObdDashboardSignalKey(source = source.name, id = id, moduleId = moduleId)

fun ObdDashboardSignalKey.toObdSignalId(): ObdSignalId? {
    val src = runCatching { LiveSource.valueOf(source) }.getOrNull() ?: return null
    return ObdSignalId(source = src, id = id, moduleId = moduleId)
}

fun applyDashboardSelection(
    rows: List<ObdSignalRow>,
    keys: Set<ObdSignalId>,
): ObdDashboardApplyResult {
    val present = rows.map { it.key }.toSet()
    val skipped = keys.count { it !in present }
    val next = rows.map { row -> row.copy(selected = row.key in keys) }
    return ObdDashboardApplyResult(rows = next, skipped = skipped)
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests com.forddiag.android.ui.ObdDashboardSelectionTest`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/forddiag/android/ui/ObdDashboardSelection.kt \
  app/src/test/java/com/forddiag/android/ui/ObdDashboardSelectionTest.kt
git commit -m "$(cat <<'EOF'
feat(obd): add dashboard selection apply helper

EOF
)"
```

---

### Task 3: Favorite preferences

**Files:**
- Modify: `app/src/main/java/com/forddiag/android/core/AppPreferences.kt`
- Create: `app/src/test/java/com/forddiag/android/core/ObdFavoritePointerTest.kt`

**Interfaces:**
- Consumes: `ObdDashboardScope`
- Produces:
  - `data class ObdFavoritePointer(val scope: ObdDashboardScope, val dashboardId: String, val profileId: String?)`
  - `fun ObdFavoritePointer.isApplicable(selectedProfileId: String?): Boolean`
  - `AppPreferences.getObdFavorite` / `setObdFavorite` / `clearObdFavorite`

Because SharedPreferences needs Android Context (no Robolectric), test only the pure `isApplicable` + round-trip of scope string helpers in this task. Prefs methods are thin wrappers exercised later manually.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.forddiag.android.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ObdFavoritePointerTest {
    @Test
    fun globalAlwaysApplicable() {
        val fav = ObdFavoritePointer(ObdDashboardScope.GLOBAL, "engine", profileId = null)
        assertTrue(fav.isApplicable(null))
        assertTrue(fav.isApplicable("focus_2017_eu"))
    }

    @Test
    fun profileOnlyWhenIdsMatch() {
        val fav = ObdFavoritePointer(ObdDashboardScope.PROFILE, "fuel", profileId = "focus_2017_eu")
        assertTrue(fav.isApplicable("focus_2017_eu"))
        assertFalse(fav.isApplicable("other"))
        assertFalse(fav.isApplicable(null))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests com.forddiag.android.core.ObdFavoritePointerTest`

Expected: FAIL

- [ ] **Step 3: Implement pointer + AppPreferences**

Add to `ObdDashboard.kt` (or keep next to prefs — prefer `ObdDashboard.kt` for the data class):

```kotlin
data class ObdFavoritePointer(
    val scope: ObdDashboardScope,
    val dashboardId: String,
    val profileId: String? = null,
) {
    fun isApplicable(selectedProfileId: String?): Boolean = when (scope) {
        ObdDashboardScope.GLOBAL -> true
        ObdDashboardScope.PROFILE ->
            !profileId.isNullOrBlank() && profileId == selectedProfileId
    }
}
```

Replace/extend `AppPreferences.kt`:

```kotlin
package com.forddiag.android.core

import android.content.Context

object AppPreferences {
    private const val PREFS_NAME = "forddiag_prefs"
    private const val KEY_SELECTED_PROFILE_ID = "selected_profile_id"
    private const val KEY_OBD_FAV_SCOPE = "obd_favorite_scope"
    private const val KEY_OBD_FAV_PROFILE_ID = "obd_favorite_profile_id"
    private const val KEY_OBD_FAV_DASHBOARD_ID = "obd_favorite_dashboard_id"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getSelectedProfileId(context: Context): String? =
        prefs(context).getString(KEY_SELECTED_PROFILE_ID, null)

    fun setSelectedProfileId(context: Context, id: String) {
        prefs(context).edit().putString(KEY_SELECTED_PROFILE_ID, id).apply()
    }

    fun getObdFavorite(context: Context): ObdFavoritePointer? {
        val sp = prefs(context)
        val id = sp.getString(KEY_OBD_FAV_DASHBOARD_ID, null) ?: return null
        val scopeRaw = sp.getString(KEY_OBD_FAV_SCOPE, null) ?: return null
        val scope = runCatching { ObdDashboardScope.valueOf(scopeRaw) }.getOrNull() ?: return null
        val profileId = sp.getString(KEY_OBD_FAV_PROFILE_ID, null)
        return ObdFavoritePointer(scope, id, profileId)
    }

    fun setObdFavorite(context: Context, favorite: ObdFavoritePointer) {
        prefs(context).edit()
            .putString(KEY_OBD_FAV_SCOPE, favorite.scope.name)
            .putString(KEY_OBD_FAV_DASHBOARD_ID, favorite.dashboardId)
            .putString(KEY_OBD_FAV_PROFILE_ID, favorite.profileId)
            .apply()
    }

    fun clearObdFavorite(context: Context) {
        prefs(context).edit()
            .remove(KEY_OBD_FAV_SCOPE)
            .remove(KEY_OBD_FAV_DASHBOARD_ID)
            .remove(KEY_OBD_FAV_PROFILE_ID)
            .apply()
    }
}
```

- [ ] **Step 4: Run tests**

Run: `./gradlew :app:testDebugUnitTest --tests com.forddiag.android.core.ObdFavoritePointerTest`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/forddiag/android/core/ObdDashboard.kt \
  app/src/main/java/com/forddiag/android/core/AppPreferences.kt \
  app/src/test/java/com/forddiag/android/core/ObdFavoritePointerTest.kt
git commit -m "$(cat <<'EOF'
feat(obd): persist favorite dashboard pointer

EOF
)"
```

---

### Task 4: ViewModel state and dashboard operations

**Files:**
- Modify: `app/src/main/java/com/forddiag/android/ui/FordDiagViewModel.kt`
- Test: extend `ObdDashboardSelectionTest` is enough for apply; add thin pure helpers tests only if you extract more. Manual verification checklist at end of task.

**Interfaces:**
- Consumes: `ObdDashboardRepository`, `AppPreferences`, `applyDashboardSelection`, key mappers
- Produces UI state fields and methods listed in the spec

- [ ] **Step 1: Add UI types near `ObdSignalRow` in `FordDiagViewModel.kt`**

```kotlin
data class ObdDashboardSummary(
    val id: String,
    val name: String,
    val scope: ObdDashboardScope,
    val signalCount: Int,
    val updatedAtMs: Long,
)

fun ObdDashboard.toSummary(scope: ObdDashboardScope) = ObdDashboardSummary(
    id = id,
    name = name,
    scope = scope,
    signalCount = signals.size,
    updatedAtMs = updatedAtMs,
)
```

Extend `UiState` with:

```kotlin
val obdDashboardsProfile: List<ObdDashboardSummary> = emptyList(),
val obdDashboardsGlobal: List<ObdDashboardSummary> = emptyList(),
val obdActiveDashboardId: String? = null,
val obdActiveDashboardScope: ObdDashboardScope? = null,
val obdFavorite: ObdFavoritePointer? = null,
```

- [ ] **Step 2: Implement reload + favorite read**

```kotlin
fun reloadObdDashboards() {
    val app = getApplication<Application>()
    val profileId = _state.value.selectedProfileId
    val global = ObdDashboardRepository.listGlobal(app)
        .map { it.toSummary(ObdDashboardScope.GLOBAL) }
    val profile = profileId?.let { ObdDashboardRepository.listForProfile(app, it) }
        ?.map { it.toSummary(ObdDashboardScope.PROFILE) }
        .orEmpty()
    val favorite = AppPreferences.getObdFavorite(app)
    _state.update {
        it.copy(
            obdDashboardsGlobal = global,
            obdDashboardsProfile = profile,
            obdFavorite = favorite,
        )
    }
}
```

Call `reloadObdDashboards()` from `init` after profiles load, and at the end of `selectProfile` / wherever `selectedProfileId` is set (including `saveProfile` path). When profile changes, clear `obdActiveDashboardId` / scope if the previous active was `PROFILE` (global active may remain).

- [ ] **Step 3: Implement apply / save / update / rename / delete / copy / favorite**

Core apply:

```kotlin
fun applyObdDashboard(id: String, scope: ObdDashboardScope) {
    val app = getApplication<Application>()
    val profileId = _state.value.selectedProfileId
    try {
        val dash = ObdDashboardRepository.load(
            app, scope,
            profileId = if (scope == ObdDashboardScope.PROFILE) profileId else null,
            id = id,
        )
        val keys = dash.signals.mapNotNull { it.toObdSignalId() }.toSet()
        val result = applyDashboardSelection(_state.value.obdSignals, keys)
        val err = if (result.skipped > 0) {
            "${result.skipped} signals not in catalog"
        } else {
            null
        }
        _state.update {
            it.copy(
                obdSignals = result.rows,
                obdActiveDashboardId = dash.id,
                obdActiveDashboardScope = scope,
                error = err,
            )
        }
    } catch (e: Exception) {
        _state.update { it.copy(error = e.message ?: e.toString()) }
    }
}
```

`saveObdDashboard(name: String, scope: ObdDashboardScope)`:
- Reject blank name → error
- Reject zero selected → `"Select at least one signal"`
- PROFILE requires `selectedProfileId`
- Build `ObdDashboard(id = ObdDashboardIds.newId(name), name, signals = selected.map { it.key.toDashboardKey() }, updatedAtMs = now)`
- Save, reload lists, set active

`updateActiveObdDashboard()`:
- Require active id+scope; else `"No active dashboard"`
- Overwrite signals from current selection (allow empty? Spec: save requires ≥1 — same for update: require ≥1)

`renameActiveObdDashboard(name: String)`: load, copy with new name (keep id), save, reload

`deleteActiveObdDashboard()`: delete file; if favorite matches, `clearObdFavorite`; clear active; reload

`copyActiveGlobalDashboardToProfile()`: require active GLOBAL + selectedProfileId; copy; set active to PROFILE copy; reload

`setObdFavoriteDashboard(id, scope)` / `clearObdFavoriteDashboard()`: write prefs with profileId when PROFILE; update `obdFavorite` in state

- [ ] **Step 4: Hook favorite into `refreshObdCatalog`**

After successful row build:

```kotlin
val favorite = AppPreferences.getObdFavorite(getApplication())
val profileId = _state.value.selectedProfileId
var rows = buildObdCatalogRows(...)
var activeId: String? = null
var activeScope: ObdDashboardScope? = null
var applyError: String? = null
if (favorite != null && favorite.isApplicable(profileId)) {
    val dash = runCatching {
        ObdDashboardRepository.load(
            getApplication(),
            favorite.scope,
            if (favorite.scope == ObdDashboardScope.PROFILE) favorite.profileId else null,
            favorite.dashboardId,
        )
    }.getOrNull()
    if (dash != null) {
        val keys = dash.signals.mapNotNull { it.toObdSignalId() }.toSet()
        val applied = applyDashboardSelection(rows, keys)
        rows = applied.rows
        activeId = dash.id
        activeScope = favorite.scope
        if (applied.skipped > 0) {
            applyError = "${applied.skipped} signals not in catalog"
        }
    }
}
_state.update {
    it.copy(
        obdSignals = rows,
        obdCatalogLoaded = true,
        obdActiveDashboardId = activeId ?: it.obdActiveDashboardId,
        obdActiveDashboardScope = activeScope ?: it.obdActiveDashboardScope,
        obdFavorite = favorite,
        busy = null,
        error = applyError,
    )
}
```

Preserve prior selection when there is **no** applicable favorite (keep existing `selected` set behavior).

- [ ] **Step 5: Compile check**

Run: `./gradlew :app:compileDebugKotlin`

Expected: SUCCESS (UI not wired yet — methods unused is fine)

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/forddiag/android/ui/FordDiagViewModel.kt
git commit -m "$(cat <<'EOF'
feat(obd): wire dashboard CRUD and favorite apply in ViewModel

EOF
)"
```

---

### Task 5: OBD tab UI + App wiring

**Files:**
- Modify: `app/src/main/java/com/forddiag/android/ui/TabObd.kt`
- Modify: `app/src/main/java/com/forddiag/android/ui/FordDiagApp.kt`

**Interfaces:**
- Consumes: ViewModel methods from Task 4; `UiState` dashboard fields
- Produces: dashboard bar UX per spec

- [ ] **Step 1: Extend `ObdScreen` / `ObdListScreen` signatures**

Add callbacks:

```kotlin
onApplyDashboard: (String, ObdDashboardScope) -> Unit,
onSaveDashboard: (name: String, scope: ObdDashboardScope) -> Unit,
onUpdateActiveDashboard: () -> Unit,
onRenameActiveDashboard: (String) -> Unit,
onDeleteActiveDashboard: () -> Unit,
onCopyGlobalDashboardToProfile: () -> Unit,
onSetFavoriteDashboard: (String, ObdDashboardScope) -> Unit,
onClearFavoriteDashboard: () -> Unit,
```

- [ ] **Step 2: Build `ObdDashboardBar` composable**

Place above the record CSV row (or above the signal list) in `ObdListScreen`:

- `Text` showing active name or `"Dashboards"`
- `OutlinedButton` / `DropdownMenu`: sections **This profile** / **Global** listing `state.obdDashboardsProfile` and `state.obdDashboardsGlobal`; on click → `onApplyDashboard`
- `IconButton` (MoreVert) with menu items:
  - Save selection as… → opens dialog (name field + Radio profile/global; profile disabled if `selectedProfileId == null`)
  - Update active (enabled if active != null)
  - Rename active → dialog
  - Copy global → this profile (enabled if active scope GLOBAL && profile selected)
  - Delete active
  - Set as favorite / Clear favorite (toggle based on whether `state.obdFavorite` matches active)

Match existing colors (`Accent`, `Line`, `TextPrimary`, `TextMuted`) and rounded borders like the CSV row.

Keep dialogs local with `remember { mutableStateOf }` for name text and which dialog is open (`none` / `save` / `rename`).

- [ ] **Step 3: Wire in `FordDiagApp.kt`**

```kotlin
ObdScreen(
    state = state,
    onRefresh = viewModel::refreshObdCatalog,
    onToggleRecord = viewModel::setObdRecordEnabled,
    onToggleSignal = viewModel::toggleObdSignal,
    onStartGauges = viewModel::startObdGauges,
    onStartChart = viewModel::startObdChart,
    onCloseFullscreen = viewModel::stopObdFullscreen,
    onConsumePendingCsv = viewModel::consumeObdPendingCsv,
    onApplyDashboard = viewModel::applyObdDashboard,
    onSaveDashboard = viewModel::saveObdDashboard,
    onUpdateActiveDashboard = viewModel::updateActiveObdDashboard,
    onRenameActiveDashboard = viewModel::renameActiveObdDashboard,
    onDeleteActiveDashboard = viewModel::deleteActiveObdDashboard,
    onCopyGlobalDashboardToProfile = viewModel::copyActiveGlobalDashboardToProfile,
    onSetFavoriteDashboard = viewModel::setObdFavoriteDashboard,
    onClearFavoriteDashboard = viewModel::clearObdFavoriteDashboard,
)
```

- [ ] **Step 4: Build + unit tests**

Run:

```bash
./gradlew :app:compileDebugKotlin :app:testDebugUnitTest --tests com.forddiag.android.core.ObdDashboardTest --tests com.forddiag.android.core.ObdFavoritePointerTest --tests com.forddiag.android.ui.ObdDashboardSelectionTest
```

Expected: SUCCESS / all PASS

- [ ] **Step 5: Manual smoke (device/emulator)**

1. Connect → OBD → Refresh → select 2–3 PIDs → Save as “Engine” (profile).
2. Clear selection by applying empty? (cannot — apply another or toggle off) → Apply “Engine” → only those selected.
3. Save same set as Global “Engine G” → Copy global → profile → appears under This profile.
4. Set favorite → Refresh catalog → selection restored.
5. Delete active → cleared from menu; favorite cleared if it matched.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/forddiag/android/ui/TabObd.kt \
  app/src/main/java/com/forddiag/android/ui/FordDiagApp.kt
git commit -m "$(cat <<'EOF'
feat(obd): add dashboard picker and management UI

EOF
)"
```

---

## Spec coverage checklist

| Spec requirement | Task |
|------------------|------|
| JSON on disk global + by_profile | 1 |
| Model fields id/name/signals/updatedAtMs | 1 |
| Repository list/load/save/delete/copy | 1 |
| Apply replace | 2, 4 |
| Skip missing keys + error message | 2, 4 |
| Favorite prefs | 3 |
| Favorite apply after catalog refresh | 4 |
| CRUD + rename + update active + copy | 4, 5 |
| UI selector + overflow actions | 5 |
| Unit tests listed in spec | 1, 2, 3 |

## Plan self-review notes

- No TBD/placeholder steps remaining after inline fixes.
- Types consistent: `ObdDashboardScope`, `ObdFavoritePointer`, `ObdDashboardSummary`, `applyDashboardSelection`.
- AppPreferences Context I/O not unit-tested (no Robolectric); covered by pure `isApplicable` + manual smoke.
