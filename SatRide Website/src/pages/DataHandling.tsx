import { Link } from 'react-router-dom'
import { EFFECTIVE_DATE, SUPPORT_EMAIL } from '../legal'

/**
 * The itemised version of the privacy policy.
 *
 * It exists as its own page because the Play data-safety form asks per data type — collected or
 * not, shared or not, required or optional, why — and having those answers written down in one
 * table is what keeps the form, the policy and the app consistent with each other.
 */
export default function DataHandling() {
  return (
    <>
      <h1>Data Handling</h1>
      <p className="updated">Effective {EFFECTIVE_DATE}</p>

      <p className="lede">
        Exactly what SatRide touches, where it goes, and how long it stays. This is the detailed
        companion to the <Link to="/privacy">privacy policy</Link>.
      </p>

      <h2>Stays on your device</h2>
      <p>None of the following is ever transmitted to us.</p>
      <table>
        <thead>
          <tr>
            <th>Data</th>
            <th>Used for</th>
            <th>Kept</th>
          </tr>
        </thead>
        <tbody>
          <tr>
            <td>Precise or approximate location</td>
            <td>Working out which satellites are above you and when they pass</td>
            <td>Latest fix only, in app storage</td>
          </tr>
          <tr>
            <td>Camera frames</td>
            <td>The live backdrop in the sky view</td>
            <td>Not kept — drawn and discarded</td>
          </tr>
          <tr>
            <td>Compass and motion</td>
            <td>Aiming the sky view where the phone points</td>
            <td>Not kept</td>
          </tr>
          <tr>
            <td>Satellite catalog and computed passes</td>
            <td>Working offline</td>
            <td>Local database, until uninstall</td>
          </tr>
          <tr>
            <td>Your settings and reminders</td>
            <td>Remembering your preferences</td>
            <td>Local storage, until uninstall</td>
          </tr>
        </tbody>
      </table>

      <h2>Leaves your device</h2>
      <table>
        <thead>
          <tr>
            <th>Data</th>
            <th>Goes to</th>
            <th>Why</th>
            <th>Optional?</th>
          </tr>
        </thead>
        <tbody>
          <tr>
            <td>Firebase user id, email, Google display name</td>
            <td>Firebase Authentication</td>
            <td>Signing you in</td>
            <td>Yes — the app works signed out</td>
          </tr>
          <tr>
            <td>Display name, country, referral note</td>
            <td>Firestore, at <span className="mono">users/&#123;uid&#125;</span></td>
            <td>Your profile</td>
            <td>Yes</td>
          </tr>
          <tr>
            <td>Crash traces, device model, OS version, screen names</td>
            <td>Firebase Crashlytics</td>
            <td>Diagnosing faults</td>
            <td>No, while the app is installed</td>
          </tr>
          <tr>
            <td>Feature-usage events, device model, country</td>
            <td>Firebase Analytics</td>
            <td>Understanding which features are used</td>
            <td>No, while the app is installed</td>
          </tr>
          <tr>
            <td>Requests for orbital data (no personal content)</td>
            <td>CelesTrak, SatNOGS</td>
            <td>Fetching current element sets</td>
            <td>No, for fresh data</td>
          </tr>
        </tbody>
      </table>

      <h2>Not collected at all</h2>
      <p>
        Coordinates, photos, video, audio, contacts, messages, files, calendar, health data,
        installed apps, advertising identifiers, or any cross-app or cross-site tracking. SatRide
        contains no advertising SDK.
      </p>

      <h2>Sharing and selling</h2>
      <p>
        We do not sell personal data and we do not share it with anyone for advertising. The only
        third parties involved are the processors named above, acting on our behalf, plus the public
        data sources SatRide downloads from.
      </p>

      <h2>Security</h2>
      <p>
        Everything in transit uses TLS. Profile documents are protected by Firestore security rules
        that let each signed-in user read and write only their own document. We hold no servers and
        no database of our own; there is no separate copy of your data for us to lose.
      </p>

      <h2>Retention and deletion</h2>
      <ul>
        <li>Profile document: until you delete your account.</li>
        <li>Authentication record: until you delete your account.</li>
        <li>Crash reports: up to 90 days.</li>
        <li>Analytics: up to 14 months.</li>
        <li>Everything on-device: removed when you uninstall.</li>
      </ul>
      <p>
        <Link to="/delete-account">Delete your account and data →</Link>
      </p>

      <h2>Questions</h2>
      <p>
        <a href={`mailto:${SUPPORT_EMAIL}`}>{SUPPORT_EMAIL}</a>
      </p>
    </>
  )
}
