# KeySync Enhanced Changelog

## 1.48-android16 — 2026-10-28

### UI

- Added home-screen Device Info card: physical resolution, logical dp size, density, diagonal, orientation, device model and Android version; expandable, one-tap copy.
- The card shows the overlay viewport next to the active profile's layout baseline and flags a mismatch — a mismatch rescales every saved position on load, which is the usual explanation for buttons that "moved by themselves" after rotating or switching devices.

### Overlay controls

- The overlay top status prompt (preset · mode · last input) is now toggleable and persisted globally (`status_bar_visible` in DataStore). Toggle available in Settings, in the edit toolbar and from the notification bar; unlike `keysVisible`, "off" survives a service restart.

### Runtime

- WASD joystick entries are rebuilt once Compose measures the layout (`EventHandler.refreshWasdGeometry()`). `updateKeyMapping()` runs before the first layout pass, so after a rotation or viewport change the joystick origin and sprint offsets were stale; walking survived, sprint did not (its injection point must land outside the actual on-screen centre to cross the game's sprint threshold). Only WASD entries are rewritten; plain keys and the aim contact are untouched.
- Tests: added `StatusBarSettingTest`, `DeviceInfoTest`; extended `LayoutProjectionTest`.

### Release

- Fixed GitHub Actions: removed `android-actions/setup-android@v3`, whose interactive `sdkmanager --licenses` handshake exits 1 on current runners (every push since 1.45 had failed in ~30s). Licenses are now accepted by writing hash files into `$ANDROID_HOME/licenses/`; `actions/setup-java` bumped v4 → v5.
- First tag-based release: `v1.48-android16`.

### 1.46 (backfilled)

- Fixed landscape clipping of left-side buttons by unifying the overlay window coordinate system (commit `a66d2ec`).

## 1.42-enhanced — development snapshot

### Preset system

- Added human-readable Mapping Preset Schema v1.
- Added preset catalog/index.
- Added normalized and pixel coordinate modes.
- Added symbolic Android key-code resolution.
- Added 22 bundled keyboard presets.
- Added universal / FPS / MOBA / MMORPG / Action / Racing categories.
- Added in-app preset selection for installed games.

### Compatibility

- Replaced fixed keyboard-state arrays with maps/sets.
- Added mapping conflict detection.
- Added tolerant DataStore JSON parsing.
- Improved MotionEvent event timestamps.
- Increased active pointer capacity from 10 to 16.
- Improved direction-aware gesture interpolation.

### Scope

This release intentionally focuses on keyboard presets, mapping persistence, and compatibility. It does not claim support for mapping features that the current Runtime does not actually implement.
