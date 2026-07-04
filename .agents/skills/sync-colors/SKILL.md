---
name: sync-colors
description: Verify Canvas constants (GraphColors.kt) are in sync with Compose colors (Color.kt) -- flags any drift between the two color sources
user-invocable: false
metadata:
  filePattern:
    - "**/GraphColors.kt"
    - "**/Color.kt"
---

# Color Sync Verification

Strimma has two parallel color definitions that MUST stay in sync:
- `ui/theme/Color.kt` -- Compose `Color()` values (prefixed `Dark`/`Light`)
- `graph/GraphColors.kt` -- Android Canvas `Int` constants (prefixed `CANVAS_`)

## Check Process

1. Read both files
2. For each CANVAS_ constant in GraphColors.kt, find the corresponding Compose color in Color.kt
3. Convert and compare:
   - Compose: `Color(0xFF56CCF2)` = ARGB hex
   - Canvas: `0xFF56CCF2.toInt()` = same ARGB hex as signed Int
4. Flag any mismatch: name the constant, show both values, and which file needs updating

## Known Mappings

| Canvas (GraphColors.kt) | Compose (Color.kt) | Color |
|---|---|---|
| CANVAS_IN_RANGE | DarkInRange / LightInRange | Cyan #56CCF2 |
| CANVAS_ABOVE_HIGH | DarkAboveHigh / LightAboveHigh | Amber #FFB800 |
| CANVAS_BELOW_LOW | DarkBelowLow / LightBelowLow | Coral #FF4D6A |

If new colors have been added to either file without a counterpart, flag that too.
