# OBD Dashboards Design

**Date:** 2026-11-05  
**Status:** Approved for implementation planning  
**Scope:** OBD tab — named collections of selected Mode 01 PIDs / UDS DIDs (“dashboards”) with per-profile and global libraries.

## Problem

On the OBD tab, users toggle signals one by one before starting gauges. Selection is in-memory only (`ObdSignalRow.selected` in `UiState`). There is no way to save or restore a set of PIDs/DIDs, so repeated sessions require re-selecting the same signals.

## Goals

- Select signals as today, then save the current selection as a named **dashboard**.
- Quickly apply a dashboard (replace current selection).
- Keep dashboards **per vehicle profile**, plus a **global** library that can be copied into the current profile.
- Full management: create, rename, update active, delete, mark favorite/default for OBD open.
- Persist on disk in the same spirit as vehicle profiles (`filesDir` + kotlinx.serialization JSON).

## Non-goals

- Layout customization (gauge order, sizes, themes).
- Cloud sync or sharing dashboards between devices (local files only).
- Embedding dashboards inside `VehicleProfile` JSON.
- Changing OBD poll / gauge / chart runtime behavior beyond which signals are selected.

## Decisions (from brainstorming)

| Topic | Choice |
|-------|--------|
| Scope | Per profile + global, with copy global → current profile |
| Apply behavior | Replace current selection |
| Management | Full CRUD + update active + favorite/default |
| Storage | JSON files under `filesDir/obd_dashboards/` |

## Data model

### On-disk layout

```text
filesDir/obd_dashboards/
  global/<dashboardId>.json
  by_profile/<profileId>/<dashboardId>.json
```

### Dashboard document

```kotlin
@Serializable
data class ObdDashboardSignalKey(
    val source: String,       // "PID" | "DID"
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
```

- `id`: alphanumeric / underscore / dash (same rules as profile ids).
- Labels are **not** stored; they always come from the live OBD catalog.
- Mapping to UI: `ObdSignalId(source = LiveSource.valueOf(...), id, moduleId)`.

### Preferences (`AppPreferences`)

Store the favorite/default dashboard pointer:

- `obd_favorite_scope`: `"global"` | `"profile"`
- `obd_favorite_profile_id`: profile id when scope is `profile` (nullable/ignored for global)
- `obd_favorite_dashboard_id`: dashboard id

**Active** dashboard (session-only in `UiState`, not prefs): last applied, saved-as, updated, renamed, or copied dashboard in the current context.

**Favorite** (prefs only): applied automatically after each successful `refreshObdCatalog()` when the favorite is global, or profile-scoped and `profileId` matches `selectedProfileId`.

## Repository API

New `ObdDashboardRepository` (mirror `ProfileRepository` patterns):

- `listGlobal(context): List<ObdDashboard>`
- `listForProfile(context, profileId): List<ObdDashboard>`
- `load(context, scope, profileId?, id): ObdDashboard`
- `save(context, scope, profileId?, dashboard)`
- `delete(context, scope, profileId?, id)`
- `copyGlobalToProfile(context, dashboardId, profileId): ObdDashboard` (new id under profile, same name/signals)

Invalid ids / IO failures surface as exceptions; ViewModel maps them to `UiState.error`.

## UI (OBD list mode)

Add a compact **dashboard bar** above the signal list in `TabObd.kt` (`ObdListScreen`):

1. **Selector** — menu with two sections: *This profile* / *Global*. Choosing an entry applies it (replace) and sets it active.
2. **Overflow / actions**:
   - Save current selection as… → dialog: name + scope (profile | global)
   - Update active dashboard (overwrite signals from current selection)
   - Rename active
   - Copy global → this profile (enabled when active is global and a profile is selected)
   - Delete active
   - Set / clear favorite

Empty states:

- No dashboards yet → selector shows “No dashboards” / prompt to save.
- No profile selected → profile-scoped save/list disabled; globals still work.

Missing signals on apply:

- Apply marks every catalog row whose key is in the dashboard; keys not present in `obdSignals` are skipped.
- If any keys were skipped, set `UiState.error` to a short message like `"3 signals not in catalog"` (clear on next successful action as elsewhere).

## ViewModel / state

Extend `UiState`:

- `obdDashboardsProfile: List<ObdDashboardSummary>`
- `obdDashboardsGlobal: List<ObdDashboardSummary>`
- `obdActiveDashboardId: String?`
- `obdActiveDashboardScope: ObdDashboardScope?`
- `obdFavoriteDashboardId` / scope / profileId (or a small nested data class)

`ObdDashboardSummary`: `id`, `name`, `scope`, `signalCount`, `updatedAtMs`.

ViewModel methods:

- `reloadObdDashboards()`
- `applyObdDashboard(id, scope)`
- `saveObdDashboard(name, scope)`
- `updateActiveObdDashboard()`
- `renameActiveObdDashboard(name)`
- `deleteActiveObdDashboard()`
- `copyActiveGlobalDashboardToProfile()`
- `setObdFavoriteDashboard(id, scope)` / `clearObdFavoriteDashboard()`

Lifecycle hooks:

- On ViewModel init and whenever `selectedProfileId` changes: reload dashboard lists; re-read favorite from prefs.
- At the end of a successful `refreshObdCatalog()`: if a valid favorite exists, apply it (replace selection) and set it active. Manual toggles afterward are allowed; they do not clear active id (no dirty indicator in v1).

`toggleObdSignal` does not clear the active dashboard id; overwriting signals requires **Update active**.

## Error handling

| Case | Behavior |
|------|----------|
| Empty name | `error` message; no write |
| Invalid id charset | Same |
| Delete active | Clear active; if it was favorite, clear favorite prefs |
| Favorite for other profile | Do not apply; ignore quietly when current profile differs |
| Corrupt / unreadable file | Skip that file; continue listing; surface one soft error if useful |
| Update with no active | `error`: “No active dashboard” |
| Save with zero selected | `error`: “Select at least one signal” |

## Testing

Unit tests (JVM):

1. JSON round-trip for `ObdDashboard`.
2. Repository list/save/delete paths for global vs `by_profile/<id>/` (use temp `Context` / files dir if existing test patterns allow; otherwise pure path helpers + serialization tests).
3. Apply replace: previous selection cleared; only dashboard keys selected.
4. Copy global → profile produces independent file under profile dir.
5. Favorite prefs read/write.
6. Apply with missing catalog keys: available keys selected; no crash.

Wire existing `ObdSignalId` / `obdSampleKey` helpers; no device/emulator required.

## Files likely touched

- New: `core/ObdDashboard.kt` (model + repository)
- Extend: `core/AppPreferences.kt`
- Extend: `ui/FordDiagViewModel.kt`, `ui/TabObd.kt`, `ui/FordDiagApp.kt` (callbacks)
- New tests under `app/src/test/...`

## Success criteria

- User can save the current OBD selection as a named dashboard (profile or global).
- User can switch dashboards in one tap; selection is fully replaced.
- Favorite dashboard auto-applies after catalog load when valid.
- Global dashboard can be copied into the current vehicle profile.
- Data survives process death via files + prefs.
