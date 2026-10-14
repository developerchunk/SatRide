# SatRide Website

The public site for SatRide — **https://satride.developerstring.com**

It exists for three reasons, in order of how much Google Play cares:

1. **Account and data deletion.** Play requires apps that create accounts to offer deletion from a
   publicly reachable URL, not only from inside the app. That is `/delete-account`.
2. **A privacy policy URL.** Required in the Console for any app that requests location or camera.
3. A place to point people at.

## Running it

```bash
npm install
cp .env.example .env   # then fill in the two blank values, see below
npm run dev
```

`npm run build` type-checks and produces `dist/`.

## Firebase configuration

The deletion page signs the user in and deletes their record, so it needs Firebase **web**
credentials. These are not in `androidApp/google-services.json` — a web app has its own `apiKey`
and `appId`.

The Web app (`SatRide`, app id `…:web:578bac578adb7c7bf72697`) is registered, and `.env` is filled
in except for one value: paste the **Web API key** from Firebase console → Project settings → Your
apps → SatRide → Config.

The `apiKey` is not a secret — it identifies the project and ships in any web client — but it is
per-project, so it is configured rather than committed. `.env` is gitignored. What protects your
data is Firestore rules, not that string.

### No analytics on this site, deliberately

The Firebase snippet the console hands you includes `getAnalytics()`. It is left out here. These
are the privacy policy and the account-deletion pages: measuring the people reading them would
have to be disclosed *in the policy they are reading*, and in the EU it drags a cookie-consent
banner onto a page whose entire job is to be plainly readable. The app is measured; the legal
pages are not.

## Firestore rules the deletion page depends on

Deleting the profile happens **from the client, as the signed-in user**. That only works if the
rules allow a user to delete their own document:

```
match /users/{uid} {
  allow read, write, delete: if request.auth != null && request.auth.uid == uid;
}
```

The profile is deleted *before* the auth account, and the order is not incidental: once the auth
user is gone the client is anonymous, the rule above no longer matches, and the profile document
is stranded where nobody — including us — can remove it.

## Deploying

**See [DEPLOY.md](DEPLOY.md)** for the Firebase Hosting steps, the custom domain, and the two
console settings that are easy to miss.

The site is a single-page app, so **every path has to serve `index.html`**. A direct visit to
`/privacy` is exactly what the Play listing links to, and without a rewrite the host answers 404
before React ever runs. Configs for the three usual hosts are included:

| Host            | File            |
| --------------- | --------------- |
| Firebase Hosting| `firebase.json` |
| Netlify         | `public/_redirects` |
| Vercel          | `vercel.json`   |

For Firebase Hosting:

```bash
npm run build
firebase deploy --only hosting
```

Then point `satride.developerstring.com` at the host and add it to the Firebase authorized domains.

## URLs the Play Console needs

| Console field            | URL                                                    |
| ------------------------ | ------------------------------------------------------ |
| Privacy policy           | `https://satride.developerstring.com/privacy`          |
| Account deletion         | `https://satride.developerstring.com/delete-account`   |

## Keeping it honest

`src/legal.ts` holds the effective date and the support address for all three legal pages, so they
cannot drift apart. The pages describe what the app *actually* does — location never leaves the
device, camera frames are never captured, the profile is the only thing stored. If the app's
behaviour changes, these pages and the Console's data-safety form both have to change with it, and
Play compares the two.
