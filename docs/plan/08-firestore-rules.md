# 08 — Firestore security rules

The rules SatRide's data model needs, and why each clause is there. Paste the block under
**Production rules** into the Firebase console (Firestore → Rules → Publish).

## The data model these rules assume

```
users/{uid}                      one document per signed-in user, id == Firebase Auth uid
  displayName : string           required, 1..60 chars
  countryCode : string | null    ISO 3166-1 alpha-2, uppercase
  referredBy  : string | null    free text, trimmed, <= 60 chars
  createdAt   : timestamp        server time, write-once
  updatedAt   : timestamp        server time
```

Nothing else is stored in Firestore. Satellites, element sets, passes and alerts are all local —
that is deliberate and unchanged: the app has to work on a hillside with no signal, so the network
is never in the path of drawing a frame.

## Production rules

```
rules_version = '2';

service cloud.firestore {
  match /databases/{database}/documents {

    // A user's own profile, and nothing else.
    //
    // The document id *is* the Firebase Auth uid, so ownership is not a field that could be
    // spoofed — it is the path itself. A request for someone else's document does not match
    // `request.auth.uid` and is refused before any field is looked at.
    match /users/{userId} {

      allow read: if isOwner(userId);

      // Create and update are separated because they have genuinely different rules: `createdAt`
      // is write-once, and a client that could rewrite it could backdate its own account.
      allow create: if isOwner(userId)
                    && isValidProfile()
                    && request.resource.data.createdAt == request.time
                    && request.resource.data.updatedAt == request.time;

      allow update: if isOwner(userId)
                    && isValidProfile()
                    && request.resource.data.createdAt == resource.data.createdAt
                    && request.resource.data.updatedAt == request.time;

      // Deleting your own profile is allowed: it is the user's data, and an account-deletion
      // path is a store requirement on both platforms.
      allow delete: if isOwner(userId);
    }

    // Everything not matched above stays closed. This is the default the project starts with and
    // it is kept deliberately — a collection added later should have to be thought about, not
    // inherit access from a wildcard someone forgot to tighten.
    match /{document=**} {
      allow read, write: if false;
    }
  }
}

function isOwner(userId) {
  return request.auth != null && request.auth.uid == userId;
}

function isValidProfile() {
  let d = request.resource.data;
  return d.keys().hasOnly(['displayName', 'countryCode', 'referredBy', 'createdAt', 'updatedAt'])
      && d.displayName is string
      && d.displayName.size() >= 1
      && d.displayName.size() <= 60
      && (!('countryCode' in d) || d.countryCode == null
          || (d.countryCode is string && d.countryCode.matches('^[A-Z]{2}$')))
      && (!('referredBy' in d) || d.referredBy == null
          || (d.referredBy is string && d.referredBy.size() <= 60))
      && d.createdAt is timestamp
      && d.updatedAt is timestamp;
}
```

## Why it is shaped this way

- **Ownership is the path, not a field.** `users/{uid}` with `request.auth.uid == uid` cannot be
  forged. A `users/{autoId}` collection with an `ownerUid` field can be — a client writes any uid
  it likes into a *new* document unless a rule forbids it, and that rule is easy to get subtly
  wrong. Making the id the uid removes the class of mistake.
- **`hasOnly(...)`.** Without it a client can attach arbitrary extra fields to its own document.
  That is not a security hole in itself, but it is how a document quietly grows a field the app
  never reads and nobody can safely delete later.
- **`createdAt` is write-once**, checked by comparing against `resource.data` on update. Rules are
  the only place this can be enforced; a client-side check is a suggestion.
- **`request.time`, not a client clock.** A client that supplies its own timestamp can claim to
  have joined before anyone else, which matters the moment referrals mean anything.
- **The catch-all stays `false`.** New collections must be granted access explicitly.

## What is deliberately *not* here

- **No public read of profiles.** Referrals are recorded as free text at sign-up; nothing in the
  app looks another user up, so nothing needs to read another user's document. If a referral
  leaderboard is ever built, it should be served from an aggregate written by a Cloud Function —
  not by opening every profile to every reader.
- **No server-side validation of `referredBy`.** It is a string the user types. Checking that it
  names a real account would require reading other users' documents, which is exactly what the
  rules above refuse. Resolve it in a Cloud Function later if it needs to mean anything.
