# Play Store listing — SatRide

Everything the Console asks for, written out so it can be pasted rather than improvised at
submission time. Character limits are Play's and are counted; the counts in brackets are actual.

---

## Store presence

**App name** (30 max)

```
SatRide: 3D Satellite Tracker
```
[29] — "Satellite Tracker" is the phrase people actually search, and it stays intact as an exact
substring. "3D" is the differentiator: it points at the thing that makes this app worth choosing —
you can play with the satellites, not just read a list of them. "SatRide" alone is a brand nobody
is looking for yet, and a title spent on an unknown brand earns no organic installs at all.

**Short description** (80 max)

```
Play with satellites on a live 3D Earth. Track passes, ISS alerts and AR sky.
```
[77] — this is the line under the name in search results and it converts harder than the full
description ever will, because most people never expand that. It leads with the hook rather than
the category: every competitor is a tracker, and none of them let you play. "Track" still carries
the search intent for anyone scanning results for a tracker.

**Full description** (4000 max)

```
Thousands of satellites are flying over your head right now. SatRide is where you go and play with
them — spin a live 3D Earth, pick one out of the swarm, and ride along on its shoulder while the
planet turns underneath you.

PLAY WITH SATELLITES
A live 3D Earth with hundreds of objects in their real orbits, every one of them where it actually
is this second. Drag to spin the planet, pinch to fly in, tap a satellite to lock onto it — then
ride along from its point of view and watch the world roll past beneath you. This is the part
people show their friends.

POINT AT THE SKY
Hold your phone up and the satellites are there, drawn over your camera view where they actually
are. Tap one to follow it, and SatRide guides you to it with a gentle nudge as you get closer.
Prefer not to use the camera? Turn it off and the same sky is drawn on a dark background.

KNOW WHEN TO GO OUTSIDE
Every pass over your location, on a timeline: when it rises, how high it climbs, how long it
lasts. Set a reminder and SatRide nudges you a few minutes before, so you are outside and looking
up when it happens. A bright ISS pass is worth setting an alarm for, and now you do not have to.

EVERY SATELLITE, LISTED
Browse the catalog by what is up now, the popular ones, weather satellites, amateur radio
satellites or crewed missions. Every entry has its orbit, its next pass, its downlink frequencies
and where it is this second.

WORKS WITHOUT SIGNAL
A full satellite catalog ships inside the app. It works on a hillside with no bars — no signal
needed to know what is overhead. When you do have a connection it quietly refreshes the orbital
data so predictions stay sharp.

REAL DATA, REAL ORBITS
Positions are propagated with SGP4 from public orbital element sets published by CelesTrak, with
transmitter details from the SatNOGS database. These are the same models professional tracking
software uses. SatRide is for observation and enjoyment, not navigation or anything
safety-critical.

BUILT TO RESPECT YOU
No account required — everything works signed out. Your location is used on your phone and is
never uploaded. The camera draws the sky behind the satellites and records nothing: no photo, no
video, ever. No ads, no purchases, no advertising identifier — SatRide is free.

WHAT YOU NEED
Location, so SatRide knows which satellites are above you — or enter a position by hand instead.
Camera, only for the augmented sky view. Notifications, only for pass reminders you ask for. Each
one is explained before it is asked for, each is optional, and each can be changed later.

Privacy policy: https://satride.developerstring.com/privacy
Questions: support@developerstring.com
```

### Why it is shaped like this

The first two lines carry the install decision — Play truncates the description behind a "read
more" and most people never open it. Everything above the fold is the promise and the proof.

Headers are in caps rather than markdown because the Console renders plain text, and a wall of
unbroken prose is what most tracker listings look like.

The permissions paragraph exists on purpose. Location and camera are the two things that make
people hesitate on the install button, and answering them in the listing — before the install —
converts better than leaving it to the permission dialog.

---

## Graphics checklist

| Asset | Spec | Status |
| --- | --- | --- |
| App icon | 512×512 PNG, 32-bit, no alpha | Generate from `tools/icon` — same art as the launcher icon |
| Feature graphic | 1024×500 PNG/JPG, no alpha | **Needed.** Earth limb along the bottom, satellite upper right, "See every satellite above you" |
| Phone screenshots | 2–8, 16:9 or 9:16, min 1080 px | **Needed** — see the shot list below |
| 7" and 10" tablet screenshots | 2–8 each | **Needed.** Play now surfaces large-screen quality; without them the listing is downranked on tablets |
| Promo video | YouTube URL, optional | Skip for v1 |

### Screenshot shot list, in order

The first two are what people actually see in search results; put the strongest there.

1. **Sky view with camera on**, satellites labelled over a real sky. Caption: "Point your phone. See what is up there."
2. **Passes timeline** showing a high ISS pass. Caption: "Know exactly when to look up."
3. **Play globe**, Earth with the constellation around it. Caption: "Spin the whole constellation."
4. **Satellite detail**, live elevation and downlinks. Caption: "Every orbit, in real time."
5. **Catalog**, Popular segment. Caption: "Hundreds of satellites, offline."
6. **Permission screen**. Caption: "Asks once. Explains why."

