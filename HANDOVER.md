# Health BB: handover

Read this first when you pick the project up again (human or AI). Base version: **2.1.0** (versionCode 13).

## 1. Product rules (binding)

1. Facts only. No explanatory text in the app, no advice, no health judgements, no source lines ("via Health Connect").
2. No averages anywhere, except a workout's Avg HR.
3. Status is shown with icons only: ▲ ahead · ● on track · ▼ behind · ✓ done. No checkmark on rings.
4. No 3-dot overflow menus. Data checks live only in Settings.
5. Ring numbers sit inside the ring stroke ("34 / 50", the "/ 50" muted; short forms like 16k), never upside down.
6. Every user-facing text is listed and approved before it ships. A new text needs owner approval first.
7. Pixel care: pills, badges and day letters exactly centred. Smooth, detailed, not oversized.
8. Read-only towards Health Connect.
9. Nothing personal in the repo: no real health values, names, devices or screenshots with real data.

## 2. Structure

Kotlin, Jetpack Compose, Glance widgets, SQLite cache, Health Connect (connect-client 1.1.0), WorkManager, DataStore. minSdk 34, compileSdk 37, AGP 9.4, Kotlin 2.4.

| Package | Role |
|---|---|
| `data/` | Health Connect reading (`HcReader`, `Sync`), local SQLite cache (`Cache*`), domain logic (`V2*`, `V4*`, `Insights`, `Pace`, `Calculations`), settings (`Store`, `Prefs`), Apple Health importer (`data/importer`) |
| `render/` | Canvas drawing shared by app tiles and widgets: rings (`render/ring`: `RingPaint`, `RingLabelLayout`, `TileArt`), tiles |
| `ui/` | Compose screens: `home` (tile dashboard), `section` (shared section scaffold, periods, cards), `activity`, `health` (Body, Vitals, metric pages, waist), `allmetrics`, `settings`, `nav`, `theme`, `components`, `debug` (screenshot tour) |
| `widget/` | Glance widgets: *Health BB* (configurable, per-instance config) and *Health BB · Clock* |
| `work/` | `Refresh`: 30-minute periodic sync + widget refresh |
| `tape/` | Renpho smart tape over Bluetooth |

Keep these names stable: the package id `com.boudhealth`, the widget receiver class names and the DataStore name. Changing them makes Android treat it as a new app or orphans placed widgets.

## 3. Data rules

- Days and weeks are local time; weeks run Monday to Sunday.
- Per day, one best source per metric (no double counting).
- Heart points: Fitbit AZM on heart-rate reserve. Fat burn 40–59 % ×1, cardio 60–84 % and peak ≥85 % ×2. Max HR is fitted to Google Health's Active Zone Minutes (default 190; a user-set value wins); measured peaks never move it, 220 − age is info only. Daily goal = ceil(weekly / 7). Weekly target default 150 (WHO); "Suggested for you" = median of the last 12 weeks.
- Workout day: a recorded workout of ≥20 min and ≥10 heart points. Streak counts consecutive workout days.
- HRV is RMSSD only. Impossible readings are hidden (e.g. body fat <5 %, HR <35 bpm).
- Lower-is-better targets (weight, body fat, waist) use a "met" flag; a ring never shows met while above target.
- Calories burned = the total from Google Health as delivered. No calorie goal.

## 4. Build, install, verify

- Build and install: `./gradlew installDebug` on the Mac with the phone connected. The owner's `hb-install.sh` (one folder up, not in git) connects over Wi-Fi and installs.
- Debug screenshot tour (Settings → Data check, debug builds): saves full-page screenshots to `Pictures/HealthBB-QA`; pull with `adb pull /sdcard/Pictures/HealthBB-QA`.
- The owner keeps private verification tools (stub compile harness, data tests, ring render harness) and all plan/QA documents outside this repo.

## 5. Backlog

1. Waist entries typed in the app and the Apple Health import live only in the app's local database, and `allowBackup` is false, so a reinstall or phone reset loses them. Options: write waist to Health Connect (needs a write permission) or add export/import of the local data.
2. Device-check the 2.1.0 redesign (checklist in the private `r3/QA_R3.md`).
3. Battery: measure real use over a few days; if needed, change the refresh interval in `work/Refresh.kt` from 30 to 60 minutes.
4. Optional release build with R8 (APK from ~50 MB to roughly 8–10 MB). Needs keep rules checked for Health Connect and Glance, then a full test round.
5. Clean the 5 "unnecessary non-null assertion" compiler warnings (`ActivitySection.kt:374`, `BodySection.kt:280`, `HealthCharts.kt:219`).
6. Internal names still say "HealthBoud" (theme, receiver base class, Gradle project name). Harmless; rename only with care (see §2).

## 6. History

v1–v6: KWGT prototype, native app, tiles and widgets, Apple Health import, Renpho tape, design system "Mono Ink".
V2.0 (Nov 2026): tabs Home · Activity · Body · Vitals, section dashboards with periods, ring rework, two widgets, month ring calendar, restored detail views and insights, approved texts.
V2.1 (Nov 2026): Activity, Body and Vitals rebuilt as rich dashboards: Activity ring + Apple-style ring calendar, Body weight/composition hero (no rings), Vitals resting HR + four stacked lanes; Trends strip of 6 fact tiles per period (no averages); previous period in grey behind charts; medians removed (Vitals arrows, heart points suggestion). Shared code: `data/R3*.kt`, `ui/section/R3*.kt`, `TrendsStrip.kt`, `RingCalendar.kt`.
