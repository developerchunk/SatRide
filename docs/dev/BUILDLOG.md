# SatRide — Build Log

Append-only. One entry per completed phase (or notable mid-phase event: deviation from plan,
cut-line invocation, hardware verification session, store review outcome, perf numbers).

Format:

```
## YYYY-MM-DD — P<n> <event>
What shipped / what happened. Deviations from the phase file + one-line reason.
Numbers worth keeping (benchmarks, battery, soak stats, review turnaround).
```

---

## 2026-09-04 — Desktop brought level with the phones; sky detail sheet fixed

**The sky detail bug.** Tapping a second satellite on the sky view showed the first one's details.
`SatelliteDetailSheet` resolved its view model with `koinViewModel { parametersOf(noradId) }`
against the *sky tab's* store, which lives for the session — so Koin handed back the first
`SatelliteDetailViewModel` forever and never looked at the parameters again; and because that store
was never cleared, the first satellite also stayed followed for the rest of the session. The sheet
now owns a `ViewModelStore` keyed on the satellite (`key(noradId)` around the sheet), cleared when
it leaves composition, which is also what releases the tracking. The full-screen route was never
affected: each navigation entry is its own store.

**Desktop, previously "a development surface", now runs what the phones run.** Every JVM actual
that said "not a shipping target" has been replaced with a real implementation, and the packaging
made shippable:

- **Firebase on the JVM** (`core:auth` `DesktopFirebase`). GitLive's JVM artifacts sit on
  `firebase-java-sdk`, which needs a host-supplied `FirebasePlatform` for storage and an explicit
  `Firebase.initialize(options)`. Options are parsed from the same `google-services.json` the
  Android build uses — bundled as a resource by `desktopApp` when present, else `~/.satride/` — and
  the session (refresh token) persists in an owner-only properties file, so a desktop sign-in
  survives relaunch. Must run before Koin: `authModule` picks the unavailable repository at
  construction otherwise.
- **Google sign-in** (`feature:auth` jvmMain). The installed-app flow: system browser, one-shot
  `127.0.0.1` listener on an ephemeral port (`jdk.httpserver`), PKCE S256, code exchanged over
  `java.net.http`. Needs a *Desktop app* OAuth client — the Android/web ids in `google-services.json`
  do not accept loopback redirects — read from a git-ignored properties resource, `~/.satride/`, or
  the environment. The button stays disabled with a reason until both Firebase and the client are
  present. PKCE verified against the RFC 7636 test vector.
- **Pass alerts** (`core:notifications` jvmMain). Coroutine timers per armed alert, delivered via
  `osascript display notification` on macOS, `notify-send` on Linux, a tray balloon elsewhere.
  Scope is honestly *while the app is open* — there is no AlarmManager to wake a quit process —
  and `Main` calls `rearmAll()` on launch, the same call as Android's boot receiver.
- **Location** (`core:sensors` jvmMain). IP geolocation (`ipapi.co`, one HTTPS request per launch,
  cached; failures cool down five minutes). Reported as a ±25 km fix, which is the coarse class the
  phones ask for anyway. The Greenwich seeding in `Main` is gone: it wrote a *manual* position,
  which would have outranked the real fix forever. `--observer=` still works.
- **Permissions**: camera unsupported (planetarium), location and notifications granted. **Web
  pages** open in the system browser instead of showing a URL to copy.