Take these on the Pixel with the walkthrough disabled and the status bar clean. For the tablet
sets, take them in landscape — the layout now caps content width and centres it, which is the
thing the large-screen review is looking for.

---

## App access (the "Sign-in details" declaration)

**"Is any part of your app restricted?" → answer that all functionality is available without
special access.**

Nothing in SatRide is gated behind an account. Every feature — the catalog, the AR sky, the 3D
globe, passes, reminders — works signed out; sign-in is skippable everywhere and the profile tab
says so. So the *honest* answer is "no restricted access", and it is also the safe one: Google
will not create accounts or use a personal one to review, and a shared Google account signing in
from review infrastructure is routinely blocked regardless of 2FA. Promising access you cannot
reliably provide is what gets a submission rejected.

There is still a labelled **Tester sign-in** (Settings → Testers) with email + password, wired to
Firebase Auth, no OTP or second factor. It exists for the internal test team and as a courtesy
path for a reviewer who wants to see the signed-in surface. **Provide those credentials in the
optional instructions box** even though the app is not "restricted" — a working demo account can
only help, and a declared door is fine where an undocumented one is not.

### "Any other information required to access your app" — paste this

```
No access restrictions. Every feature works without an account — sign-in is optional and
skippable on every screen (the Profile tab confirms "SatRide works fine without an account").

A tester email/password sign-in is provided as a courtesy, not a requirement:
  1. Open the app. On the first-run screens, tap "Skip" through onboarding, sign-in and
     permissions to reach the app.
  2. Go to the Profile tab (bottom right) → the gear icon (top right) to open Settings.
  3. Scroll to "Testers" → tap "Tester sign-in".
  4. Enter the email and password supplied in the credentials fields above.

No OTP, no two-step verification, no biometric or QR step is involved.
```

Put the actual email and password in the **username/password fields** Google provides, not in this
box. This box is only the where-to-find-it instructions.

## Native debug symbols — the warning is expected, and not fixable here

> "This App Bundle contains native code, and you've not uploaded debug symbols."

**Ship with it.** It is a recommendation, not a requirement: the bundle uploads and the release
proceeds normally.

`ndk { debugSymbolLevel = "SYMBOL_TABLE" }` is set in `androidApp/build.gradle.kts` and is correct,
but it cannot resolve this warning for SatRide. AGP *does* inspect native libraries that arrive
inside AAR dependencies — that part is often misreported — it simply cannot recover debug
information the publisher never shipped. AGP's own diagnostic, from
`./gradlew :androidApp:extractReleaseNativeSymbolTables --info --rerun-tasks`, says so for every
one of the twenty libraries:

```
Unable to extract native debug metadata from …/libsqliteJni.so
because the native debug metadata has already been stripped.
```

Every `.so` in the app comes from AndroidX — `sqlite-bundled`, `datastore`, `graphics-path`,
CameraX — published prebuilt and stripped. There is no native code of our own.

