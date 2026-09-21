# KeySync Enhanced — Change Summary

## Core goal

The enhanced fork keeps the original KeySync runtime model and adds a scalable keyboard-preset layer instead of hard-coding a large number of game-specific layouts into Kotlin.

## What was added

### 1. Human-editable preset schema

`data/mapping/*.json` is the source of truth for bundled keyboard presets.

Benefits:

- Easy to read and edit.
- Git-friendly diffs.
- Preset changes do not require touching Runtime code.
- Internal Kotlin `DraggableItem` names are not part of the public preset format.
- Schema version leaves room for future migrations.

### 2. 22 bundled presets

Categories:

- Universal
- FPS
- MOBA
- MMORPG
- Action
- Racing

Presets intentionally use generic game-control layouts rather than claiming to be official configurations for particular commercial games.

### 3. Normalized coordinates

The default coordinate system is `0.0..1.0` in both axes. This allows the same preset to adapt to different screen resolutions.

### 4. Pixel-coordinate migration path

Fixed-resolution layouts can specify `targetWidth` and `targetHeight`. X/Y coordinates scale independently; button size uses the smaller axis scale to avoid excessive stretching.

### 5. Symbolic key codes

Preset authors can write:

```json
"keyCode": "SPACE"
```

instead of memorizing Android's integer key codes. Android `KEYCODE_*` names and raw `#123` values are also supported.

### 6. Preset validation

Invalid schema versions, malformed metadata, invalid coordinate modes and unsupported item types are reported before a preset is exposed.

### 7. Runtime conflict diagnostics

Duplicate physical key assignments are logged when a mapping is installed. This catches common mistakes such as assigning W separately while also using a WASD group.

### 8. External-keyboard compatibility

The old fixed-size keyboard state arrays were replaced with maps/sets. This avoids array-index failures when an external or vendor-specific key uses a value outside the previous fixed range.

### 9. DataStore compatibility

Existing DataStore JSON reads are more tolerant of additional fields and malformed entries. Invalid button data falls back to an empty mapping rather than crashing the mapping flow.

### 10. Gesture timing

Injected MotionEvents now use the current uptime as `eventTime` while preserving `downTime`, producing a more realistic event timeline for gesture consumers.

### 11. Pointer capacity

The EventManager active-pointer capacity was raised from 10 to 16 to reduce failures when several mapped controls are pressed simultaneously.

### 12. Gesture interpolation

Randomized gesture interpolation now calculates a perpendicular offset from the actual movement direction instead of treating an angle value itself as the offset magnitude.

### 13. In-app preset selection

Long-press a configured game on the main screen to select it. With one game selected, the preset action opens the bundled preset catalog and stores the selected layout for that package.

## What was deliberately not faked

The current KeySync runtime does not natively contain a general-purpose DSL, custom skill wheel engine, edge-recenter FPS state machine, stealth/walk state machine, or arbitrary overlay-image manager. The enhanced preset format therefore does not pretend those features are implemented simply by adding JSON fields.

Those can be added later as independent runtime features without destabilizing the preset system.