- **Packaging**: `SatRide` 1.0.2, bundle id `com.developerstring.satride`, runtime modules from
  `suggestRuntimeModules` plus `jdk.httpserver`, generated `.icns`/`.ico`/`.png` (the macOS one
  inset and rounded to Apple's grid, since jpackage masks nothing), ProGuard explicitly off for
  the JVM release until keep rules exist.

Not changed on purpose: the AR camera layer (no meaningful AR without a device to point) and
haptics (no motor). Verified: `:desktopApp:compileKotlin`, the new JVM tests, the Koin graph
tests, and a `createDistributable` launch of the packaged app.

---

## 2026-08-06 — iOS Firebase, end to end on a real iPhone

The iOS half of P15 has run for the first time. Everything below was done against a physical device
(iPhone, iOS 26.3) rather than a simulator.

**Packages.** `firebase-ios-sdk` 11.15.0 (FirebaseAuth, FirebaseFirestore) and `GoogleSignIn-iOS`
8.0.0 added to `iosApp.xcodeproj` as Swift packages. The project is objectVersion 77 — the format
where folders sync automatically — so the edit was four object types added by hand
(`XCRemoteSwiftPackageReference`, `XCSwiftPackageProductDependency`, `PBXBuildFile`, plus the
`packageReferences`/`packageProductDependencies` arrays), then `xcodebuild
-resolvePackageDependencies` to prove it.

**The bundle-id trap, now defused.** `GoogleService-Info.plist` is registered for
`com.developerstring.satride.SatRide`, and `Config.xcconfig` builds
`com.developerstring.satride.SatRide$(TEAM_ID)`. Those agree **only because `TEAM_ID` is empty**. Setting
it — which is a natural thing to do when you have just found your team id — would silently produce
`...SatRide46C9R239Q5` and Firebase would refuse to initialise, at *runtime*, with nothing looking
wrong at build time. The xcconfig now says so at the top. The signing team is `DEVELOPMENT_TEAM` in
the project, which is a different setting entirely.

**Three things broke, in order:**

1. `cannot find 'IosGoogleSignIn' in scope`. It lives in `feature:auth`, an `implementation`
   dependency of `composeApp`, so it is not in the framework's Objective-C header. Rather than
   exporting a whole feature module to Swift for one property, `MainViewController.kt` now forwards
   it — `setGoogleSignInHandler` and `googleSignInCancelledSentinel`, alongside the existing
   `doInitKoin` and `handleDeepLink`. The framework's Swift-facing surface stays four functions.
2. `cannot convert '(String?, String?) -> KotlinUnit' to '(String?, String?) -> Void'`. A Kotlin
   `-> Unit` *nested inside* another function type bridges as `KotlinUnit`, not `Void`. Adapted at
   the boundary so the wart does not spread into the Swift code.
3. **Crash on tapping sign-in**: `NSInvalidArgumentException: No active configuration. Make sure
   GIDClientID is set in Info.plist.` GoogleSignIn 8 needs the client id before it will present
   anything. Set from `FirebaseApp.app()?.options.clientID` rather than duplicated into Info.plist as
   `GIDClientID` — both values come from `GoogleService-Info.plist`, and reading it at runtime keeps
   that file the single source of truth, so regenerating it in the console cannot leave a stale copy
   behind somewhere else.

Also registered the `REVERSED_CLIENT_ID` URL scheme, without which the OAuth callback has nowhere to
land: the sheet opens, the user picks an account, and nothing ever comes back — no error anywhere.
`onOpenURL` now offers the URL to `GIDSignIn` first (it returns false for anything that is not its
callback, so it is a safe filter) and falls through to the `satride://` deep-link parser.

**State:** the app builds, installs, launches and stays up on the device. The `GIDClientID` crash is
fixed and confirmed gone.

**Still not linked: the Kotlin/Native *test* binaries.** `ld: framework 'FirebaseCore' not found`
on `:core:auth:linkDebugTestIosSimulatorArm64`. Xcode resolves the SPM packages for the *app target*;
Gradle's standalone test binaries link themselves and have no idea those frameworks exist. The right
fix is to keep the Firebase-touching code behind the interfaces `core:auth` already defines, so its
tests never need the SDK — not to hardcode a DerivedData path into `linkerOpts`. Logged as a blocker.

---

## 2026-08-04 — Reviewing PR #1 and #2: what was taken, what was reworked, what was left

Two contributed PRs, reviewed rather than merged. Both contained real fixes; neither was taken as
written.

### PR #1 — location reactivity (taken, reimplemented)

**The bug is real and was ours.** `DefaultLocationProvider` checked `source.isAvailable` *once, at
construction*. Location permission is granted after launch — the sky view asks on arrival — so on a
fresh install the provider never subscribed and the screen sat on "waiting for a location fix" until
the app was killed and reopened. None of this session's location work touched it.

Taken: the retry idea, `SatelliteTracker.requestFix()`, and prompting a fix the moment permission is
granted. Reworked:

- The PR used `runCatching` around the collection. That swallows `CancellationException` too, so
  cancelling the scope leaves the loop spinning instead of stopping. Now `runCatchingCancellable`,
  which this codebase already has for exactly this.
- The PR polls every second **forever**. Someone who declined permanently is the common case, and
  waking once a second for the life of the process — to ask a question that cannot change without a
  trip to Settings — is a battery cost for nothing. Now backs off 1 → 30 s.
- The PR assigned `automatic.value = it` directly, which would have undone this session's
  `isBetterFix` guard and let a coarse network fix overwrite a good GPS one.

The change also broke every test in `LocationProviderTest`, because an indefinite watcher makes
`runTest` wait forever. Fixed properly rather than by weakening the watcher: the tests now build the
provider on `backgroundScope` (cancelled at test end, not waited on) combined with the unconfined
dispatcher (so the init still runs eagerly). Two new tests cover the actual bug — permission granted
after startup, and the back-off.

### PR #2 — desktop overhaul (partly taken)

**Taken:**
- **The back affordance.** `SatRideIcon.Back` plus a navigation icon on non-tab routes. This closes a
  long-standing blocker: Settings and the design gallery were full-screen with no visible way
  out. It mattered more than the log implied — the system back gesture covered it on Android and
  there is no back gesture at all on desktop. Verified on the Pixel.
- **`App(navController = …)`**, so a platform shell can drive navigation itself.
- **Desktop window polish**: centred on the monitor, a minimum size, and Escape as the back gesture.
  Escape is bound with `onPreviewKeyEvent` rather than `onKeyEvent`, so a surface that takes focus
  for its own key handling cannot swallow it.

**Reworked:**
- The PR seeds a default observer (Googleplex) **on every launch** when `--observer` is absent. That
  silently overwrites a position the developer set last time; a setting that quietly resets is worse
  than one that never existed. Now seeded only when nothing is stored, and to Greenwich — a location
  nobody will mistake for a real fix.
- The always-visible top bar would have appeared over the **splash**, which is not a tab, giving a
  loading screen a back button pointing at nothing. `RootScaffold` now takes `showChrome`.

**Not taken (yet):** the keyboard orbiting, magnetic hover and ride-from-anywhere. The ideas are
good — WASD navigation and a Ride action reachable from any screen are both worth having — but the
implementation has two defects that need fixing first, and doing that properly is its own piece of
work rather than something to bury at the end of a long session:

- `onKeyEvent` returns `true` for **every** `KeyDown`, so the Sky surface swallows Escape and Tab.
  That directly breaks the Escape-to-back binding the same PR adds.
- `heldKeys` is never cleared on focus loss, so a key held while the window loses focus stays held
  and the view spins on its own.

Also of note: `SatRideRoute.Play` becomes `data class Play(val rideId: Int?)`. That is the right shape
for ride-from-anywhere, but it interacts with the tab back-stack — `Play(42)` and `Play(null)` are
different route instances under `popUpTo(saveState)/restoreState` — and wants checking rather than
assuming.

### Found while reviewing

The desktop build threw `Default FirebaseApp is not initialized` on every launch. The `runCatching`
in `authModule` only guarded *construction*, and `Firebase.auth` resolves fine — it is the
`authStateChanged` **flow** that throws, asynchronously, on first collection. The app scope's
supervisor kept things alive so it worked, but every launch printed a stack trace that reads like a
crash. Now caught in the flow and logged as one honest info line. Desktop launches clean, and its
splash hands off at **2.49 s ("work finished")**, which is the cap correctly *not* waiting.

---

## 2026-08-04 — Play globe: real time, reach, and lights that are actually lights

Four changes to the Play tab, all reported from looking at it on a device.

**Zoom-out reach.** `MAX_ORBIT_KM` was nine Earth radii — about 57,000 km — which is *less than the
diameter of the geostationary ring*. Anything above low orbit simply could not be framed: GPS sits
at 26,560 km from the centre and the GEO belt at 42,164 km, and there was no way to pull back far
enough to see either. Raised to 30 Earth radii (191,000 km). With a 45° vertical field of view,
framing a radius `r` needs `r / tan(22.5°)` ≈ `2.4 r`, so GEO needs ~102,000 km and the new limit
clears it with room for the Molniya apogees. `GlobeCameraZoomTest` pins it against the orbits that
are actually in the catalog, including a test asserting the *old* limit would have failed — so
"tidying" it back to single digits fails loudly.

**Real and Play are now separate modes.** Previously "1×" was the slowest gear and nothing more.
That is not the same as real: a user who fast-forwards and comes back has a clock that is *offset*
from now, not merely running at the right rate — the globe ticks correctly from the wrong moment,
which looks entirely plausible and is hours wrong. Switching to Real calls `setSpeed(1.0)` **and**
`resync()`, so Real always means now. The gears are hidden in Real rather than greyed out, because
there is only one speed there and it is the real one. Ride works in both, unchanged.

**The rotation and terminator were already right — now that is proven.** `Frames.sunPositionTeme`
is a standard low-precision solar ephemeris and the renderer rotates it into the Earth-fixed frame
by GMST, so the subsolar point was already correct. But it is exactly the kind of claim that can be
plausibly wrong forever: a globe lit from roughly the right direction is completely convincing, and
a sign error or a half-day GMST offset still produces a picture with a day side and a night side.
`SubsolarPointTest` checks it against facts anyone can look up — subsolar latitude ~0° at the March
equinox and ±23.44° at the solstices, never outside the tropics across a whole year, ~0° longitude
at 12:00 UTC, and **15° of westward travel per hour**, which is the one that would catch a rotation
*rate* error. All seven pass.

**City lights are points now, not a texture.** This was the visible complaint: the surface became
vector geometry at the last restyle and the lights did not, so they stayed a 2048×1024 raster
stretched across a 56×80 sphere mesh. Zoomed in they smeared into orange fog beside coastlines that
stayed perfectly sharp — and it is the *mismatch* that reads as broken, since a uniformly soft globe
would just look stylised.

`tools/citylights_earth.py` bakes the same NASA Black Marble image into 12,000 points (60 KB): merge
lights within 0.45° so a large city collapses to one bright dot rather than dozens of overlapping
ones, weight the centroid by brightness so it lands on the city core, and keep the brightest by
*total* emitted light rather than peak, which favours a real conurbation over a single saturated gas
flare. The renderer draws each as a dim halo plus a tight core — one flat circle reads as a sticker,
two read as something glowing — with intensity square-rooted, because brightness in the source spans
four orders of magnitude and a linear map leaves everything but a dozen megacities invisible.

Three culls keep it cheap: horizon, terminator (smoothstepped, so there is no hard edge along it)
and brightness, plus a 4,200-per-frame backstop for a full night hemisphere. Radius is in pixels
with a clamped zoom term, deliberately — scaling it physically would make every city a sub-pixel
speck from far out, where the lights are most of what says the planet is inhabited.

The textured fallback keeps its raster night pass. It only runs when the vector data is missing
entirely, and a soft globe lit softly is coherent in a way a sharp one with soft lights is not.

**Verified on a Pixel 6a:** Real/Play toggle with gears appearing only in Play; the Americas lit at
15:51 UTC, which is where the Sun actually was; and individual cities picked out across the Nile
delta, the Gulf, Iran and into Pakistan and India — discrete dots where there had been a smear.

---

## 2026-08-04 — Scope pivot, accounts, and first run

Three things in one turn: the radio track deferred, an optional Google account with a Firestore
profile, and a splash that keeps a promise. Verified on a **physical Pixel 6a**.

### Scope: v1 is a sky-and-play app

P7–P10 — the RTL-SDR receiver, DSP chain, Doppler correction and decoders — are **deferred to
post-v1**. Nothing is deleted: `core:sdr`, `core:dsp`, `core:audio` and `feature:radio` still build,
each phase file opens with why it is paused and how to resume, and the Radio tab is simply not shown
in the bottom bar (one line in `TopLevelTab.visible` restores it). `docs/plan/06-roadmap.md` is
rewritten around the new scope, and v1.1 is now explicitly "the radio release" rather than a point
update. New phases **P15 (Identity & Profile)** and **P16 (First Run)** slot in after P6; the phase
index gained an *Order* column because P7–P10 keep their numbers so their cross-references survive.

The honest cost is written down in the roadmap: "RF" goes unearned for one release, and the
Doppler-corrected NFM demo — the most technically impressive thing in the plan — does not ship in
v1.

### The account picker: the wrong API looked exactly like the right one

The requirement was "every Google account on the phone, and never auto-login the default". The
obvious implementation — `GetGoogleIdOption` with `setFilterByAuthorizedAccounts(false)` and
`setAutoSelectEnabled(false)` — reads as if it says precisely that, compiles, and **failed on the
device** with `NoCredentialException: No credentials available` on a phone with nine Google accounts
signed in. `GetGoogleIdOption` is the *silent* one-tap retrieval; it returns credentials the user has
already authorised for this app, and when there are none it fails rather than offering a chooser.
The fix is `GetSignInWithGoogleOption`, the explicit button flow, which always presents the full
chooser. On device it listed **all nine accounts**, and offered the chooser again immediately after a
sign-out instead of silently reusing the previous account.

Worth noting because the SHA-1 was checked first and was correct — the failure looks exactly like a
signing-config problem, and is not.

### The eight-second promise was measured from the wrong place

`withTimeoutOrNull(8s)` in the splash's view model passed every unit test and delivered **10.3
seconds** on the device. Cold start to first frame on a Pixel 6a is 2.3 s, and the user's eight
seconds begin when they tap the icon, not when a view model happens to be constructed. Added
`AppStartMark` — a monotonic mark created eagerly as Koin starts — and the cap is now charged against
it. Re-measured on device: **handoff at 8.001 s from launch**.

The cap is a race, not a cancellation: the work keeps running afterwards, because a timeout that
cancelled the catalog refresh would turn a slow start into no data at all.

### Firestore round-trip, proven rather than assumed

Firestore acknowledges a write into its local cache immediately, so a UI that flips to "saved" proves
nothing about the server. The check that does: write a profile, **clear all app data** to destroy the
cache, sign in again, and see whether it comes back. It did — country and referral intact, same uid —
so the write genuinely reached the server and the rules permit it. The same run also proved a
returning user goes straight to their profile rather than being asked to create one again.

### Smaller things the device settled

- The country field used `enabled = false` to make it clickable, which draws at reduced contrast and
  reads as *disabled* — on the one field that opens a whole picker. Now `readOnly` with full
  contrast, a chevron, and the tap picked up from its interaction source (a read-only field consumes
  taps for focus, so a `clickable` wrapper never fires).
- GitLive's Android artifacts declare `com.google.firebase:*` with **no version** and expect a BOM.
  Declared as `implementation` the constraint stops at the module boundary and every consumer fails
  with "Could not find com.google.firebase:firebase-auth:" — an empty version string. It has to be
  `api(project.dependencies.platform(...))`.
- `default_web_client_id` is generated into the *application* module, which a feature module cannot
  import. Resolved by name at runtime, the same trick `core:notifications` uses for its icon.
- Both auth bindings fall back to an unavailable implementation when Firebase cannot start, so the
  desktop build and a pre-Xcode iOS build show a signed-out screen instead of crashing.
- P6's `ViewModelResolutionTest` and `ModuleGraphVerificationTest` both earned their keep again: the
  graph guard caught `StartupCoordinator`'s injected step map immediately.

### The Passes screen was computing from a four-day-old position

Reported by the user looking at the Passes tab and asking whether it was really using *their*
location. It was not, quite.

`AndroidLocationSource` chose **one** provider: GPS whenever fine location was granted, otherwise
network. Indoors — which is where an app is set up — GPS can take minutes or never deliver, so the
app fell back to `lastKnown()`. On the Pixel 6a that cached fix was from the network provider,
100 m accurate, and **four days old**. A whole day of passes was being computed for wherever the
phone had been on Tuesday, and nothing anywhere said so.

Three changes:
- **Register both providers at once.** They interleave; a coarse network fix now lands in seconds
  while GPS converges behind it. Costs nothing at these intervals (5 min / 5 km, 1 min / 50 m).
- **`GeoFix` carries `ageMillis`**, from `elapsedRealtimeNanos` rather than `time` so a wall-clock
  adjustment cannot corrupt it. Without an age there was no way to tell a fresh fix from a stale
  one, prefer the fresher of two providers, or warn anybody.
- **`isBetterFix`** decides which to keep: much fresher wins outright (a stale precise fix is worse
  than a current coarse one — the user may not be there any more), otherwise accuracy decides.
  Seven tests, including the regression where a network fix arriving a second after a good GPS one
  replaced it.

**On device:** accuracy went from the cached 100 m to **±34 m**, and the Passes tab recomputed.

Also added, at the user's request: **a "Your location" panel on the Profile tab** showing the
coordinates, the source and the accuracy — collapsed by default, and opening it asks for a fresh
fix rather than showing the cache. It earns its place because that one number silently decides
whether every AOS, elevation and reminder in the app is right, and until now there was no way for a
user who suspected otherwise to check. Verified on device: `18.8480° N 77.5330° E · From this
device · ±34 m`.

### Still open

- **P15.5 iOS** needs the project owner in Xcode: SPM packages for `firebase-ios-sdk` and
  `GoogleSignIn-iOS`, and the bundle-id mismatch (`GoogleService-Info.plist` is registered for
  `com.developerstring.satride`; `Config.xcconfig` builds `com.developerstring.satride.SatRide$(TEAM_ID)`).
  The Kotlin side is written and compiles — `IosGoogleSignIn.handler` is the seam Swift fills.
- **P16.4 shimmer** exists and is used by the profile screen, but is not yet applied to the catalog
  rows or pass cards, which is where it matters most.
- **P16.5 permissions** not started. Camera and location work via the existing `feature:arsky` code;
  what is missing is `PERMANENTLY_DENIED`, the approximate-vs-precise upgrade path, rationale before
  the prompt, and a settings deep link.

---

## 2026-08-04 — P6 closed on an emulator, and the three bugs that were waiting there

P6 shipped "code complete, `[H]`, needs a device". A Pixel 6a emulator (Android 16) was available
after all, and it took about twenty minutes to find three separate defects that 965 green tests had
no way to see. Recording them because the pattern is the lesson, not the individual bugs.

**1. Crash on launch from a cold deep link.** `NullPointerException` on `GuideFeedback.reset()`.
The guide-request collector was added at the *top* of `SkyViewModel`'s class body, and
`viewModelScope.launch` on `Dispatchers.Main.immediate` runs synchronously when it is already on the
main thread — so a request that was already pending reached `onEvent` during construction, before
the `guide` and `trails` initialisers further down had run. A cold start from a notification always
has a pending request, so this crashed every time it mattered and never once in a test, because the
tests construct the view model with an empty bus. Moved into the existing `init` at the end of the
class, with the reasoning written above it.

**2. The Passes tab could not be opened at all.** `NoDefinitionFoundException: No definition found
for type 'kotlinx.datetime.TimeZone'`. `viewModelOf(::PassesViewModel)` resolves *every* constructor
parameter from the graph — including ones that have default values — so it demanded a binding for a
parameter whose whole point was that it defaults to the system zone. Replaced with an explicit
`viewModel { }` block.

The interesting part is that **two existing guards both missed it**. `AppModuleTest` resolves one
binding and stops. A new `ModuleGraphVerificationTest` using Koin's reflective `verify()` was written
for it, then tested against the bug deliberately reintroduced — and stayed green. So
`ViewModelResolutionTest` now simply *builds* every tab's view model from the real graph, which was
re-run against the reintroduced bug and does fail with the exact production exception. Kept both:
`verify()` earns its place catching genuinely-missing bindings (it found three while being set up),
but only construction proves constructibility.

**3. A notification tap did nothing when the app was on another tab.** The guide bus had the sky
view *consume* the request. The sky view's view model is still alive on the back stack while another
tab is showing, so it consumed the request the instant it was posted; `StateFlow` coalesced the
value back to null before the app shell's `LaunchedEffect` recomposed, and the shell concluded there
was nothing to navigate to. Redesigned: nothing consumes, and each of the two readers remembers the
highest sequence number it has acted on. Handling is now idempotent and independent of who gets
there first. `SkyEvent.GuideCleared` also records the current sequence, so a view model recreated
later cannot undo the user's [Stop].

A fourth, smaller fault came out of the same run: **`MainActivity` needed `launchMode="singleTop"`**.
Without it every `satride://` link started a *second* MainActivity, with two live view models racing
for the same request. This is a genuine prerequisite of the deep link, not a preference, and is
commented as such in the manifest.

**And two things the device showed that no test would have:**

- The timeline was **90% noise**. 450 "passes" in a 24-hour window, most of them geostationary
  objects rendered as `METEOSAT-10 · 14:54 · 2880m` — one window-long "pass" per never-setting
  satellite. `Pass.isAlwaysVisible` exists in the model *for this exact case*, with a comment saying
  the UI would otherwise present nonsense, and the timeline simply did not use it. Now filtered,
  with the empty state careful to call that a quiet sky rather than blaming the user's filters.
- `dumpsys alarm` reported `HAS_SCHEDULE_EXACT_ALARM: count=381` after a few minutes on one screen.
  Both view models read `AlertScheduler.capability` inside their per-second state build, and on
  Android that is two binder calls. Now cached and refreshed only when a permission result can have
  changed it.
- **ARKTIKA-M 1 was listed twice**, fourteen minutes apart, with near-identical durations. A pass
  search clips anything already in progress to the moment the search *started*, and the search runs
  more than once — at startup, and again when the window widens — so each run writes a cache row
  with a later AOS, and the cache is keyed by AOS, so they all survive. This is a P2 cache
  characteristic rather than a P2 bug; the timeline now collapses overlapping passes of one
  satellite and keeps the earliest, which is the least-clipped and therefore truest AOS. Two
  satellites cannot occupy one transit, so overlap is exactly the right test for "the same pass".

**What was verified end to end**

Timeline → [Remind me] → alarm armed at exactly AOS − 10 min → notification fires on the
`pass-alerts` channel, reading *"TEVEL2-9 pass / Pass in 10 min — max 85°, rises SSE"* with the
`ic_notification` satellite icon → tapped from a **different tab** → app switches to Sky showing
*"Guiding to MONITOR-2 (RS39S)"*. Plus: cold-start deep link, warm-start deep link including the
same-satellite-twice case, the notification auto-cancelling its own alert after firing, and a
**full device reboot** after which the pending alarm was re-registered at the identical instant
without the app being opened.

**Worth knowing:** the emulator does **not** grant `SCHEDULE_EXACT_ALARM`, so everything above ran
on the inexact fallback — a 33-second delivery window on a ~40-second alarm — and the UI correctly
said "give or take a few minutes". That is the degraded path working, not the good one; the good one
has still never run. Carried into P12.

**Also cleared:** `feature:play`'s pre-existing ktlint failures. `ktlintFormat` fixed all but nine,
which were `const val OceanR`-style colour channels the rule wants in SCREAMING_SNAKE; renamed by
hand. The globe snapshot test was re-run afterwards and the render is unchanged — the reformat
touched a hand-tuned hot path, so that check was the point. `./gradlew check` is now green on a
clean tree with nothing excluded.

## 2026-08-04 — P6 Passes & Alerts

> **Superseded in part by the entry above.** This entry was written before the phase was walked on
> a device. The `[H]` markings, the "no alarm has ever actually fired" caveat and the closing note
> about `feature:play`'s lint are all resolved there; everything else below still stands.

The planning loop: a timeline of what is coming over, a polar plot of each pass, a reminder before
it starts, and a deep link that lands in the sky view already guiding at the satellite. Code
complete on Android and iOS; the alert and deep-link halves are `[H]` — **no alarm has ever
actually fired**, which is the one thing no test can stand in for.

**What shipped**

- `core:designsystem/PolarPassPlot` — the az/el bowl, in two genuinely different drawings rather
  than one that scales. At 56 dp the compass letters and elevation labels are illegible smudges, so
  the thumbnail drops them and thickens its arc; the shape *is* the message at that size. AOS is a
  hollow ring and LOS a filled dot, which says the direction of travel without an arrowhead that
  would vanish in the timeline.
- `feature:passes` — timeline and detail. Both are pure functions of *now*: a one-second tick is
  folded into the state, so countdowns, the "today" header and "up now" stay honest on a screen the
  user leaves open, rather than going quietly stale.
- `core:notifications` — `AlertScheduler` over `AlarmManager` (Android) and
  `UNCalendarNotificationTrigger` (iOS), with all the reasoning shared and both platform files
  small enough to verify by reading.
- Room schema **v1 → v2**: a `pass_alerts` table and a hand-written migration.
- `satride://sky/detail/{noradId}?guide=true`, parsed once in `core:common`.

**Decisions worth the ink**

- **Alerts copy the pass's numbers instead of referencing it.** A pass is computed, not stored: the
  cache row is pruned the moment its element set is superseded, which is daily. An alert holding a
  row id would go dangling within a day. Copying also means the boot receiver can build the
  notification with no orbit model warmed up and no position fix yet.
- **`AlertReconciliation` is pure and returns data.** SGP4 predictions move as elements refresh —
  seconds a day, occasionally a minute. An alert armed on Monday firing at Monday's time all week is
  the worst kind of bug: the user goes outside and looks at an empty sky, and nothing anywhere
  logs an error. Making the decision a value that can be asserted against is the only way to be
  confident about it. Twelve tests, including the one that matters most: a satellite whose passes
  could *not* be recomputed keeps its alerts, because "we could not check" and "it is not
  happening" are different answers and only one of them should cancel a reminder.
- **`SCHEDULE_EXACT_ALARM`, not `USE_EXACT_ALARM`.** The latter is granted automatically and is
  reserved for alarm-clock and calendar apps; a satellite tracker claiming it is a review
  rejection. So exactness is revocable, and when it is revoked the scheduler falls back to
  `setAndAllowWhileIdle` and the UI *says* the reminder may be a few minutes out. Degraded and
  honest beats precise and rejected.
- **Deep links go through an application-scoped bus (`SkyGuideRequests`), not Navigation Compose's
  own deep-link support.** The target is a *tab*: routing to it as a destination would push a new
  back-stack entry on every notification tap. The bus also solves the ordering problem — on a cold
  start the link is delivered before any screen exists, so the request has to wait until the sky
  view composes. Requests carry a sequence number, because without one two ISS notifications ten
  minutes apart are identical values, `StateFlow` conflates the second away, and the second tap
  does nothing.
- **Pass detail is keyed by satellite + AOS, not by an id.** Same reason as the alerts. It also
  buys the honest "that pass is gone" state when a refresh drops a marginal pass below the user's
  minimum elevation — a real outcome, not an error.
- **Choosing the week *causes* the propagation.** The cache is only filled two days ahead at
  startup, so without that the week would silently end after 48 hours.

**Deviations from the phase file**

- `AlertScheduler` gained `capability: AlertCapability`. The contract as written could not express
  "supported but inexact" or "supported but not yet permitted", and both are ordinary states on a
  modern Android phone. Without it the UI could only hide the feature or lie about it.
- `PassRepository` gained `refreshUpcoming(window)` (it existed on the implementation only). The
  seven-day view needs work done, not merely requested — see above.
- `SatRideRoute.PassDetail(passId: String)` → `PassDetail(noradId, aosEpochMillis)`, and
  `SatelliteDetailEffect.OpenPass` carries the AOS with it. P4.3's next-pass card is a dependent
  call site and was updated in the same change, per the contracts rule.
- The polar plot's acceptance asked for "a screenshot test with a golden fixture arc". Built as an
  **offscreen render test that asserts properties and leaves PNGs behind** (`ImageComposeScene` →
  `composeApp/build/reports/passes/`), matching P5's globe test. A checked-in golden image fails on
  every font-rendering change and teaches everyone to regenerate it without looking, which is worse
  than no test.
- Number formatting (`format`, `asCompassPoint`, `asMegahertz`) moved from `feature:satellites` to
  `core:common`: a notification body has to say "max 78°" the way a catalog row does, and the alert
  scheduler cannot depend on a feature. The `SatClass` → glyph/label/accent mapping is
  **deliberately duplicated** into `feature:passes` instead — features may not depend on features
  and the design system may not know domain types, so seven lines twice is the architecture's
  stated cost. A third feature needing it is the moment to reconsider.
- `core:notifications` owns no Android resources; the status-bar icon is looked up by name
  (`ic_notification`, supplied by `androidApp`) with the launcher icon as a fallback.

**Two bugs found by writing the tests**

- Both view models seeded their upstreams with `stateIn(..., emptyMap())`, which makes `combine`
  emit before the data arrives. The first frame of the timeline showed every satellite as
  "Object 25544", with no radio badge and every grade one step too low, and corrected itself a
  moment later. Left as plain flows, `combine` waits — which is what the loading state is *for*.
- The polar plot's elevation rings used the theme hairline (10% white). The inner two were
  invisible, which turns a scale into a plain disc and removes the "how high is high" reading
  entirely. They are accent-tinted now.

**Numbers**

- 979 test executions green (493 JVM, 486 iosSimulatorArm64); **103 new across P6 and its device
  follow-up** (the JVM-only ones are the migration test, which needs a file on disk, the offscreen
  render test, which needs Skiko, and the two DI-graph guards, which are reflective).
  `./gradlew check` green with nothing excluded; `:androidApp:assembleDebug` and
  `:composeApp:compileKotlinIosSimulatorArm64` both green.
- The v1 → v2 migration is verified against a real v1 database built from the checked-in
  `schemas/1.json`, then opened through the app's own `openDatabase` so Room checks the identity
  hash. That check is the whole point: a column declared in the wrong order fails on the first
  launch after an update, on every device at once, with the user's data behind it.

**Note:** `feature:play` fails `ktlintCheck` on `main` and did so before this phase — see
PROGRESS.md's blockers. `check` was run with that one task excluded; everything else is green.

## 2026-08-02 — ghosts made tappable, and the fixes seen working on device

`hitTest` excluded below-horizon satellites deliberately, with a comment defending it: a ghost must
not steal a tap from a satellite that is genuinely up. That was right when thirty satellites were
tracked and ghosts were rare, and it stopped being right the moment the whole catalog was tracked
— changed a few hours earlier, without revisiting the guard written against exactly that case.

The guard is now a preference rather than an exclusion: above-horizon satellites are searched
first and win outright, ghosts only if nothing up is within the tap radius. A ghost still cannot
take a tap from a satellite that is up even when it is nearer the finger; it is second in line
rather than unreachable. Two consequences that would have made ghost selection feel broken anyway:
trails rebuild on selection change (they are only kept for satellites above the horizon, so
selecting a ghost would animate an arc that did not exist), and a selected satellite draws at full
alpha wherever it is, rather than remaining the faintest thing on a screen that dimmed everything
else to look at it.

**Verified on the Pixel 6a in two frames.** Tapping a ghost opened COMS 1: chip reading *below
horizon*, elevation −73.8°, class *Weather*, altitude 36 067 km at 3.07 km/s — textbook
geostationary, and the exact satellite whose marker had to be qualified to `COMS 1` to keep
COMSTAR out. The frame before it had SWISSCUBE drawing a CubeSat square a few rows above a
correctly-crewed ISS (ZARYA), which is the substring fix and its regression case sitting side by
side on a real screen.

## 2026-08-02 — whole-catalog tracking, and two substring bugs in the classifier

**Below the horizon.** The tracker only propagated what was up, so the renderer's ghosts had
nothing to draw and pointing the phone at the ground showed an empty screen. Now the whole curated
catalog is tracked. Measured on a Pixel 6a, release build, pointed below the horizon with the sky
full of ghosts: **1141 frames in 40 s — 28.5 fps, which is the 30 Hz cap being hit exactly —
0.35% janky, p50 9 ms, p99 14 ms, zero missed vsync**, and **zero background GCs in 75 s** with the
live heap at 6.9 MB against 6.2 MB when thirty satellites were tracked.

Eight times the tracked set for no measurable garbage is the packed `ProjectedPoint` and
`interpolateInto` earning their keep. Frame times are *lower* than the AR figures because
planetarium mode has no camera preview to composite.

The one thing that did need bounding was orbit trails: a few hundred at twenty segments each is
thousands of draw calls a frame. Trails are now built only for satellites above the horizon plus
whichever is selected or guided to — an arc for something on the far side of the Earth was
meaningless anyway.

**`contains` on three-letter markers.** `isCrewed` matched substrings, so against the live SatNOGS
feed **SWISSCUBE**, **AISSAT 1** and **OUTPOST MISSION 1** were all classified as crewed
spacecraft and drawn with the crewed glyph beside the ISS. Single-word markers now match as whole
tokens; only multi-word ones like `CREW DRAGON` still match as substrings.

Reviewing that turned up the same mistake in a marker added hours earlier: bare `COMS` would have
caught **COMSTAR**, a 1970s communications satellite still in a graveyard orbit and still
catalogued. Qualified to `COMS 1`.

Both are only reachable through sources that carry no CelesTrak group — the SatNOGS fallback,
AMSAT, and user imports — which is to say the paths least likely to be exercised until CelesTrak
is down and they are the only ones left.

## 2026-08-02 — P5 verified on a Pixel 6a

Release build. **2413 frames in 40 s of steady-state AR, 1 janky (0.04%), p50 11 ms, p90 13 ms,
p99 15 ms, zero missed vsync**, nothing above 21 ms — comfortably past the 30 fps criterion.
Post-GC live heap flat across 90 s (6208 → 6284 → 6212 KB) with a single background collection in
roughly 5400 frames, which is the flat-heap criterion met.

**The camera FOV chain came out exactly as the arithmetic predicted:** the Pixel 6a's main camera
is ~67° horizontally in landscape, and in portrait the app read **41.7° × 65.6°** from the
hardware — transposed and cropped by the two stages `CameraFovMath` models. Had the raw sensor
figure been used the sky would have been drawn at roughly double scale.

**Physics validated from the app's own readout.** NOAA 15 reported altitude 812 km, speed
7.45 km/s, range 2018 km at 15.6° elevation, footprint 3061 km, downlink 137.350 MHz — a real
810 km sun-synchronous orbit with self-consistent slant-range geometry and its actual DSB
frequency. Its numbers ticked once a second while the glyph moved every frame, which is the
two-rate design visible on one screen. Separately, ES'HAIL 2, METEOSAT-10, METEOSAT-11 and MTG-S1
all appeared clustered in a narrow band to the west — which is what geostationary satellites do,
and a far stronger check on the projection than any single satellite could give.

**Still open**, and stated plainly because they are the parts that matter most: the phone has not
been pointed at a satellite during a pass (indoors cannot settle that); the guide loop's haptics
were never driven end to end; and battery over ten minutes is unmeasured because the phone charges
over the same cable adb uses.

## 2026-08-02 — P5 AR sky, code complete and entirely unverified on hardware

370 tests green on JVM and iosSimulatorArm64 (up from 291), `check` and `assembleDebug` clean. No
Android device was attached at any point while this was written, so **every acceptance criterion
that needs one is open** — see PROGRESS.md. What follows is what the tests did establish.

**The projection is the phase's foundation and was built first**, because the phase file gates
rendering on it. Two things it pins that are easy to get plausibly wrong:

- *A linear angle-to-pixel map is right at the centre and at the edges and wrong in between.* The
  test that catches it puts a target a quarter of the screen above centre and asserts 26.565°, not
  22.5°.
- *The identity quaternion does not face north — it points at the ground.* Written down as
  `SkyProjector.UPRIGHT_FACING_NORTH` with a test asserting identity gives −90° elevation, because
  a test fixture that assumed otherwise would be silently checking the floor.

**The camera's reported field of view is not the field of view on screen.** A 4:3 sensor reporting
72.3° horizontally shows **36.4°** on a 20:9 portrait phone — a factor of two — after the preview
stream's crop and the scale-to-fill crop. Projecting with the raw sensor figure would draw the sky
at double scale, which would look like a broken compass rather than a broken lens. `CameraFovMath`
does the chain in tangent space, where the pinhole model is linear.

**Three bugs the tests found that a device would have hidden:**

- `screenAngleTo` guarded the degenerate "target directly behind you" case with `x == 0.0 && y ==
  0.0`. Floating-point rotation never lands exactly on zero: the components were ~1e-16 and
  `atan2` of two noise values returned 151°. The guide arrow would have spun freely.
- `GuideFeedback` used `Long.MIN_VALUE` as its "no tick yet" sentinel, so `now - lastTick`
  overflowed negative and the first tick never fired. Invisible on a device, where frame
  timestamps are large and positive.
- The planetarium fling integrated velocity with forward Euler, making it **framerate-dependent by
  about 5%** — the same flick landing a degree or two apart on a 60 Hz phone versus a 120 Hz one.
  Replaced with the exact integral of the decaying velocity.

**Allocation budget.** P5.3 is accepted on a flat heap, which drove three decisions: `ProjectedPoint`
is a packed value class rather than a nullable data class (a nullable value class boxes, at 1500
objects/second); `SatelliteTracker` gained `interpolateInto`, because `interpolated` returns a fresh
`Topocentric` per satellite per frame; and label text is measured once and cached per satellite
rather than per frame. None of this is measured yet — that is what the device pass is for.

**Architecture note.** P5.4 says the AR detail sheet reuses P4's detail composable, and
`checkModuleDependencyRules` forbids feature-to-feature dependencies. Resolved by composing through
the app shell: `SkyScreen` takes the sheet as a slot, and the nav host fills it with
`SatelliteDetailSheet`. That is the seam the rule exists to create rather than a workaround for it.

## 2026-08-02 — P4 complete; iOS hardware verification deferred

P4 closed with every acceptance criterion in its phase file met and verified on a Pixel 6a.
**291 tests, green on both JVM and iosSimulatorArm64.**

iOS hardware verification is **deferred** — no device available. iOS is covered by compilation and
by the full simulator test suite, which is identical to the JVM run, so all shared logic is
verified. What is not verified is everything that needs real hardware, and PROGRESS.md now carries
a per-phase list of exactly that rather than a general note, because the debt grows with each
phase and "test iOS later" would not survive contact with a real device session.

The item worth watching is **P3's CoreMotion conversion**. Everything that points at the sky rests
on it, so each further phase adds work on top of an assumption no test can check — the frame
convention is documented and pinned by fourteen tests, but it has never met a magnetometer.

## 2026-08-02 — P4 audit: three gaps found by re-reading the phase file

Asked whether P4 was actually finished, checking each acceptance criterion against the code rather
than against memory found that it was not. I had marked the phase complete prematurely.

- **Import was unreachable from the catalog.** The phase file says "Settings-reachable *and* catalog
  overflow". Settings worked; the overflow did not exist, so `SatellitesEvent.ImportRequested` and
  its effect were dead code — wired end to end and impossible to trigger. There is now an overflow
  menu beside Filters, which is also the better placement: the moment someone wants to import is the
  moment they have searched for a new launch and not found it.
- **Rows showed a class *label*, not a class *glyph*.** `SatelliteGlyph` now draws seven thin-stroke
  silhouettes that differ in outline, not just colour — so they stay readable in red night mode and
  at the few-pixel sizes the P5 sky view will need.
- **The detail header's launch date was always blank.** It read `Satellite.launchDate`, a field
  nothing ever writes, while the launch year sat unused in the international designator. It now
  shows the year.

**Scroll performance, finally measured** rather than deferred. Release build, 260 rows recomposing
at 1 Hz: **3.5% janky, 50th percentile 11 ms, 90th 17 ms** — comfortably 60 fps. The debug build
reads 48% janky at a flat 23–26 ms per frame, which is uniform Compose debug overhead rather than
an algorithmic cost; the tell is the tightness of the distribution. Release signing now falls back
to the debug key, because without a signing config `assembleRelease` produces an unsigned APK and
release performance could not be measured at all. P13 replaces it.

**Import verified end to end on device.** A real GPS BIIR-5 element set pasted in, stored with
`user-import` provenance, searchable, and classified NAVIGATION from its name alone — the
no-group path in the classifier. Two false alarms along the way were my own verification being
wrong, not the app: the soft keyboard silently coalesces runs of repeated digits in `adb input
text` (26407 became 267), and Room runs in WAL mode, so copying `satride.db` without `-wal` reads a
database missing every recent write.

## 2026-08-02 — P4 follow-up: refresh UX, and what a row is for

Device feedback drove four changes, all of them worth more than the code they cost.

**Catalog rows showed a ticking azimuth.** A bearing is only actionable while physically pointing
something, which is the sky view's job, and two numbers changing every second in every row is noise
in a list people scan. Rows now carry the catalog number and the **launch year** — AO-10 reads
1983, INSAT-3DS reads 2024 — parsed from the international designator, which is the only thing the
public catalogs say about a satellite's history. Country was the other suggestion and is not
available: neither CelesTrak's element sets nor SatNOGS publishes it, and a field that cannot be
filled should not be designed in. Elevation stays, because it is why a row is in "Up now" at all.

**A manual refresh took minutes and janked the UI.** Three causes, all real:
1. It ran on `viewModelScope`, which is the main thread — the orchestration alone was dropping
   ~57 frames at a time.
2. It recomputed the entire pass cache, about nine seconds of propagation, while the user watched a
   spinner. Dropped: pass entries are keyed on element epoch, so a refreshed satellite misses the
   cache and recomputes when its passes are next needed.
3. Eleven endpoints were fetched **sequentially**, which on a mobile connection was most of the
   wait. Now four at a time — enough to hide the latency, still a well-behaved client of servers
   run by volunteers. All eleven now complete in under two seconds.

**The refresh died when the user left the screen.** It now belongs to `CatalogRefreshController`,
which lives in an application-scoped coroutine scope. Verified on device: switching to Passes and
back mid-fetch showed it still running at 11/12.

**Progress is real, not an animation.** The endpoint list is known before the first request, so
each step is a source that has actually answered, and the bar opens at 0/12 rather than guessing.
A bar that invents its own motion is worse than none — it says the app is working when it may be
stuck, which is exactly when the user most needs the truth.

Also confirmed, because it was asked and deserved evidence rather than assurance: **there is no
dummy data anywhere**. All 455 element sets on the device trace to a named live source, and the
only hardcoded element set in shipping code is the greyed-out placeholder in the import field that
shows the expected format.

## 2026-08-02 — P4 Satellites Feature complete (and P2/P3 device verification)

The first real product screens: catalog, filters, satellite detail, TLE import. **287 tests, green
on both JVM and iosSimulatorArm64** (up from 244). Verified on a Pixel 6a — which is where most of
the value of this session came from.

### Device verification cleared two earlier blockers, and found three bugs

**P3's compass is verified.** Real GPS fix (18.8468, 77.5443 ±2000 m), compass accuracy HIGH, and
— the check that matters — a phone lying flat face-up reported pointing elevation **−86.6°**. That
is exactly what `CAMERA_FORWARD = -Z` predicts, so the frame convention is confirmed against
hardware rather than against itself. Geostationary satellites read 36,000 km with zero Doppler.

**The tracking-debug screen crashed on open.** `context.display` throws on an application context,
which is what Koin injects, so the first sensor callback killed the app. Now read through
`DisplayManager`, which answers from any context — and cached with a display listener rather than
a binder call per sample at 50 Hz.

**The bundled catalog was never in the APK.** The offline-first path worked on desktop and iOS and
silently did nothing on Android: with the AGP Kotlin-Multiplatform library plugin, Compose
Resources does not fold `commonMain` resources into the Android assets —
`prepareComposeResourcesTaskForAndroidMain` is `NO-SOURCE` — so the file simply was not packaged.
Compose Resources was the wrong mechanism anyway; this is data, not a UI asset. Now loaded per
platform (Android assets / Compose Resources elsewhere), with the file copied into the APK at build
time so there is still exactly one copy in version control. A fresh install now seeds 260
satellites before any network call.

**The staleness banner was permanently red.** It reported the oldest element set held, on the
theory that freshness should reflect the worst data the user might point at. Across 455 objects
there is always one whose operator last published a week ago, so a catalog refreshed thirty seconds
earlier showed "7 days old — pass times may be off". A warning that is always on is a warning
nobody reads, which is what that very type's documentation says it must avoid. It now reports time
since the last successful fetch; per-satellite element age moved to the detail screen, where it is
about one satellite and is actionable.

**A geostationary satellite was shown as being on a 14,400-minute pass.** A satellite that never
sets produces one "pass" spanning the whole search window. `Pass.isAlwaysVisible` now recognises
that, and the card says the satellite holds station instead.

### Two bugs the view-model tests found

1. **The segment label raced the rows.** Segment and row list travelled through `combine` as
   separate streams, so switching segments briefly rendered the new label over the previous
   segment's rows. They now travel as one value, which makes the mixed state unrepresentable.
2. **The error message flashed under corrected input.** `text` and `error` were separate flows, so
   editing emitted new text beside the stale error for one frame. Held together as one value now.

### Notes

- **`TrackingEngine` is now behind a `SatelliteTracker` interface.** Its perpetual one-second loop
  is correct in the app and impossible under a test's virtual clock, which can never reach idle
  while a `while (true)` keeps scheduling delays. Screens want two flows and a follow toggle, so
  that is what they depend on.
- **`viewModelScope` runs on `Dispatchers.Main`**, which on Kotlin/Native is the platform run loop
  and is not driven by the test scheduler. Every view-model test class sets a main dispatcher —
  without it the suite passed on JVM and hung on iOS, which is exactly the asymmetry a KMP project
  cannot afford to discover late.
- Segments are **Up now · Popular · Weather · Ham/FM · Crewed · All**.
  "Ham/FM" requires a downlink SatRide can actually decode: a CubeSat whose only mode we cannot
  demodulate would be a disappointing first listen.
- The `[Tune]` button explains that the radio arrives later rather than sitting disabled — on a
  phone there is no hover, so a disabled control has nowhere to put its reason.
- `RadioLauncher` is injected now with a stub implementation, per P4's contract.
- The ISS events endpoint is **not published yet**, so `IssEventsApi` 404s on every call and the
  banner simply does not appear. That silent-failure path is the one under test, because the file
  is hosted separately and will be missing or unreachable at some point in the app's life.
- Not verified on hardware: the filter sheet and the import screen — the phone dropped off USB
  before those were tapped. Both are unit-tested; logged as a blocker.

## 2026-08-02 — P3 Tracking & Sensors complete

Observer position, device attitude and the app-wide tracking engine. **244 tests, green on both
JVM and iosSimulatorArm64** (up from 183).

| Check | Result | Budget |
|---|---|---|
| 300-satellite propagation sweep | **0.9 ms** | < 50 ms (P3.4) |
| Attitude filter, jitter rejection | **11.7×** | ≥ 10× (P3.3) |
| Attitude filter, 90° step | settles to 1° in **180 ms** | < 400 ms (P3.3) |
| Attitude filter, lag at 60°/s | **2.2°** | — |
| Live engine (London, desktop) | **35 tracked**, physics correct | — |

**Verified against physics, not against ourselves.** The live run tracked seven geostationary
satellites at 38,400–39,000 km, azimuth ~180°, elevation ~32°, range rate ~0 — which is exactly
where the geostationary arc sits from London, at exactly the range and with exactly the (absence
of) Doppler it must have. AO-07 came out at 2,276 km closing at 3.8 km/s, giving 1,848 Hz of shift
at 145.800 MHz. None of that could be right by accident.

**Two bugs, both found by running it rather than by a test:**

1. **Interpolation froze the sky.** `FixPair.at` clamped at the newest fix, on the reasoning that
   extrapolating is a guess. But the render loop always asks for *now*, and now is always later
   than the newest fix — so every glyph held still for a second and then jumped, which is the exact
   stutter the 1 Hz/60 Hz split exists to prevent. It now follows the line forward, bounded to one
   tick past the newest fix. Safe because low-orbit angular motion is under a degree per second and
   smooth, so a second of linear prediction errs by a fraction of a degree — well inside the
   compass error the view already carries, and far better than a visible jump. Caught by printing
   two interpolations 500 ms apart and getting the same number twice.
2. **The observer position never reached the tracking engine.** The settings→location bridge is a
   Koin `single` whose entire job is a side effect, so nothing necessarily resolved it: the pass
   repository computed 1,992 passes from the user's saved position while the tracking engine
   reported "no position". Now `createdAtStart = true`. A lazy singleton that exists for its side
   effects is a bug waiting for the right screen ordering.

**Notes:**

- **`AttitudeFrames.kt` is the single source of truth for axes**, as P3 asked. World frame is ENU
  with azimuth clockwise from north — the same ordering `Frames.ecefToTopocentric` already
  produces, so a satellite's direction and the phone's attitude are directly comparable with no
  adapter. Fourteen tests pin the conventions, including ones that would pass at the cardinal
  points and fail everywhere else (a mirrored sky) — the kind of error that survives eyeballing.
- **CoreMotion's `.xTrueNorthZVertical` is north-west-up, not east-north-up**, despite naming only
  two axes. Missing the quarter turn would rotate the sky by 90° on iOS only, presenting as "the
  compass is broken on iPhone".
- **Declination is applied exactly once**: Android reports magnetic north and needs it, iOS reports
  true north and must not. Applying it twice doubles the error, and since declination is near zero
  across much of Europe and North America that bug would look fine precisely where it was most
  likely to be tried. Tested at Sydney, where declination is over 12°.
- **The smoother adapts on a velocity probe, not on error.** Adapting on the gap between filtered
  and raw cannot work — a degree of noise and a degree of real motion are the same number. A second
  fixed fast filter runs alongside, and the angle by which the raw signal *leads* it separates the
  two: noise is zero-mean so the lead stays around the noise amplitude, while sustained motion
  pulls steadily ahead. An order of magnitude apart, for the cost of one extra slerp. Approach
  informed by Stardroid (Apache-2.0); the formulation and constants are ours.
- **`LocationManager`, not Play Services' fused provider.** Fused gives a better first fix, but it
  would exclude Huawei hardware and de-Googled ROMs — which overlap heavily with the self-hosting,
  radio-tinkering audience this app is for. Coarse accuracy is all a satellite tracker needs, and
  that is not worth an ecosystem dependency.
- **Nothing propagates with no collectors** (`WhileSubscribed`), and all storage access is in the
  30-second sweep so the 1 Hz tick is pure computation. An earlier version queried each element set
  every tick: propagators were reused, but thirty tracked satellites meant thirty database queries a
  second for rows that change twice a day.
- `Quaternion.fromRotationMatrix` uses Shepperd's method rather than the single-branch formula,
  which divides by nearly zero for rotations near 180° — the orientation of a phone held up at the
  sky.
- `Quaternion.angleTo` goes through `acos`, whose derivative is unbounded at zero, so comparing two
  near-identical rotations has a noise floor around 1e-8 rad. Irrelevant physically; it does mean
  assertions on it cannot be tighter than about 1e-6.
- Manifest and Info.plist declare coarse location and motion usage. Compass, accelerometer and
  location are all `required="false"` — a device without them falls back to manual planetarium
  mode, which is a supported way to use SatRide rather than a broken one.
- Not verified: **the compass on real hardware.** Everything above it is tested, but frame
  conventions, the display-rotation remap and the declination sign can all pass and still be
  visibly wrong on a phone. The tracking-debug screen (Settings → Tracking debug) exists for that
  check; `--track` on desktop is its headless twin and covers the position/propagation half.

## 2026-08-02 — P2 Data Layer complete

Satellites, element sets and transmitters flowing from CelesTrak/SatNOGS/ARISS/AMSAT into Room,
behind repositories, with a bundled snapshot so the first launch has a full catalog before any
network call. **183 tests, green on both JVM and iosSimulatorArm64** (up from 62).

Verified live end to end on the desktop target (`--force-sync`, which exists for exactly this):

| Check | Result |
|---|---|
| Sources reached | **10 of 10** — 5 CelesTrak groups, 3 NOAA by catalog number, ARISS, AMSAT |
| Element sets merged | **455** satellites |
| Transmitters | **2,902** active |
| Bundled snapshot seeded | **260** satellites, 501 transmitters, 123 KB |
| Passes precomputed (London, 2 days) | **2,015** |
| Database after a full sync | **8.6 MB** |

**Two bugs, both found by running it rather than by a test:**

1. **The bundle suppressed its own first refresh.** Seeded rows were stamped `fetchedAt = now`, and
   the refresh policy measures from the last fetch — so a release that sat in review for three
   weeks would tell itself the catalog had just been downloaded and skip refreshing for another
   day. Now stamped with the snapshot's `generatedAt`, which makes an old bundle trigger a refresh
   immediately. This is the failure mode the bundle exists to *bridge*, not to hide, and there is
   a test for it now.
2. **The database came out at 46 MB.** Precomputing ten days of passes across the whole curated
   catalog produced ~10,000 passes, and each cached arc was 40 samples of full-precision JSON.
   Fixed on both sides: precompute two days (the timeline's opening horizon — longer windows are
   computed per satellite, on demand) and store arc samples rounded to hundredths of a degree with
   second-resolution timestamps. **46 MB → 8.6 MB.** The discarded precision is a fraction of a
   pixel on a polar plot and finer than the propagator's own accuracy.

**Facts the plan had wrong, corrected against the live APIs:**

- **CelesTrak has retired the `noaa` group.** `GROUP=noaa` returns "not found", and `weather` only
  carries the modern JPSS birds — so NOAA 15, 18 and 19 belong to no group at all. They are also
  the satellites most likely to give a new user their first decoded image (137 MHz APT, receivable
  on the cheapest antenna), so they are fetched individually by catalog number.
- **CelesTrak answers a bad query with HTTP 200 and a line of prose**, so `expectSuccess` never
  fires and the JSON parser reports "expected `[` but found `I`". Detected explicitly now, which is
  how the retired group name was diagnosed at all.
- **`Dispatchers.IO` is not reachable from common code** — it is internal there — so `AppDispatchers`
  needs an expect/actual after all. On Native it is `Dispatchers.Default.limitedParallelism(8)`.
- **DataStore has a stable KMP release**: 1.2.1. The P0 note assumed the newest was `1.3.0-alpha10`.
  A store holding the user's observer position and hardware settings is not the place for an alpha.

**Notes:**

- **CelesTrak GP JSON carries mean elements, not TLE text**, while every other source is text. Rather
  than two internal representations, `TleFormatter` (new, in `core:orbit`) renders JSON back into
  canonical element-set lines. It round-trips all 33 AIAA verification sets byte-for-byte, and —
  the check that matters — **the CelesTrak JSON for the ISS reproduces ARISS's published text
  exactly**: two unrelated endpoints, one expected answer, no reliance on our own code being right.
- Two characters in a TLE carry no information and real catalogs spell them both ways: the exponent
  sign of a zero (`00000+0` and `00000-0` appear in the *same* AIAA element set) and a blank
  ephemeris type. We always emit `-0` and `0`.
- **Merge rule: newest epoch wins, whatever the source.** Priority only breaks ties between equal
  epochs, where the two are the same element set anyway. The other ordering would let a stale
  CelesTrak group overwrite a fresher ARISS ISS set on every single refresh.
- The SatNOGS fallback is fetched **only** when every primary source fails — it is a quarter of a
  megabyte of element sets we normally already have.
- `curated` survives refreshes via a targeted `ON CONFLICT` update. A plain upsert would reset it
  and silently empty the offline starter list the first time the network worked — the exact moment
  everything looks like it is going right.
- The bundled snapshot is shaped as *the data the app would have downloaded* (3-line text plus raw
  SatNOGS records), so seeding runs through the same parsers and mappers as a live fetch. It is
  built by `./gradlew :tools:snapshot:run`, a real module using `core:network`, not a script.
- **`TxMode` now covers the full published taxonomy** (60 entries); all 50 distinct mode strings in
  the live database map, and unknown ones degrade rather than throw. The decoder mapping is
  deliberately conservative — only AFSK/AX.25 and G3RUH claim `TELEMETRY`, because promising more
  means a user sits through a ten-minute pass for nothing.
- Pass-cache key is satellite + observer cell + element epoch. Any of the three changing makes a
  cached result *wrong*, not merely old. Observer cells are geohash-5 (~5 km, under the 10 km the
  ±10 s pass accuracy tolerates) — the plan said geohash-6, which at 1.2 km would recompute every
  time the user crossed town.
- **No background scheduler.** Seed on first launch, refresh on foreground if the interval has
  elapsed. A satellite tracker is opened deliberately, and this cannot be broken by the next change
  to a platform's background-execution rules.
- Not verified: an Android device (none attached) and a genuine airplane-mode cold start. The
  offline path is covered by tests — total network failure, missing bundle, corrupt bundle — and
  seeding provably runs before any network call.

## 2026-08-02 — P1 Orbit Core complete

Pure-Kotlin SGP4/SDP4, coordinate chain, pass prediction, Doppler and WMM. **62 tests, green on
both JVM and iosSimulatorArm64** — the same numbers out of Kotlin/JVM and Kotlin/Native, which
is the property the whole cross-platform claim rests on.

| Check | Result | Target |
|---|---|---|
| AIAA verification vectors | 33 satellites, 762 comparisons, worst **1.15e-7 km** | < 1e-6 km |
| Azimuth / elevation vs Skyfield | < **0.01°** over 362 samples | < 0.1° |
| Pass AOS/LOS vs Skyfield | within **10 s**, peak elevation within 1° | ±10 s, ±1° |
| WMM declination vs NOAA model | worst **4e-7°** | < 0.5° |

**Two real bugs, both found by tests that compare against something other than our own code:**

1. **`julianDateOfYearStart` was half a day out.** This is the one worth remembering. All 762
   SGP4 golden comparisons passed *while the bug was present*, because the verification vectors
   propagate by elapsed minutes from epoch — an absolute epoch error cancels out entirely. It
   only became visible once a satellite was placed against a rotating Earth, where it puts every
   object 180° of longitude away. Caught by comparing deep-space lunar coefficients (driven by
   days since 1949, not by time since epoch) against python-sgp4. `DeepSpaceCoefficientsTest`
   now pins those coefficients so the same class of error fails immediately and obviously.
2. **`Quaternion.rotate` had an extra `v` term**, doubling every rotated vector. Harmless today,
   but this is the function the AR view's attitude handling is built on in P5.

**Notes:**

- Test fixtures are static data files under `core/orbit/src/commonTest/resources` with
  provenance recorded in `FIXTURES.md`. **Python is not a build or runtime dependency** — it was
  used once, offline, to generate `orbit_reference.json`; the generator is checked in beside the
  data so it can be regenerated. Everything the app runs is Kotlin.
- `commonTest` cannot read resources portably, so the fixture directory path is generated into a
  constant by a Gradle task and read through okio, which has a filesystem on every target. The
  alternative — embedding the data as string literals — is impossible anyway: `tcppver.out` is
  140 KB and the JVM caps a string constant at 64 KB.
- **TLE checksums are not enforced by default.** Real catalogs (and the entire published AIAA
  set) contain usable element sets with non-conforming checksums, and catalogs arrive over
  HTTPS where corruption is already caught. Strict mode exists for user-pasted text (P4.4).
- Satellite 33334 is a **documented divergence**: `tcppver.out` still carries a position for it,
  but its perturbed eccentricity is about -118 and every current implementation — ours and
  python-sgp4 — rejects it with error 3. Recorded explicitly rather than silently tolerated.
- Migrated to `kotlin.time.Instant`; `kotlinx.datetime.Instant` is deprecated in 0.8.0.
- `core:orbit` stays pure (model + common + stdlib + datetime only), enforced by
  `checkModuleDependencyRules`, so the P13 `orbitk` extraction remains possible.

## 2026-08-02 — P0 device verification (Pixel 6a, Android 16 / API 36)

First on-device run. Installed, launched, and walked the whole shell: all four tabs, Settings,
the design-system gallery, night mode, and back navigation. **Zero crashes** across the session
(`FATAL EXCEPTION` count: 0).

| Metric | Result | Budget |
|---|---|---|
| Cold start (`am start -W` TotalTime) | **780 ms** | < 2 s (P12.3) |
| Memory, PSS (debug build) | 141 MB | — baseline for P12 |
| Debug APK | 14 MB | — |

**Bug found and fixed — night mode left text white.** In the first run, typography and numerics
stayed light while everything else turned red. Cause: `MaterialTheme` does not derive
`LocalContentColor` from the colour scheme, so any `Text` that does not name a colour keeps
whatever the enclosing `Scaffold` set — meaning re-theming inside the tree silently had no
effect on text. Fixed in `SatRideTheme` by providing `LocalContentColor` from
`colorScheme.onBackground` inside `MaterialTheme`, and re-verified on device. Worth noting this
was invisible to the compiler, to unit tests, and to the desktop smoke run — only a screenshot
caught it, and it would have quietly poisoned every screen built between here and release.

Also fixed: `DesignSystemPreview` is a full-screen route and its content ran under the status
bar — added `safeContentPadding()`.

Not verified: iOS on a physical device (simulator only), and the CI workflow (no remote yet).

## 2026-08-02 — P0 Scaffold complete

Built on the user-supplied KMP starter template in `SatRide/` (Android + iOS + desktop). All 25
modules configure, and Android APK, desktop, iOS framework, JVM tests, iOS simulator tests,
ktlint and the dependency-rules gate all pass. Full verification run: **3m 13s**.

**Deviations from phase-00-scaffold.md, with reasons:**

- **Package is `com.developerstring.satride`**, not the planned `com.duosages.satride` — the
  template and the iOS bundle id already use it. Changing it would churn the Xcode project for
  no benefit.
- **Toolchain bumped**: Gradle 9.1.0 → **9.5.0**, AGP 9.0.1 → **9.3.1**, compileSdk 36 → **37**.
  Forced, not optional: lifecycle 2.11.0 (stable) requires AGP ≥ 9.1.0 and compileSdk ≥ 37, and
  AGP 9.3.1 requires Gradle ≥ 9.5.0. The alternative was pinning lifecycle to a beta. targetSdk
  deliberately stays at 36 (compileSdk affects available APIs; targetSdk opts into runtime
  behaviour changes and is a separate decision).
- **minSdk 24 → 26**, **JVM target 11 → 17** as planned.
- **Framework baseName kept as `Shared`** rather than renamed — the module rename
  `shared` → `composeApp` already required a pbxproj edit; renaming the framework too would
  have added Swift import churn with zero user-visible benefit.
- **Icons are hand-drawn** (`core:designsystem/component/SatRideIcons.kt`). Compose Multiplatform
  stopped publishing `material-icons-core` after 1.7.3, so Material icons are simply not
  available on CMP 1.11. Drawing them with Canvas primitives also matches the thin-stroke
  language in docs/plan/03 better than Material glyphs would.
- **ktlint style is `intellij_idea`, not `ktlint_official`** — the official style forces
  wrapping that reads badly in Compose builder code (`colors =` pushed onto its own line with
  double indent). `function-naming` is disabled because ktlint cannot tell a `@Composable`
  (PascalCase by convention) from a plain function.
- **`withHostTest` deliberately not enabled** in the KMP convention plugin: `commonTest`
  already runs on JVM and iOS, and Android host tests would be a third near-identical JVM run
  across 25 modules. Modules needing Android-specific host tests enable it themselves (P3, P7).
- **Convention plugins read `satride.*` gradle properties** for SDK levels/JVM target rather than
  the version catalog, since precompiled script plugins cannot access `libs` cleanly. Dependency
  versions still live in the catalog, reachable via the generated `LibrariesForLibs` type.

**Notes for later phases:**

- `datastore-preferences-core` has no stable KMP release (latest is `1.3.0-alpha10`). Catalogued
  but unwired — revisit at P2.7 and decide alpha-vs-alternative then.
- Gradle memory is intentionally bounded (`-Xmx3g` Gradle, `-Xmx2g` Kotlin daemon,
  `workers.max=4`): this is a 16 GB machine that runs other Gradle builds concurrently.
- **Dependency-rules gate proven**, not assumed: temporarily added `feature:radio → feature:decode`
  and `core:orbit → core:network`, confirmed both were reported with file and line, then reverted.
- Not verified: on-device runs (no device connected) and the CI workflow (never executed on
  GitHub — no remote yet).

## 2026-08-01 — Planning complete
Plan docs (`docs/plan/01–07`) and execution phases (`docs/dev/P0–P14`) authored. Hackathon
rules verified against the saved official page; iOS USB limitation validated (DriverKit =
iPad-M-series only → rtl_tcp path for iOS); Look4Sat data-source URLs extracted from its
`Sources.kt` and adopted into P2.2/P2.3 with a newest-epoch-wins merge rule. Next: P0.
