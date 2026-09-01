import { NavLink } from 'react-router-dom';

const items = [
  { to: '/run', label: '跑步' },
  { to: '/history', label: '记录' },
  { to: '/settings', label: '设置' },
];

export function AppNavigation() {
  return (
    <nav className="app-navigation" aria-label="主要导航">
      {items.map((item) => (
        <NavLink key={item.to} to={item.to} className="nav-item">
          <span className="nav-marker" aria-hidden="true" />
          <span>{item.label}</span>
        </NavLink>
      ))}
    </nav>
  );
}
