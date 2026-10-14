# P15 — Identity & Profile (`core:auth`, `feature:auth`, `feature:profile`)

**Objective:** an optional account. A user can sign in with Google, keep a small profile, and be
credited to whoever referred them — without any of it standing between them and the sky.
**Depends on:** P6. **Duration target:** 3 days.

> **Numbering.** P7–P10 keep their numbers and their content; they are deferred, not renumbered, so
> every cross-reference in them stays valid. Execution order lives in the table in
> [README.md](README.md), not in the integers.

## The one rule this phase must not break

**SatRide works with no account and no network.** That is not a nice-to-have; it is the reason the
whole app computes locally instead of calling a server. Sign-in unlocks a profile and referrals and
nothing else. Every screen that existed before this phase must still work for a signed-out user, and
the app must never open on a wall the user cannot get past.

Concretely: no route is gated, no view model requires a session, and `AuthState.SignedOut` is a
first-class state rather than an error.

## Tasks

### P15.1 — `core:auth` contracts and Firebase wiring
- `AuthRepository`: `state: StateFlow<AuthState>`, `signInWithGoogle()`, `signOut()`,
  `deleteAccount()`. `AuthState` is `Unknown | SignedOut | SignedIn(SatRideUser)` — `Unknown` is
  distinct on purpose, because "we have not restored the session yet" and "there is no session"
  look identical to code and completely different to a user.
- `GoogleIdTokenProvider` — the only platform-specific piece. Android uses Credential Manager;
  iOS is supplied by Swift (see P15.5). Kotlin never links GoogleSignIn directly.
- **Accept:** signing in twice in a row is idempotent; `signOut` returns to `SignedOut` without
  clearing any local satellite data.

### P15.2 — Every Google account on the phone, none of them automatic
- Android `GetGoogleIdOption` must set **`setFilterByAuthorizedAccounts(false)`** so accounts that
  have never used SatRide are offered, and **`setAutoSelectEnabled(false)`** so a returning user is
  still shown the picker rather than being silently signed into whichever account Android
  considers default.
- **Accept:** on a device with two or more Google accounts, the picker lists all of them, and a
  second sign-in after a sign-out shows the picker again rather than jumping straight in 🔌.

### P15.3 — Profile over Firestore
- `UserProfile(displayName, countryCode?, referredBy?, createdAt, updatedAt)`, stored at
  `users/{uid}`. Rules in `docs/plan/08-firestore-rules.md`; ownership is the document path.
- `ProfileRepository`: `observe()`, `save()`, `delete()`. Offline is Firestore's own cache — a
  profile edit made with no signal is written when there is one.
- **Accept:** create, edit and re-read round-trip; rules reject a write to another uid.

### P15.4 — Screens
- **Sign in:** what an account is *for*, the Google button, and a visible **Skip** — the skip is
  the honest one, since nothing behind it is gated.
- **Create profile:** Name (required, 1–60), Country (optional, searchable ISO 3166-1 list),
  Referred by (optional). Shown after a *first* sign-in only; a returning user goes straight back.
- **Profile tab:** signed-out prompt, or the profile with edit, sign out, and delete account.
- **Accept:** VM tests for validation, country search and submission; the country list is searchable
  by name and by code.

### P15.5 — iOS handoff 🔌
Kotlin cannot call GoogleSignIn without the SDK being linked, and the Xcode project has no packages.
So the iOS actual is a thin holder that Swift populates at launch.
- **Needs the project owner in Xcode:** add `firebase-ios-sdk` and `GoogleSignIn-iOS` via SPM, and
  **correct the bundle identifier** — `GoogleService-Info.plist` is registered for
  `com.developerstring.satride` while `Config.xcconfig` builds
  `com.developerstring.satride.SatRide$(TEAM_ID)`. Firebase refuses to initialise on a bundle id it does
  not know, and the failure is at runtime, not build time.
- **Accept:** sign-in completes on an iPhone and the same uid appears in the Firebase console 🔌.

## Exit checklist
- [ ] A signed-out user can still use every screen the app had before this phase
- [ ] All Google accounts on the device are offered; none is selected automatically
- [ ] Profile round-trips through Firestore, and the rules refuse a cross-uid write
- [ ] Account deletion removes the Firestore document and the Firebase Auth user
- [ ] iOS handoff written down with exact steps, and the bundle-id mismatch resolved
