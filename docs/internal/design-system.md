# Strimma Design System

Strimma and Springa are sister apps sharing a unified design language. Same foundation,
different accent: Springa uses hot pink (energy, training), Strimma uses cyan (calm,
medical precision).

## Color Foundation

### Dark Theme (Primary)

Purple-warm undertone, shared with Springa.

| Token | Hex | Material 3 Role | Usage |
|-------|-----|-----------------|-------|
| `DarkBg` | `#111018` | `background` | Screen background, deepest layer |
| `DarkSurface` | `#181520` | `surface` | Mid-level surface |
| `DarkSurfaceCard` | `#1E1A2A` | `surfaceVariant` | Cards, graph containers, settings sections |
| `DarkSurfaceBorder` | `#2C2840` | `outlineVariant` | Card borders, dividers |
| `DarkTextPrimary` | `#FFFFFF` | `onBackground`, `onSurface` | Primary text, headings |
| `DarkTextSecondary` | `#A898C0` | `onSurfaceVariant` | Secondary text, timestamps, labels |
| `DarkTextTertiary` | `#6A5F80` | `outline` | Tertiary text, section headers, muted captions |

### Light Theme

| Token | Hex | Usage |
|-------|-----|-------|
| `LightBg` | `#F4F2F7` | Screen background (warm tint) |
| `LightSurface` | `#FAFAFC` | Card backgrounds (off-white, not pure) |
| `LightSurfaceBorder` | `#D5D0E0` | Borders |
| `LightTextPrimary` | `#18151F` | Primary text |
| `LightTextSecondary` | `#6A5F80` | Secondary text |
| `LightTextTertiary` | `#9088A0` | Tertiary text |

### Status Colors (Same Both Themes)

These are Strimma's identity. They never change between themes.

| Token | Hex | Meaning |
|-------|-----|---------|
| `InRange` | `#56CCF2` | Glucose in target range. Strimma's brand accent. |
| `AboveHigh` | `#FFB800` | Glucose above target |
| `BelowLow` | `#FF4D6A` | Glucose below target or critical |
| `Stale` | `#6A5F80` | No data / outdated (matches tertiary text) |

### Semantic Tinted Backgrounds

Solid dark-tinted surfaces for contextual areas (prediction pills, alert backgrounds).

| Token | Hex | Usage |
|-------|-----|-------|
| `TintInRange` | `#152535` | Cyan-tinted dark, in-range context |
| `TintWarning` | `#35280E` | Amber-tinted dark, high glucose alert |
| `TintDanger` | `#351525` | Red-tinted dark, low glucose alert |

### Graph Zone Fills

| Token | Alpha | Usage |
|-------|-------|-------|
| `InRangeZone` | 7% of InRange | Subtle in-range band |
| `ZoneLow` | 12% of BelowLow | Low region background |
| `ZoneHigh` | 12% of AboveHigh | High region background |

### Canvas Colors (Android Graphics)

Mirror of Compose status colors as ARGB ints for Canvas/Paint rendering.
Always update these when status colors change.

| Constant | Value | Compose Equivalent |
|----------|-------|-------------------|
| `CANVAS_IN_RANGE` | `0xFF56CCF2` | `InRange` |
| `CANVAS_HIGH` | `0xFFFFB800` | `AboveHigh` |
| `CANVAS_LOW` | `0xFFFF4D6A` | `BelowLow` |

## Typography

System font (no custom imports). Hierarchy through size and weight.

| Role | Size | Weight | Tracking | Usage |
|------|------|--------|----------|-------|
| Hero | 64.sp | Bold | -- | BG value |
| HeroIcon | 40.sp | Normal | -- | Direction arrow |
| Title | 18.sp | Bold | -- | Screen titles (TopAppBar) |
| SectionHeader | 11.sp | SemiBold | 1.5.sp | Uppercase section labels |
| Body | 14.sp | Normal | -- | Default text, labels |
| BodyEmphasis | 14.sp | SemiBold | -- | Stat labels, setting values |
| Small | 12.sp | Normal | -- | Supporting text, timestamps |
| Caption | 11.sp | Normal | -- | Tertiary info |
| Pill | 13.sp | SemiBold | -- | Prediction pills |
| Stat | 16.sp | Bold | -- | TIR percentages, stat values |