Per Google's [own documentation](https://developer.android.com/build/include-native-symbols) that
message is the expected outcome for pre-stripped dependencies. The community answer is the same:
the [most thorough write-up of this warning](https://martinlundberg.net/blog/2024/07/24/how-to-debug-and-fix-google-play-console-warning-about-missing-native-debug-symbols)
traces it to a dependency (in that author's case `datastore-preferences`) and concludes the fix is
a different library version or an upstream issue — not something the app build can do.

Both suspects are already on current versions here (`datastore` 1.2.1, `sqlite` 2.7.0), so there is
no version to move to.

Revisit only if a native crash actually appears in Android Vitals. The realistic source would be
`libsqliteJni.so`, and the route then is an upstream issue against the AndroidX library rather than
anything in this build.

## Console declarations

| Field | Answer |
| --- | --- |
| Category | Education (secondary: Tools) |
| Tags | Astronomy, Science, Education |
| Privacy policy URL | `https://satride.developerstring.com/privacy` |
| Account deletion URL | `https://satride.developerstring.com/delete-account` |
| Contact email | `support@developerstring.com` |
| Price | **Free** |
| Contains ads | **No** |
| In-app purchases | **No** — there are no Play Billing products and no billing library |
| Content rating | Complete the questionnaire; expect Everyone / PEGI 3 |
| Target audience | 13+ |

### Data safety form

Answer these exactly; they have to match the privacy policy, and Play compares them.

| Data type | Collected | Shared | Purpose | Optional |
| --- | --- | --- | --- | --- |
| Approximate/precise location | **No** — used on device only, never transmitted | No | App functionality | Yes |
| Camera | **No** — frames drawn, never stored or sent | No | App functionality | Yes |
| Email address | Yes | No | Account management | Yes |
| Name | Yes | No | Account management | Yes |
| User IDs | Yes | No | Account management, crash diagnostics | Yes |
| Country (profile) | Yes | No | App functionality | Yes |
| Crash logs | Yes | No | Diagnostics | No |
| Diagnostics / app interactions | Yes | No | Analytics | No |

Also tick: data encrypted in transit — **yes**; users can request deletion — **yes**.

The location and camera rows are the ones to get right. They are *used* but not *collected*, which
is a real distinction in the form: collected means transmitted off the device. Ticking "collected"
for location when nothing leaves the phone would be a false declaration in the direction that
costs you installs.

### Permission declarations

- **`SCHEDULE_EXACT_ALARM`** — needs a declaration in the Console. Justification: "The app schedules
  a one-off notification for a satellite pass the user explicitly asked to be reminded about. A
  pass lasts a few minutes and the reminder is useless if it arrives late, so the alarm must fire
  at an exact time." Category: alarm/timer.
- **Camera and location** — no separate declaration form, but both are described in the listing and
  primed in-app before being requested.

---

## Advertising id — removed, and it took a deliberate act

`AD_ID` is not something SatRide asks for.
`play-services-measurement-api`, which Firebase Analytics depends on, declares it in its own
manifest and the merger pulls it in regardless; `androidApp/build/outputs/logs/manifest-merger-release-report.txt`
names the library. So the app manifest removes it:

```xml
<uses-permission android:name="com.google.android.gms.permission.AD_ID" tools:node="remove" />
<uses-permission android:name="android.permission.ACCESS_ADSERVICES_AD_ID" tools:node="remove" />
```

Verified absent from `merged_manifest/release/`. **The data-safety form therefore declares no
advertising id.** Firebase Analytics keeps working — it falls back to the app-instance id — and
Crashlytics never used it. `ACCESS_ADSERVICES_ATTRIBUTION` still arrives from the same library and
is deliberately left: it is not an advertising identifier and removing it would buy no declaration
while risking Analytics behaviour nobody has tested.

---

## Google Sign-In will break in production unless you do this

`google-services.json` currently registers **one** certificate fingerprint, and it is the debug
keystore's:

```
56:32:6A:0A:9C:8E:F3:CB:93:01:1E:27:1D:6F:15:AC:85:9E:00:EF   ← ~/.android/debug.keystore
```

Google Sign-In checks the signing certificate of the installed app against the fingerprints
registered for the package. Anything not signed with that debug key gets `ApiException 10`
(DEVELOPER_ERROR) at sign-in — which is every build a real user will ever run.

Two more fingerprints have to be added in **Firebase console → Project settings → Your apps →
Android → Add fingerprint**, and then `google-services.json` re-downloaded into `androidApp/`:

1. **The upload key** — what internal-testing and locally-installed release builds are signed with:
   ```
   SHA-1    94:92:60:6C:FE:A6:28:F0:CB:51:0F:81:43:6A:70:66:63:8D:5A:FF
   SHA-256  41:1D:DB:BF:55:06:29:0C:4A:4C:7B:8B:62:11:46:1F:68:EE:BC:17:FB:FE:2D:07:D6:2C:5F:F0:2B:1F:BB:78
   ```

2. **The Play App Signing key** — the one that matters most, and the one that does not exist yet.
   With Play App Signing (on by default) Google re-signs the bundle with *its own* key before
   delivering it, so what users install is signed with neither of the above. Its fingerprint
   appears in **Play Console → your app → Test and release → Setup → App signing** only after the
   first bundle is uploaded. Add it then, before promoting anything to production.

Sign-in working in a debug build proves nothing about any of this.

## Before you hit publish

- [x] Release keystore wired — `keystore.properties` (gitignored) feeds a real `upload` signing
      config; verified the AAB carries `CN=Aditya Shinde`, 2048-bit RSA, valid to 2051
- [x] Play App Signing SHA-1 (`F5:FB:CB:92:…:9F:52:D2`) and debug SHA-1 added to Firebase, and
      `google-services.json` refreshed in `androidApp/` (17 Aug 2026)
- [ ] *Optional:* add the **upload key** SHA-1 (`94:92:60:6C:…:8D:5A:FF`) to Firebase as well.
      Not needed for anything installed from Play — Google re-signs those — but without it, a
      release APK you `adb install` directly fails sign-in with `ApiException 10`, which is
      confusing when you are trying to test the release build locally.
- [ ] `versionCode` bumped for every upload — Play rejects a repeat
- [ ] Website deployed and both URLs resolving over HTTPS
- [ ] `satride.developerstring.com` added to Firebase authorized domains, or web sign-in fails
- [ ] Firestore rules allow a user to delete their own `users/{uid}` document
- [ ] Internal testing track first, on a real device, installed from Play — not sideloaded
- [ ] Crashlytics receiving from a release build (force one crash and confirm it lands, deobfuscated)
