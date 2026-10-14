/**
 * The Firebase handles the account-deletion page needs, and nothing else.
 *
 * Initialised lazily so that every other page — the policies, which is most of the traffic —
 * loads without pulling the SDK or reaching the network. `getAuth` at module scope would make
 * Firebase a hard dependency of the whole site.
 */
import { initializeApp, type FirebaseApp } from 'firebase/app'
import { getAuth, type Auth } from 'firebase/auth'
import { getFirestore, type Firestore } from 'firebase/firestore'

const config = {
  apiKey: import.meta.env.VITE_FIREBASE_API_KEY,
  authDomain: import.meta.env.VITE_FIREBASE_AUTH_DOMAIN,
  projectId: import.meta.env.VITE_FIREBASE_PROJECT_ID,
  storageBucket: import.meta.env.VITE_FIREBASE_STORAGE_BUCKET,
  messagingSenderId: import.meta.env.VITE_FIREBASE_MESSAGING_SENDER_ID,
  appId: import.meta.env.VITE_FIREBASE_APP_ID,
}

/** True when the site has been configured. The deletion page says so rather than failing oddly. */
export const isFirebaseConfigured = Boolean(config.apiKey && config.appId && config.projectId)

let app: FirebaseApp | undefined

function firebaseApp(): FirebaseApp {
  if (!app) app = initializeApp(config)
  return app
}

export function firebaseAuth(): Auth {
  return getAuth(firebaseApp())
}

export function firebaseDb(): Firestore {
  return getFirestore(firebaseApp())
}

/** Where the app keeps a profile — `users/{uid}`, matching core:auth's ProfileRepository. */
export const PROFILE_COLLECTION = 'users'
