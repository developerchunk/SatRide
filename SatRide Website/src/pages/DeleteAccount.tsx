import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import {
  GoogleAuthProvider,
  onAuthStateChanged,
  reauthenticateWithPopup,
  signInWithPopup,
  signOut,
  type User,
} from 'firebase/auth'
import { deleteDoc, doc } from 'firebase/firestore'
import { PROFILE_COLLECTION, firebaseAuth, firebaseDb, isFirebaseConfigured } from '../firebase'
import { SUPPORT_EMAIL } from '../legal'

type Phase = 'loading' | 'signedOut' | 'signedIn' | 'deleting' | 'done'

/**
 * Account and data deletion, as Play requires it to exist on the web.
 *
 * The order of operations is the part that matters. The Firestore profile is deleted **before**
 * the auth user, because Firestore's rules only permit a user to touch their own document while
 * they are still authenticated — delete the account first and the profile becomes an orphan that
 * nobody, including us, has the rights to remove.
 *
 * Firebase also refuses `deleteUser` when the sign-in is more than a few minutes old, which is
 * correct behaviour for an irreversible action and shows up as `auth/requires-recent-login`. That
 * is caught and answered with a fresh Google popup rather than an error the user cannot act on.
 */
export default function DeleteAccount() {
  const [phase, setPhase] = useState<Phase>(isFirebaseConfigured ? 'loading' : 'signedOut')
  const [user, setUser] = useState<User | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [confirmed, setConfirmed] = useState(false)

  useEffect(() => {
    if (!isFirebaseConfigured) return
    return onAuthStateChanged(firebaseAuth(), (next) => {
      setUser(next)
      setPhase(next ? 'signedIn' : 'signedOut')
    })
  }, [])

  async function handleSignIn() {
    setError(null)
    try {
      await signInWithPopup(firebaseAuth(), new GoogleAuthProvider())
    } catch (cause) {
      setError(describe(cause))
    }
  }

  async function handleDelete() {
    const auth = firebaseAuth()
    const current = auth.currentUser
    if (!current) return

    setError(null)
    setPhase('deleting')
    try {
      // Profile first — see the note above. `deleteDoc` on a document that never existed is a
      // no-op, which is the right outcome for someone who signed in but never filled in a profile.
      await deleteDoc(doc(firebaseDb(), PROFILE_COLLECTION, current.uid))

      try {
        await current.delete()
      } catch (cause) {
        if (isRecentLoginError(cause)) {
          // Prove it is still them, then finish. The profile is already gone, so this second
          // attempt only has the auth record left to remove.
          await reauthenticateWithPopup(current, new GoogleAuthProvider())
          await current.delete()
        } else {
          throw cause
        }
      }
      setPhase('done')
    } catch (cause) {
      setError(describe(cause))
      setPhase('signedIn')
    }
  }

  if (!isFirebaseConfigured) {
    return (
      <>
        <h1>Delete your account</h1>
        <div className="notice error">
          This page is not configured yet. Add the Firebase web credentials to <code>.env</code>{' '}
          (see <code>.env.example</code>) and rebuild the site.
        </div>
        <p>
          In the meantime you can delete your account from inside the SatRide app: Profile → Delete
          account. Or write to <a href={`mailto:${SUPPORT_EMAIL}`}>{SUPPORT_EMAIL}</a> and we will
          do it for you.
        </p>
      </>
    )
  }

  return (
    <>
      <h1>Delete your account</h1>
      <p className="lede">
        Sign in with the Google account you use in SatRide, and delete it along with everything
        stored against it.
      </p>

      <h2>What gets deleted</h2>
      <div className="card">
        <ul>
          <li>Your SatRide profile — display name, country and referral note.</li>
          <li>Your sign-in record, so the account no longer exists.</li>
        </ul>
        <p style={{ marginBottom: 0 }}>
          Anything on your phone — satellites, passes, reminders, settings — was never tied to your
          account and is untouched. Uninstalling the app removes all of it. Crash and analytics
          records are not linked to your identity after deletion and age out on their own schedule;
          see <Link to="/data">data handling</Link>.
        </p>
      </div>

      {error && <div className="notice error">{error}</div>}

      {phase === 'loading' && <p>Checking your sign-in…</p>}

      {phase === 'signedOut' && (
        <>
          <h2>Step 1 — sign in</h2>
          <button className="button" onClick={handleSignIn}>
            Continue with Google
          </button>
        </>
      )}

      {(phase === 'signedIn' || phase === 'deleting') && user && (
        <>
          <h2>Step 2 — confirm</h2>
          <div className="card">
            <div className="account">
              {user.photoURL && <img src={user.photoURL} alt="" referrerPolicy="no-referrer" />}
              <div>
                <div>{user.displayName ?? 'SatRide account'}</div>
                <div className="mono">{user.email}</div>
              </div>
            </div>
          </div>

          <label className="confirm">
            <input
              type="checkbox"
              checked={confirmed}
              onChange={(event) => setConfirmed(event.target.checked)}
              disabled={phase === 'deleting'}
            />
            <span>
              I understand this permanently deletes my SatRide account and profile, and that it
              cannot be undone.
            </span>
          </label>

          <div className="row">
            <button
              className="button danger"
              onClick={handleDelete}
              disabled={!confirmed || phase === 'deleting'}
            >
              {phase === 'deleting' ? 'Deleting…' : 'Delete my account'}
            </button>
            <button
              className="button secondary"
              onClick={() => signOut(firebaseAuth())}
              disabled={phase === 'deleting'}
            >
              Sign out instead
            </button>
          </div>
        </>
      )}

      {phase === 'done' && (
        <>
          <div className="notice ok">
            Your account and profile have been deleted. Nothing is left on our side.
          </div>
          <p>
            You can keep using SatRide without an account, or sign up again any time.{' '}
            <Link to="/">Back to the start</Link>
          </p>
        </>
      )}

      <h2>Would rather we did it?</h2>
      <p>
        Write to <a href={`mailto:${SUPPORT_EMAIL}`}>{SUPPORT_EMAIL}</a> from the address on your
        account and we will delete it within 30 days.
      </p>
    </>
  )
}

function isRecentLoginError(cause: unknown): boolean {
  return typeof cause === 'object' && cause !== null && 'code' in cause &&
    (cause as { code: string }).code === 'auth/requires-recent-login'
}

/** Turns a Firebase error into something worth reading. */
function describe(cause: unknown): string {
  const code =
    typeof cause === 'object' && cause !== null && 'code' in cause
      ? (cause as { code: string }).code
      : ''
  switch (code) {
    case 'auth/popup-closed-by-user':
    case 'auth/cancelled-popup-request':
      return 'The Google window closed before sign-in finished. Try again.'
    case 'auth/popup-blocked':
      return 'Your browser blocked the Google popup. Allow popups for this site and try again.'
    case 'auth/network-request-failed':
      return 'Could not reach Google. Check your connection and try again.'
    case 'permission-denied':
      return 'The profile could not be deleted because permission was refused. Please email us and we will remove it.'
    default:
      return `Something went wrong${code ? ` (${code})` : ''}. Please try again, or email ${SUPPORT_EMAIL}.`
  }
}
