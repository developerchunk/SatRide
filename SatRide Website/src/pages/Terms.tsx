import { EFFECTIVE_DATE, SUPPORT_EMAIL } from '../legal'

export default function Terms() {
  return (
    <>
      <h1>Terms &amp; Conditions</h1>
      <p className="updated">Effective {EFFECTIVE_DATE}</p>

      <p className="lede">
        These terms cover your use of the SatRide mobile app and this website. Using either means
        you accept them.
      </p>

      <h2>The licence you get</h2>
      <p>
        SatRide is licensed to you, not sold. You may install and use it on devices you control, for
        personal or professional use, free of charge. You may not resell it, redistribute modified
        copies, or attempt to extract its source beyond what your local law expressly permits.
      </p>

      <h2>Accounts</h2>
      <p>
        An account is optional. If you create one, keep control of the Google account behind it —
        anyone who can sign in as you can change or delete your profile. You may delete your account
        at any time from the Profile tab or the account deletion page, and doing so is immediate and
        permanent.
      </p>

      <h2>Accuracy — read this one</h2>
      <div className="card warn">
        <p>
          SatRide predicts satellite positions by propagating publicly published orbital element
          sets with the standard SGP4 model. Those predictions carry real error. Element sets age,
          satellites manoeuvre without notice, and the model itself drifts by kilometres over days.
          Pointing accuracy in the sky view is further limited by your phone's compass, which is
          routinely several degrees out indoors or near metal.
        </p>
        <p>
          <strong>
            SatRide is for observation, education and enjoyment. It is not fit for navigation,
            aviation, emergency response, licensed radio operation where precision is required, or
            any purpose where being wrong has consequences.
          </strong>{' '}
          Do not rely on it for anything safety-critical.
        </p>
      </div>

      <h2>Third-party data</h2>
      <p>
        Orbital elements and transmitter details come from public sources including CelesTrak and
        SatNOGS. We are grateful for them and we do not control them. Their availability, accuracy
        and terms are their own; if a source goes down or publishes something wrong, SatRide will
        reflect that.
      </p>

      <h2>Acceptable use</h2>
      <p>
        Do not use SatRide to break the law where you are. Radio regulations in particular differ
        sharply between countries: what a receiver may lawfully listen to, and what may be recorded
        or shared, is your responsibility to know. Do not attempt to disrupt the service, abuse the
        upstream data sources, or use the app to harass anyone.
      </p>

      <h2>Cost</h2>
      <p>
        SatRide is free in full. There are no ads, no purchases and no limits, and no payment
        details are ever collected.
      </p>

      <h2>Availability and changes</h2>
      <p>
        The app is provided as it is. We may change, suspend or discontinue features, and we may
        update these terms. Continuing to use SatRide after a change means you accept the updated
        terms.
      </p>

      <h2>Liability</h2>
      <p>
        To the fullest extent the law allows, SatRide is provided without warranties of any kind,
        and DeveloperString is not liable for indirect or consequential loss arising from your use
        of it. Nothing here limits liability that cannot lawfully be limited, and if you have
        statutory consumer rights, these terms do not displace them.
      </p>

      <h2>Contact</h2>
      <p>
        Questions about these terms: <a href={`mailto:${SUPPORT_EMAIL}`}>{SUPPORT_EMAIL}</a>.
      </p>
    </>
  )
}
