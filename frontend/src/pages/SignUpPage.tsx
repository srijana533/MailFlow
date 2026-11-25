import { useState, FormEvent } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { AlertCircle, Building2, Lock, Mail, Send, User } from 'lucide-react';
import toast from 'react-hot-toast';
import { useAuthStore } from '../store/authStore';
import { Button } from '../components/ui';
import { apiErrorMessage, apiErrorStatus } from '../lib/errors';

interface Fields {
  companyName: string;
  firstName: string;
  lastName: string;
  email: string;
  password: string;
}

type FieldErrors = Partial<Record<keyof Fields, string>>;

function validate(f: Fields): FieldErrors {
  const errors: FieldErrors = {};
  if (!f.companyName.trim()) errors.companyName = 'Company name is required';
  if (!f.firstName.trim()) errors.firstName = 'First name is required';
  if (!f.lastName.trim()) errors.lastName = 'Last name is required';
  if (!f.email.trim()) errors.email = 'Email is required';
  else if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(f.email.trim())) errors.email = 'Enter a valid email address';
  if (f.password.length < 8) errors.password = 'Use at least 8 characters';
  return errors;
}

/**
 * Self sign-up. Creates a new, separate company workspace with the person as its
 * Admin; they can then add teammates on the Team page. Nobody joins an existing
 * company by signing up.
 */
export default function SignUpPage() {
  const navigate = useNavigate();
  const signUp = useAuthStore((s) => s.signUp);
  const [fields, setFields] = useState<Fields>({
    companyName: '', firstName: '', lastName: '', email: '', password: '',
  });
  const [fieldErrors, setFieldErrors] = useState<FieldErrors>({});
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);

  const set = (name: keyof Fields) => (e: React.ChangeEvent<HTMLInputElement>) => {
    setFields((f) => ({ ...f, [name]: e.target.value }));
    if (fieldErrors[name]) setFieldErrors((p) => ({ ...p, [name]: undefined }));
  };

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setError('');
    const errors = validate(fields);
    setFieldErrors(errors);
    if (Object.keys(errors).length > 0) return;

    setLoading(true);
    try {
      await signUp({
        companyName: fields.companyName.trim(),
        firstName: fields.firstName.trim(),
        lastName: fields.lastName.trim(),
        email: fields.email.trim(),
        password: fields.password,
      });
      toast.success(`Welcome to MailFlow, ${fields.firstName.trim()}!`);
      navigate('/dashboard');
    } catch (err) {
      setError(apiErrorStatus(err) === undefined
        ? 'Could not reach the server. Check your connection and try again.'
        : apiErrorMessage(err, 'Sign-up failed. Please try again.'));
    } finally {
      setLoading(false);
    }
  };

  const input = (name: keyof Fields, label: string, opts: {
    type?: string; placeholder?: string; autoComplete?: string; icon: typeof Mail;
  }) => {
    const Icon = opts.icon;
    const err = fieldErrors[name];
    return (
      <div>
        <label htmlFor={name} className="label">{label}</label>
        <div className="relative">
          <Icon className="pointer-events-none absolute left-3.5 top-1/2 h-4 w-4 -translate-y-1/2 text-slate-400" />
          <input
            id={name}
            name={name}
            type={opts.type ?? 'text'}
            autoComplete={opts.autoComplete}
            value={fields[name]}
            onChange={set(name)}
            placeholder={opts.placeholder}
            className={`input pl-10 ${err ? 'border-red-400 focus:border-red-500 focus:ring-red-500/10' : ''}`}
            aria-invalid={!!err}
            aria-describedby={err ? `${name}-error` : undefined}
          />
        </div>
        {err && <p id={`${name}-error`} className="mt-1.5 text-xs text-red-600">{err}</p>}
      </div>
    );
  };

  return (
    <div className="flex min-h-screen items-center justify-center bg-slate-50 px-4 py-10">
      <div className="w-full max-w-md animate-fade-in rounded-2xl border border-slate-200 bg-white p-8 shadow-card">
        <div className="mb-6 flex items-center gap-3">
          <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-gradient-to-br from-brand-600 to-accent-600">
            <Send className="h-5 w-5 text-white" />
          </div>
          <span className="text-lg font-bold text-slate-900">MailFlow</span>
        </div>

        <h1 className="text-2xl font-bold tracking-tight text-slate-900">Create your company workspace</h1>
        <p className="mt-1 text-sm text-slate-500">
          You'll be the admin. Your contacts, templates and campaigns are private to your company.
        </p>

        {error && (
          <div role="alert" className="mt-5 flex items-start gap-2.5 rounded-lg border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700">
            <AlertCircle className="mt-0.5 h-4 w-4 shrink-0" />
            <span>{error}</span>
          </div>
        )}

        <form onSubmit={handleSubmit} noValidate className="mt-6 space-y-4">
          {input('companyName', 'Company name', { icon: Building2, placeholder: 'Acme Ltd', autoComplete: 'organization' })}
          <div className="grid grid-cols-2 gap-3">
            {input('firstName', 'First name', { icon: User, autoComplete: 'given-name' })}
            {input('lastName', 'Last name', { icon: User, autoComplete: 'family-name' })}
          </div>
          {input('email', 'Work email', { icon: Mail, type: 'email', placeholder: 'you@company.com', autoComplete: 'email' })}
          {input('password', 'Password', { icon: Lock, type: 'password', placeholder: 'At least 8 characters', autoComplete: 'new-password' })}
          <Button type="submit" loading={loading} className="w-full" size="md">
            {loading ? 'Creating workspace…' : 'Create workspace'}
          </Button>
        </form>

        <p className="mt-6 text-center text-sm text-slate-600">
          Already have an account?{' '}
          <Link to="/login" className="font-semibold text-brand-600 hover:text-brand-700 hover:underline">Sign in</Link>
        </p>
      </div>
    </div>
  );
}
