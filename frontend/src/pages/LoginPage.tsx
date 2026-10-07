import { useState, FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuthStore } from '../store/authStore';
import {
  Mail, Lock, AlertCircle, Eye, EyeOff,
  Zap, Users, BarChart3, CheckCircle2, Send,
} from 'lucide-react';
import toast from 'react-hot-toast';
import { Button } from '../components/ui';

const DEMO_ACCOUNTS = [
  { label: 'Admin', email: 'admin@mailflow.local', password: 'Admin@123' },
  { label: 'Manager', email: 'manager@mailflow.local', password: 'Manager@123' },
];

const FEATURES = [
  { icon: Zap, text: 'Campaign Automation' },
  { icon: Users, text: 'Smart Audience Management' },
  { icon: BarChart3, text: 'Real-time Analytics' },
];

function validateEmail(value: string): boolean {
  return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value);
}

export default function LoginPage() {
  const navigate = useNavigate();
  const login = useAuthStore((s) => s.login);

  const [email, setEmail] = useState('admin@mailflow.local');
  const [password, setPassword] = useState('Admin@123');
  const [showPassword, setShowPassword] = useState(false);
  const [remember, setRemember] = useState(true);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [fieldErrors, setFieldErrors] = useState<{ email?: string; password?: string }>({});

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setError('');

    // ── Client-side validation ──────────────────────────────────────────
    const errors: { email?: string; password?: string } = {};
    if (!email.trim()) errors.email = 'Email is required';
    else if (!validateEmail(email)) errors.email = 'Enter a valid email address';
    if (!password) errors.password = 'Password is required';
    setFieldErrors(errors);
    if (Object.keys(errors).length > 0) return;

    setLoading(true);
    try {
      // Preserve the EXISTING authentication implementation.
      await login(email, password);
      toast.success('Welcome back!');
      navigate('/dashboard');
    } catch {
      setError('Invalid email or password. Please try again.');
    } finally {
      setLoading(false);
    }
  };

  const fillDemo = (demo: { email: string; password: string }) => {
    setEmail(demo.email);
    setPassword(demo.password);
    setFieldErrors({});
    setError('');
  };

  return (
    <div className="flex min-h-screen bg-white">
      {/* ── LEFT: branding / marketing (desktop only) ─────────────────────── */}
      <div className="relative hidden w-1/2 overflow-hidden bg-gradient-to-br from-brand-700 via-brand-600 to-accent-600 lg:flex lg:flex-col">
        {/* Decorative abstract shapes */}
        <div className="pointer-events-none absolute inset-0 overflow-hidden" aria-hidden>
          <div className="absolute -left-24 -top-24 h-80 w-80 rounded-full bg-white/10 blur-2xl" />
          <div className="absolute right-0 top-1/3 h-96 w-96 rounded-full bg-accent-400/20 blur-3xl" />
          <div className="absolute -bottom-32 left-1/4 h-80 w-80 rounded-full bg-brand-400/20 blur-3xl" />
          {/* subtle grid */}
          <div
            className="absolute inset-0 opacity-[0.07]"
            style={{
              backgroundImage:
                'linear-gradient(to right, white 1px, transparent 1px), linear-gradient(to bottom, white 1px, transparent 1px)',
              backgroundSize: '48px 48px',
            }}
          />
        </div>

        <div className="relative z-10 flex h-full flex-col justify-between p-12 xl:p-16">
          {/* Logo */}
          <div className="flex items-center gap-3">
            <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-white/15 ring-1 ring-white/20 backdrop-blur">
              <Send className="h-5 w-5 text-white" />
            </div>
            <span className="text-xl font-bold text-white">MailFlow</span>
          </div>

          {/* Headline + features */}
          <div className="max-w-md">
            <h1 className="text-4xl font-bold leading-tight text-white xl:text-[2.75rem]">
              Turn every campaign into meaningful conversations.
            </h1>
            <p className="mt-5 text-lg leading-relaxed text-brand-50/90">
              Create, automate, manage and analyze your email campaigns from one powerful platform.
            </p>

            {/* Abstract automation visual */}
            <div className="mt-10 flex items-center gap-3">
              <div className="animate-float flex h-12 w-12 items-center justify-center rounded-2xl bg-white/15 ring-1 ring-white/25 backdrop-blur">
                <Mail className="h-5 w-5 text-white" />
              </div>
              <div className="h-px flex-1 bg-gradient-to-r from-white/40 to-white/5" />
              <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-white/10 ring-1 ring-white/20 backdrop-blur">
                <Zap className="h-4 w-4 text-white" />
              </div>
              <div className="h-px flex-1 bg-gradient-to-r from-white/40 to-white/5" />
              <div className="flex h-12 w-12 items-center justify-center rounded-2xl bg-white/15 ring-1 ring-white/25 backdrop-blur">
                <Users className="h-5 w-5 text-white" />
              </div>
            </div>

            <ul className="mt-10 space-y-3">
              {FEATURES.map(({ icon: Icon, text }) => (
                <li key={text} className="flex items-center gap-3 text-brand-50">
                  <span className="flex h-7 w-7 items-center justify-center rounded-full bg-white/15 ring-1 ring-white/20">
                    <CheckCircle2 className="h-4 w-4 text-white" />
                  </span>
                  <span className="flex items-center gap-2 text-sm font-medium">
                    <Icon className="h-4 w-4 text-brand-100" />
                    {text}
                  </span>
                </li>
              ))}
            </ul>
          </div>

          <p className="text-sm text-brand-100/70">
            © {new Date().getFullYear()} MailFlow — Email Campaign Automation Platform
          </p>
        </div>
      </div>

      {/* ── RIGHT: login card ─────────────────────────────────────────────── */}
      <div className="flex w-full items-center justify-center px-4 py-10 sm:px-6 lg:w-1/2">
        <div className="w-full max-w-md animate-fade-in">
          {/* Mobile logo (hidden on desktop since branding panel covers it) */}
          <div className="mb-8 flex items-center gap-3 lg:hidden">
            <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-gradient-to-br from-brand-600 to-accent-600">
              <Send className="h-5 w-5 text-white" />
            </div>
            <div>
              <span className="text-lg font-bold text-slate-900">MailFlow</span>
              <p className="text-xs text-slate-500">Email Campaign Automation Platform</p>
            </div>
          </div>

          <div className="mb-7">
            <h2 className="text-2xl font-bold tracking-tight text-slate-900">Welcome back</h2>
            <p className="mt-1 text-sm text-slate-500">Sign in to your MailFlow account</p>
          </div>

          {/* Auth error */}
          {error && (
            <div
              role="alert"
              className="mb-5 flex items-start gap-2.5 rounded-lg border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700"
            >
              <AlertCircle className="mt-0.5 h-4 w-4 shrink-0" />
              <span>{error}</span>
            </div>
          )}

          <form onSubmit={handleSubmit} noValidate className="space-y-5">
            {/* Email */}
            <div>
              <label htmlFor="email" className="label">Email address</label>
              <div className="relative">
                <Mail className="pointer-events-none absolute left-3.5 top-1/2 h-4 w-4 -translate-y-1/2 text-slate-400" />
                <input
                  id="email"
                  name="email"
                  type="email"
                  autoComplete="email"
                  value={email}
                  onChange={(e) => {
                    setEmail(e.target.value);
                    if (fieldErrors.email) setFieldErrors((p) => ({ ...p, email: undefined }));
                  }}
                  className={`input pl-10 ${fieldErrors.email ? 'border-red-400 focus:border-red-500 focus:ring-red-500/10' : ''}`}
                  placeholder="you@company.com"
                  aria-invalid={!!fieldErrors.email}
                  aria-describedby={fieldErrors.email ? 'email-error' : undefined}
                />
              </div>
              {fieldErrors.email && (
                <p id="email-error" className="mt-1.5 text-xs text-red-600">{fieldErrors.email}</p>
              )}
            </div>

            {/* Password */}
            <div>
              <div className="flex items-center justify-between">
                <label htmlFor="password" className="label">Password</label>
                <button
                  type="button"
                  className="mb-1.5 text-xs font-medium text-brand-600 hover:text-brand-700 hover:underline"
                  onClick={() => toast('Please contact your administrator to reset your password.', { icon: '🔑' })}
                >
                  Forgot password?
                </button>
              </div>
              <div className="relative">
                <Lock className="pointer-events-none absolute left-3.5 top-1/2 h-4 w-4 -translate-y-1/2 text-slate-400" />
                <input
                  id="password"
                  name="password"
                  type={showPassword ? 'text' : 'password'}
                  autoComplete="current-password"
                  value={password}
                  onChange={(e) => {
                    setPassword(e.target.value);
                    if (fieldErrors.password) setFieldErrors((p) => ({ ...p, password: undefined }));
                  }}
                  className={`input pl-10 pr-11 ${fieldErrors.password ? 'border-red-400 focus:border-red-500 focus:ring-red-500/10' : ''}`}
                  placeholder="Enter your password"
                  aria-invalid={!!fieldErrors.password}
                  aria-describedby={fieldErrors.password ? 'password-error' : undefined}
                />
                <button
                  type="button"
                  onClick={() => setShowPassword((v) => !v)}
                  className="absolute right-2 top-1/2 flex h-7 w-7 -translate-y-1/2 items-center justify-center rounded-md text-slate-400 transition hover:bg-slate-100 hover:text-slate-600 focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-500"
                  aria-label={showPassword ? 'Hide password' : 'Show password'}
                >
                  {showPassword ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
                </button>
              </div>
              {fieldErrors.password && (
                <p id="password-error" className="mt-1.5 text-xs text-red-600">{fieldErrors.password}</p>
              )}
            </div>

            {/* Remember me */}
            <div className="flex items-center">
              <label className="flex cursor-pointer select-none items-center gap-2 text-sm text-slate-600">
                <input
                  type="checkbox"
                  checked={remember}
                  onChange={(e) => setRemember(e.target.checked)}
                  className="h-4 w-4 rounded border-slate-300 text-brand-600 focus:ring-2 focus:ring-brand-500/40"
                />
                Remember me
              </label>
            </div>

            <Button type="submit" loading={loading} className="w-full" size="md">
              {loading ? 'Signing in…' : 'Sign in'}
            </Button>
          </form>

          {/* Demo credentials */}
          <div className="mt-7 rounded-xl border border-slate-200 bg-slate-50 p-4">
            <p className="mb-2.5 text-xs font-semibold uppercase tracking-wide text-slate-500">
              Demo credentials
            </p>
            <div className="space-y-2">
              {DEMO_ACCOUNTS.map((demo) => (
                <button
                  key={demo.email}
                  type="button"
                  onClick={() => fillDemo(demo)}
                  className="group flex w-full items-center justify-between rounded-lg border border-slate-200 bg-white px-3 py-2 text-left transition hover:border-brand-300 hover:bg-brand-50/50"
                >
                  <span>
                    <span className="block text-xs font-semibold text-slate-700">{demo.label}</span>
                    <span className="block text-xs text-slate-500">{demo.email}</span>
                  </span>
                  <span className="text-xs font-medium text-brand-600 opacity-0 transition group-hover:opacity-100">
                    Use →
                  </span>
                </button>
              ))}
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
