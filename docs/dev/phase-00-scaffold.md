# P0 — Project Scaffold

**Objective:** a building, launching, navigable KMP/CMP skeleton with all modules, DI, theming,
and CI in place — so every later phase only adds code, never structure.
**Depends on:** nothing. **Duration target:** 2–3 days.

## Tasks

### P0.1 — Gradle workspace & version catalog
- Initialize repo (git), `.gitignore` (Android Studio + Xcode + Kotlin), `README.md` stub.
- `settings.gradle.kts` including every module from the graph in
  `docs/plan/02-architecture.md` (create all now, even if near-empty — empty modules with a
  single placeholder file are fine).
- `gradle/libs.versions.toml` with: kotlin, compose-multiplatform, agp, coroutines,
  serialization, datetime, ktor, koin (+koin-compose-viewmodel), room, sqlite-bundled,
  datastore, androidx-lifecycle (viewmodel-compose multiplatform), jetbrains-navigation-compose,
  coil3, kermit, turbine (test), kotest-assertions (test).
- Convention plugins in `build-logic/`: `satride.kmp.library` (targets: androidTarget, iosArm64,
  iosSimulatorArm64; explicitApi for flagged modules), `satride.cmp.feature` (adds compose deps +
  viewmodel + koin), `satride.android.app`.
- **Accept:** `./gradlew projects` lists all modules; sync clean.

### P0.2 — Module skeletons + dependency rules
- Each `core:*` and `feature:*` module: `build.gradle.kts` via convention plugin, package-info
  placeholder in `commonMain`.
- Add a simple dependency-rules verification (Gradle task or `konsist` test in `composeApp`):
  feature↛feature, `core:orbit`/`core:dsp` → only `core:model`+`core:common`.
- **Accept:** rules task fails when a forbidden edge is added (prove with a temporary edge,
  then remove it).

### P0.3 — App entries
- `composeApp`: `App()` root composable, Koin `startKoin` wiring (`appModule` aggregating
  per-module Koin modules — each core/feature module exposes `val xxxModule: Module`).
- `androidApp`: single-Activity (`MainActivity` → `setContent { App() }`), Application class
  with Koin init, min SDK 26, target latest.
- `iosApp`: Xcode project, SwiftUI `ContentView` hosting `ComposeUIViewController { App() }`,
  Koin init via `KoinKt.doInitKoin()`.
- **Accept:** "Hello SatRide" renders on Android emulator + iOS simulator.

### P0.4 — Design system seed (`core:designsystem`)
- Color tokens from `docs/plan/03-ux-design.md` ("Night Ops": base `#070B14`, cyan/amber/magenta
  accents, night-red palette), dark-only `SatRideTheme`, typography (UI font + tabular-mono
  numeric style `NumericText` composable), spacing scale, shape scale.
- Motion spec object: `MotionSpec.fast(120ms) / standard(240ms) / celebratory(500ms)` spring
  specs.
- Core components: `GlassPanel`, `AccentChip`, `SatRideButton`, `SectionHeader` (minimal set;
  grow later).
- **Accept:** a `DesignPreview` screen (debug-only route) renders all tokens/components.

### P0.5 — Navigation shell
- JetBrains Navigation Compose in `composeApp`: `@Serializable` route objects
  (`SkyRoute`, `SatellitesRoute`, `SatelliteDetailRoute(id)`, `PassesRoute`, `PassDetailRoute(id)`,
  `RadioRoute`, `DecodeRoute(mode)`, `GalleryRoute`, `SettingsRoute`,
  `OnboardingRoute`).
- Root scaffold: bottom bar **Sky · Satellites · Passes · Radio** with placeholder screens;
  settings via top-bar action; onboarding gate stub (DataStore flag, hardcoded false for now).
- **Accept:** all tabs navigate on both platforms; deep-link string pattern documented for
  `satride://sky/detail/{satId}` (implementation lands in P6).

### P0.6 — CI + quality gates
- GitHub Actions: on PR/main → `./gradlew check`, `:composeApp:assembleDebug`, iOS
  `compileKotlinIosSimulatorArm64` (full xcodebuild optional/nightly to keep CI fast).
- ktlint or detekt-formatting wired into `check`.
- **Accept:** CI green on the scaffold commit.

## Exit checklist
- [ ] All modules exist, compile, dependency rules enforced
- [ ] App launches on Android + iOS with 4-tab shell and theme applied
- [ ] Koin resolves a sample dependency end-to-end in both apps
- [ ] CI green; PROGRESS.md + BUILDLOG.md updated
