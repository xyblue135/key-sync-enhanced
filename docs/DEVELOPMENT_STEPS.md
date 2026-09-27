# Development steps

This repository is an enhanced fork of KeySync. The upstream source archive did
not contain a `.git` directory, so the history below was created from the
supplied source snapshot rather than by importing upstream commits. Upstream
attribution is therefore incomplete — see "Provenance" at the end.

## Actual commit history

Run `git log --oneline` to confirm; this table is descriptive, not generated.

| Commit | Subject | Purpose |
|---|---|---|
| `9c09e4c` | codex修复 | Baseline fixes on top of the imported snapshot |
| `344b3d3` | codex修复 + 工作区未提交改动 | Squashed import; **the remote history was force-overwritten here** |
| `49b3814` | docs: 完善 README | Documentation pass |
| `dff4ba5` | 修复了诸多问题 | Compatibility fixes (multi-touch ordering, pointer namespaces, injection latency) |
| `60d95b6` | 新增键位交换对 + 修复横屏 + 移除切换热键 | Swap pairs, landscape fix, removal of the profile-switch hotkey feature |
| `481b5a3` | 增加随机抖动 | Humanization (position jitter) |

> Earlier revisions of this file listed commits `3a58ccd`, `75ac041`, `4e0264a`,
> … `b44488a`. **Those commits never existed in this repository.** The table above
> is the real history.

## Known history hazard

`344b3d3` force-overwrote the remote history. Any clone made before that commit
has an unrelated ancestry, so `git pull` will not fast-forward. Re-clone rather
than merging.

## Adding a preset

Presets are data, not code — no Kotlin changes are needed.

```bash
git checkout -b preset/my-game
# edit data/mapping/<category>/my-game.json
# edit data/mapping/index.json
git add data/mapping
git commit -m "feat: add my-game keyboard preset"
```

Keep each logical change in its own commit. Do not mix large UI refactors with
preset additions unless the UI change is required by the feature.

`MappingPresetValidator` runs on every bundled preset at load time. Watch Logcat
for `MappingPresetRepository` warnings: a preset that silently drops items is
almost always a schema or key-conflict mistake rather than a runtime bug.

## Verifying a change

```powershell
.\gradlew.bat :app:compileDebugKotlin --console=plain -q
.\gradlew.bat :app:testDebugUnitTest --console=plain
```

Compilation alone is not a regression net: it does not compile the test source
set, and it cannot catch a gesture-ordering or pointer-lifetime regression.
`testDebugUnitTest` covers the pure state machines under `domain/` and the
serialization model under `model/`; the overlay, the service and the input
injection path have no automated coverage and still need a device.

## Provenance

The upstream project is KeySync (Apache-2.0). This fork does not ship a `NOTICE`
file and the per-file copyright headers were not carried over. Anyone
redistributing this fork should restore upstream attribution first.