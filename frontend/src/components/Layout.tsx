import { useState } from 'react';
import { Outlet, NavLink, useLocation } from 'react-router-dom';
import {
  LayoutDashboard, Send, Users, FileText, BarChart3, LogOut, Menu, X,
} from 'lucide-react';
import { useAuthStore } from '../store/authStore';
import clsx from 'clsx';

const NAV_ITEMS = [
  { path: '/dashboard',  label: 'Dashboard',  icon: LayoutDashboard },
  { path: '/campaigns',  label: 'Campaigns',  icon: Send },
  { path: '/recipients', label: 'Recipients', icon: Users },
  { path: '/templates',  label: 'Templates',  icon: FileText },
  { path: '/analytics',  label: 'Analytics',  icon: BarChart3 },
];

const PAGE_TITLES: Record<string, string> = {
  '/dashboard': 'Dashboard',
  '/campaigns': 'Campaigns',
  '/recipients': 'Recipients',
  '/templates': 'Templates',
  '/analytics': 'Analytics',
};

function initials(first?: string, last?: string): string {
  return `${first?.[0] ?? ''}${last?.[0] ?? ''}`.toUpperCase() || 'U';
}

// Role precedence (highest privilege first). The backend may return the user's
// roles in any order, so we display the highest-privilege role the user holds
// rather than whichever role happens to be first in the array.
const ROLE_PRECEDENCE = ['ADMIN', 'CAMPAIGN_MANAGER', 'VIEWER'];

function primaryRole(roles?: string[]): string {
  if (!roles || roles.length === 0) return 'User';
  for (const role of ROLE_PRECEDENCE) {
    if (roles.includes(role)) return role;
  }
  return roles[0];
}

function SidebarContent({ onNavigate }: { onNavigate?: () => void }) {
  const { user, logout } = useAuthStore();

  return (
    <div className="flex h-full flex-col">
      {/* Brand */}
      <div className="flex h-16 items-center gap-3 border-b border-slate-200 px-6">
        <div className="flex h-9 w-9 items-center justify-center rounded-xl bg-gradient-to-br from-brand-600 to-accent-600 shadow-sm">
          <Send className="h-5 w-5 text-white" />
        </div>
        <span className="text-lg font-bold tracking-tight text-slate-900">MailFlow</span>
      </div>

      {/* Nav */}
      <nav className="flex-1 space-y-1 overflow-y-auto px-3 py-4">
        <p className="px-3 pb-2 text-xs font-semibold uppercase tracking-wider text-slate-400">
          Menu
        </p>
        {NAV_ITEMS.map(({ path, label, icon: Icon }) => (
          <NavLink
            key={path}
            to={path}
            onClick={onNavigate}
            className={({ isActive }) =>
              clsx('nav-link', isActive ? 'nav-link-active' : 'nav-link-idle')
            }
          >
            <Icon className="h-5 w-5 shrink-0" />
            {label}
          </NavLink>
        ))}
      </nav>

      {/* User */}
      {user && (
        <div className="border-t border-slate-200 p-3">
          <div className="mb-2 flex items-center gap-3 rounded-xl bg-slate-50 px-3 py-2.5">
            <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-gradient-to-br from-brand-500 to-accent-500 text-xs font-semibold text-white">
              {initials(user.firstName, user.lastName)}
            </div>
            <div className="min-w-0 flex-1">
              <p className="truncate text-sm font-semibold text-slate-900">
                {user.firstName} {user.lastName}
              </p>
              <p className="truncate text-xs text-slate-500">{user.email}</p>
            </div>
          </div>
          <button onClick={logout} className="nav-link nav-link-idle w-full">
            <LogOut className="h-5 w-5" />
            Sign out
          </button>
        </div>
      )}
    </div>
  );
}

export default function Layout() {
  const [mobileOpen, setMobileOpen] = useState(false);
  const location = useLocation();
  const { user } = useAuthStore();

  const title =
    PAGE_TITLES[location.pathname] ??
    (location.pathname.startsWith('/campaigns/') ? 'Campaign Detail' : 'MailFlow');

  return (
    <div className="flex h-screen overflow-hidden bg-slate-50">
      {/* ── Desktop sidebar ─────────────────────────────────────────────── */}
      <aside className="hidden w-64 shrink-0 border-r border-slate-200 bg-white lg:block">
        <SidebarContent />
      </aside>

      {/* ── Mobile sidebar (drawer) ─────────────────────────────────────── */}
      {mobileOpen && (
        <div className="fixed inset-0 z-40 lg:hidden">
          <div
            className="absolute inset-0 bg-slate-900/40 backdrop-blur-sm"
            onClick={() => setMobileOpen(false)}
            aria-hidden
          />
          <div className="absolute left-0 top-0 h-full w-64 animate-scale-in border-r border-slate-200 bg-white shadow-elevated">
            <SidebarContent onNavigate={() => setMobileOpen(false)} />
          </div>
        </div>
      )}

      {/* ── Main column ─────────────────────────────────────────────────── */}
      <div className="flex min-w-0 flex-1 flex-col">
        {/* Topbar */}
        <header className="flex h-16 shrink-0 items-center justify-between border-b border-slate-200 bg-white/80 px-4 backdrop-blur sm:px-6">
          <div className="flex items-center gap-3">
            <button
              className="btn-icon text-slate-500 hover:bg-slate-100 lg:hidden"
              onClick={() => setMobileOpen(true)}
              aria-label="Open menu"
            >
              <Menu className="h-5 w-5" />
            </button>
            <h2 className="text-sm font-semibold text-slate-900 lg:text-base">{title}</h2>
          </div>

          {user && (
            <div className="flex items-center gap-3">
              <div className="hidden text-right sm:block">
                <p className="text-xs font-semibold text-slate-900">
                  {user.firstName} {user.lastName}
                </p>
                <p className="text-xs text-slate-500">{primaryRole(user.roles)}</p>
              </div>
              <div className="flex h-9 w-9 items-center justify-center rounded-full bg-gradient-to-br from-brand-500 to-accent-500 text-xs font-semibold text-white">
                {initials(user.firstName, user.lastName)}
              </div>
            </div>
          )}
        </header>

        {/* Mobile menu close button overlay helper */}
        {mobileOpen && (
          <button
            className="fixed right-4 top-4 z-50 btn-icon bg-white text-slate-600 shadow-card lg:hidden"
            onClick={() => setMobileOpen(false)}
            aria-label="Close menu"
          >
            <X className="h-5 w-5" />
          </button>
        )}

        {/* Routed content */}
        <main className="flex-1 overflow-y-auto">
          <Outlet />
        </main>
      </div>
    </div>
  );
}
