import { Link } from 'react-router-dom'

const FEATURES = [
  {
    title: 'Point at the sky',
    body: 'Hold your phone up and satellites appear over the real sky, exactly where they are.',
  },
  {
    title: 'Know when to look',
    body: 'Every pass over your location, with the time it rises and how high it climbs.',
  },
  {
    title: 'The whole constellation',
    body: 'A live 3D Earth you can spin, with hundreds of objects in their real orbits.',
  },
  {
    title: 'Works offline',
    body: 'A full catalog ships with the app. No signal needed to know what is overhead.',
  },
]

export default function Home() {
  return (
    <>
      <h1>See every satellite above you</h1>
      <p className="lede">
        SatRide tracks the objects flying over your head right now — in augmented reality, on a live
        3D globe, and on a timeline that tells you when to go outside and look up.
      </p>

      <div className="row hero-actions">
        <a
          className="button"
          href="https://play.google.com/store/apps/details?id=com.developerstring.satride"
        >
          Get it on Google Play
        </a>
        <Link className="button secondary" to="/privacy">
          Privacy policy
        </Link>
      </div>

      <div className="features">
        {FEATURES.map((feature) => (
          <div className="card" key={feature.title}>
            <h3 style={{ marginTop: 0 }}>{feature.title}</h3>
            <p style={{ marginBottom: 0 }}>{feature.body}</p>
          </div>
        ))}
      </div>

      <h2>Your data stays yours</h2>
      <p>
        Your location is used on your phone and never uploaded. The camera draws the sky behind the
        satellites and records nothing. An account is entirely optional — the app works without one,
        and you can <Link to="/delete-account">delete it and everything in it</Link> whenever you
        want.
      </p>

      <h2>Where the data comes from</h2>
      <p>
        Orbits are propagated with SGP4 from element sets published by CelesTrak, and transmitter
        details come from the SatNOGS database. SatRide would not exist without either of them.
      </p>
    </>
  )
}
