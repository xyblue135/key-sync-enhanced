# Enhanced Development Steps

This repository was prepared as a clean enhanced fork from the supplied KeySync source archive. The archive did not contain the upstream `.git` directory, so a new Git history was created from the uploaded source snapshot rather than inventing the upstream commit history.

## Commit sequence

| Step | Commit | Purpose |
|---:|---|---|
| 0001 | `3a58ccd` | Import the supplied upstream source as baseline |
| 0002 | `75ac041` | Add human-readable Mapping Preset Schema and catalog |
| 0003 | `4e0264a` | Validate preset schema before loading |
| 0004 | `9690d07` | Harden external keyboard state and detect mapping conflicts |
| 0005 | `2b25c21` | Expose preset catalog and persist preset mappings |
| 0006 | `ba9fd5f` | Fix JSON decoder shadowing in DataStore reads |
| 0007 | `c3c677b` | Add in-app preset selection |
| 0008 | `196fd74` | Expand universal/FPS/MOBA/MMORPG/Action/Racing presets |
| 0009 | `5335cc2` | Improve MotionEvent gesture timing and pointer capacity |
| 0010 | `28fa516` | Improve direction-aware gesture interpolation |
| 0011 | `c475192` | Document preset format and compatibility |
| 0012 | `2e8c95c` | Inject preset repository into ViewModel |
| 0013 | `d6352d5` | Scale pixel presets independently on X/Y |
| 0014 | `b44488a` | Load preset catalog off the main thread |

## Recommended workflow for future presets

```bash
git checkout -b preset/my-game
# edit data/mapping/<category>/my-game.json
# edit data/mapping/index.json

git add data/mapping
git commit -m "feat: add my-game keyboard preset"
```

Keep each logical change in its own commit. Do not mix large UI refactors with preset additions unless the UI change is required by the feature.
