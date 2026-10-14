# Deploying to Firebase Hosting

Target: **https://satride.developerstring.com**, Firebase project `skyrf-3964a`.

`firebase.json` (rewrites + asset caching) and `.firebaserc` are already in the repo, so the only
per-machine setup is the CLI and a login.

## One-time

```bash
npm install -g firebase-tools
```

```bash
firebase login
```

Then put the Web API key into `.env` — the only value not already filled in:

```
VITE_FIREBASE_API_KEY=AIza…
```

Firebase console → Project settings → Your apps → **SatRide** (Web) → Config. Without it the
deletion page renders an honest "not configured" notice instead of the sign-in flow; every other
page works regardless.

## Deploy

```bash
npm run build && firebase deploy --only hosting
```

That publishes to `skyrf-3964a.web.app` first. Check the deletion page there before wiring the
custom domain — it is much easier to debug on the default domain, which is already an authorized
domain for auth.

## Custom domain

1. Firebase console → **Hosting** → *Add custom domain* → `satride.developerstring.com`.
2. Firebase gives you a **TXT** record to prove ownership. Add it at whoever hosts DNS for
   `developerstring.com`, and wait for verification.
3. Firebase then gives you **A** records (or a CNAME for a subdomain — a CNAME to
   `skyrf-3964a.web.app` is the simpler option for `satride.`). Add those.
4. Wait for the certificate. Firebase provisions Let's Encrypt automatically; it usually takes
   under an hour but can take up to 24. The domain shows as *Pending* until then.

DNS propagation is the slow part and there is nothing to do but wait. `dig satride.developerstring.com`
tells you whether your records have taken effect yet.

## The step everyone forgets

Firebase console → **Authentication → Settings → Authorized domains** → add
`satride.developerstring.com`.

Google sign-in on the deletion page is rejected with `auth/unauthorized-domain` until you do. The
default `*.web.app` and `*.firebaseapp.com` domains are authorized automatically, so this only
bites once the custom domain is live — which is exactly when you stop testing it.

## Firestore rules

The deletion page deletes `users/{uid}` as the signed-in user, so the rules have to permit it:

```
match /users/{uid} {
  allow read, write, delete: if request.auth != null && request.auth.uid == uid;
}
```

Deploy rules with `firebase deploy --only firestore:rules` if you keep them in this repo, or edit
them in the console.

## Verifying it actually works

Do this on the live domain, not locally, with a **throwaway Google account** — the deletion is real
and irreversible.

1. Open `/delete-account`, sign in.
2. Confirm the account shown is the throwaway one.
3. Tick the box, delete.
4. In the Firebase console, check that both the Authentication user and the `users/{uid}` document
   are gone.

If deletion fails with `permission-denied`, the rules above are missing. If sign-in fails with
`auth/unauthorized-domain`, the authorized-domains step is.
