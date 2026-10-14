import { Suspense, lazy } from 'react'
import { Route, Routes } from 'react-router-dom'
import Layout from './components/Layout'
import Home from './pages/Home'
import Privacy from './pages/Privacy'
import Terms from './pages/Terms'
import DataHandling from './pages/DataHandling'
import NotFound from './pages/NotFound'

/**
 * The deletion page is the only one that needs Firebase, and Firebase is by far the largest thing
 * on the site — several hundred kilobytes against a few for a policy page. Loading it lazily keeps
 * that weight off the pages almost everybody actually visits.
 */
const DeleteAccount = lazy(() => import('./pages/DeleteAccount'))

export default function App() {
  return (
    <Routes>
      <Route element={<Layout />}>
        <Route index element={<Home />} />
        <Route path="privacy" element={<Privacy />} />
        <Route path="terms" element={<Terms />} />
        <Route path="data" element={<DataHandling />} />
        {/*
          Two paths to the same page. Play's account-deletion requirement wants a URL that is
          obviously about deletion, and `/delete-account` is what gets pasted into the console;
          `/delete` is the short one people type.
        */}
        <Route
          path="delete-account"
          element={
            <Suspense fallback={<p>Loading…</p>}>
              <DeleteAccount />
            </Suspense>
          }
        />
        <Route
          path="delete"
          element={
            <Suspense fallback={<p>Loading…</p>}>
              <DeleteAccount />
            </Suspense>
          }
        />
        <Route path="*" element={<NotFound />} />
      </Route>
    </Routes>
  )
}
