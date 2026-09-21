# KeySync Enhanced Changelog

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
