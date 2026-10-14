import { NavLink, Outlet, Link } from 'react-router-dom'

const NAV = [
  { to: '/privacy', label: 'Privacy' },
  { to: '/terms', label: 'Terms' },
  { to: '/data', label: 'Data handling' },
  { to: '/delete-account', label: 'Delete account' },
]

export default function Layout() {
  return (
    <div className="shell">
      <header className="site-header">
        <div className="wrap">
          <Link className="brand" to="/">
            <img src="/icon.png" alt="" />
            SatRide
          </Link>
          <nav className="site-nav">
            {NAV.map((item) => (
              <NavLink
                key={item.to}
                to={item.to}
                className={({ isActive }) => (isActive ? 'active' : undefined)}
              >
                {item.label}
              </NavLink>
            ))}
          </nav>
        </div>
      </header>

      <main>
        <div className="wrap">
          <Outlet />
        </div>
      </main>

      <footer className="site-footer">
        <div className="wrap">
          <span>© {new Date().getFullYear()} DeveloperString</span>
          <span>
            <a href="mailto:support@developerstring.com">support@developerstring.com</a>
          </span>
        </div>
      </footer>
    </div>
  )
}
