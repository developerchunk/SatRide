import { Link } from 'react-router-dom'
import { EFFECTIVE_DATE } from '../legal'

/**
 * The privacy policy.
 *
 * Written against what the app actually does — location stays on the device, the camera frame is
 * never captured, the only thing that leaves the phone is an optional profile document — rather
 * than from a generic template. A policy that over-claims is a policy the data-safety form will
 * contradict, and Play compares the two.
 */
export default function Privacy() {
  return (
    <>
      <h1>Privacy Policy</h1>
      <p className="updated">Effective {EFFECTIVE_DATE}</p>

      <p className="lede">
        SatRide is a satellite tracker. It needs to know roughly where you are to work out what is
        flying over you, and it can use your camera to draw satellites over the real sky. Neither of
        those leaves your phone.
      </p>

      <h2>The short version</h2>
      <div className="card">
        <ul>
          <li>Your location is used on the device and is never uploaded to us.</li>
          <li>The camera is used only to draw the live view behind the sky. No photo or video is captured, stored or sent.</li>
          <li>An account is optional. The whole app works without one.</li>
          <li>If you sign in, we store a display name, an optional country and an optional referral note.</li>
          <li>We do not sell your data, and there is no advertising in the app.</li>
        </ul>
      </div>

      <h2>Who we are</h2>
      <p>
        SatRide is made by DeveloperString. For anything in this policy, write to{' '}
        <a href="mailto:support@developerstring.com">support@developerstring.com</a>.
      </p>

      <h2>What the app collects, and why</h2>

      <h3>Location</h3>
      <p>
        Used to compute which satellites are above your horizon, when they will pass, and where to
        point. It is read on the device and used on the device. We never receive it and it is never
        transmitted to our servers. You can decline location access and enter a position by hand in
        Settings instead; the app works exactly the same way.
      </p>

      <h3>Camera</h3>
      <p>
        Used only as a live backdrop in the sky view so satellites appear over what you are actually
        looking at. Frames are drawn to the screen and discarded. Nothing is recorded, saved to your
        gallery, or uploaded. You can use the sky view with the camera off.
      </p>

      <h3>Motion and compass sensors</h3>
      <p>
        Used to work out which way the phone is pointing so the sky view lines up with the real sky.
        Read on the device only.
      </p>

      <h3>Notifications</h3>
      <p>
        Used only to remind you about a pass you asked to be reminded about. Reminders are scheduled
        on your device, not sent from a server.
      </p>

      <h3>Account information</h3>
      <p>
        Signing in is optional and uses Google Sign-In through Firebase Authentication. If you do
        sign in, we receive and store a Firebase user id, and the email address and display name
        attached to the Google account you chose. In your profile we store a display name, and
        optionally a country and a free-text note about who told you about the app.
      </p>

      <h3>Diagnostics</h3>
      <p>
        The app reports crashes and basic usage through Firebase Crashlytics and Firebase Analytics
        so we can find and fix faults. These reports describe what the app did — which screen was
        open, which feature was used, the device model and OS version. They never include your
        location, your camera, or the contents of anything you typed. If you are signed in, a crash
        report carries your Firebase user id so we can recognise repeated reports from one person.
      </p>

      <h2>What we never collect</h2>
      <p>
        Your coordinates. Photos or video. Contacts, messages, files, or anything else on your
        device. We do not track you across other apps or websites, and there is no advertising
        identifier in SatRide.
      </p>

      <h2>Where data goes</h2>
      <p>
        Orbital data is downloaded from public sources — chiefly CelesTrak and SatNOGS — over an
        encrypted connection. Those requests contain no information about you beyond what any web
        request necessarily reveals to a server, such as your IP address, which those services
        handle under their own policies.
      </p>
      <p>
        Account and profile data, and diagnostics, are processed by Google Firebase on our behalf.
        Firebase's handling is described in Google's own privacy documentation.
      </p>

      <h2>How long we keep things</h2>
      <p>
        Your profile document exists until you delete your account. Crash reports are retained by
        Crashlytics for up to 90 days and analytics data for up to 14 months, per Firebase's
        defaults. Everything else — the satellite catalog, computed passes, your settings — lives
        only on your device and is removed when you uninstall the app.
      </p>

      <h2>Your choices and rights</h2>
      <ul>
        <li>Every permission can be refused or withdrawn at any time, from the Profile tab or from your device's settings. The app degrades honestly rather than breaking.</li>
        <li>You can use SatRide entirely without an account.</li>
        <li>
          You can delete your account and everything stored with it at any time — in the app, or on
          the <Link to="/delete-account">account deletion page</Link>.
        </li>
        <li>
          To ask what is stored about you, or to have it corrected, write to{' '}
          <a href="mailto:support@developerstring.com">support@developerstring.com</a>.
        </li>
      </ul>

      <h2>Children</h2>
      <p>
        SatRide is not directed at children under 13, and we do not knowingly collect information
        from them. If you believe a child has given us information, write to us and we will remove
        it.
      </p>

      <h2>Changes</h2>
      <p>
        If this policy changes in a way that matters, we will update the effective date above and
        say so in the app's release notes.
      </p>
    </>
  )
}