## Shape Scale

Material 3 shapes. Tighter corners than default Material, matching Springa.

| Token | Radius | Usage |
|-------|--------|-------|
| `small` | 8.dp | Pills, small elements |
| `medium` | 12.dp | Major cards, settings sections, graph container |
| `large` | 12.dp | Same as medium (Springa uses 12px for all major surfaces) |

### Specific Shape Usage

| Element | Shape | Notes |
|---------|-------|-------|
| Graph card | 12.dp + 1.dp border | Main data card |
| Minimap card | 8.dp + 1.dp border | Compact secondary card |
| Settings section | 12.dp, no border | Color contrast only |
| Stats card | 12.dp, no border | Color contrast only |
| Prediction pill | rounded-full (100%) | Full pill shape with tinted bg |
| Widget | 16.dp | Android widget standard |

## Spacing

4dp grid. All spacing uses these increments.

| Step | dp | Usage |
|------|-----|-------|
| xs | 4 | Tight gaps, icon spacing |
| sm | 8 | Intra-component spacing |
| md | 12 | Card inner padding, form spacing |
| lg | 16 | Screen horizontal padding, section gaps |
| xl | 24 | Between major settings sections |

### Screen Padding

- Horizontal: 16.dp on all screens
- Between graph and minimap: 8.dp
- Between hero and graph: 16.dp
- Between settings sections: 24.dp

## Elevation Model

Flat. No drop shadows. Visual hierarchy through:

1. Color contrast (surfaceVariant vs background)
2. Borders (1.dp outlineVariant on data cards)
3. Typography weight and size

Settings cards and stats cards use color contrast only (no border).
Graph and minimap cards use subtle 1.dp borders.

## Component Patterns

### Cards

**Data Card (graph, minimap):**
```kotlin
Surface(
    shape = RoundedCornerShape(12.dp),
    color = surfaceVariant,
    border = BorderStroke(1.dp, outlineVariant)
)
```

**Content Card (settings, stats):**
```kotlin
Surface(
    shape = RoundedCornerShape(12.dp),
    color = surfaceVariant
    // no border
)
```

### Prediction Pill

Full pill shape with semantic tinted background:
```kotlin
Surface(
    shape = RoundedCornerShape(100),
    color = TintDanger  // or TintWarning
)
```

### Section Headers

Uppercase, small, tracked:
```kotlin
Text(
    text = title.uppercase(),
    color = outline,          // tertiary text color
    fontSize = 11.sp,
    fontWeight = FontWeight.SemiBold,
    letterSpacing = 1.5.sp
)
```

### Graph Rendering

- In-range zone: 7% opacity cyan fill
- Threshold lines: dashed, semi-transparent status colors
- Data dots: 5f radius, 9f selected
- Prediction: dashed, 40% opacity onSurface
- Tooltip: surfaceVariant @ 95% opacity, 20f corner radius
- Axis labels: secondary text color, 38f canvas text size

## Relationship to Springa

| Shared | Strimma-Specific |
|--------|-----------------|
| Background/surface/border tones (purple-warm) | Cyan brand accent (vs pink) |
| Text color hierarchy (white/lavender/muted) | Status triad: cyan/amber/coral |
| Shape scale (8/12dp) | Hero BG value (64sp medical display) |
| Flat elevation (borders, no shadows) | Prediction pills with tinted backgrounds |
| Section header pattern (uppercase, tracked) | Graph rendering (Canvas + Compose) |
| Spacing grid (4dp increments) | Notification graph bitmap |

When updating the design system, check both apps to ensure the shared
foundation stays aligned.
