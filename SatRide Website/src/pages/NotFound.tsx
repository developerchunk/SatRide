import { Link } from 'react-router-dom'

export default function NotFound() {
  return (
    <>
      <h1>Nothing here</h1>
      <p className="lede">That page does not exist.</p>
      <p>
        <Link to="/">Back to the start</Link>
      </p>
    </>
  )
}
