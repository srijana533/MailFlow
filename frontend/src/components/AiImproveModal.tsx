import { useEffect, useState } from 'react';
import { useMutation } from '@tanstack/react-query';
import toast from 'react-hot-toast';
import { Sparkles, AlertCircle } from 'lucide-react';
import { aiApi, type AiTone, type ImprovedEmail } from '../api/ai';
import { Modal, Button } from './ui';

interface AiImproveModalProps {
  open: boolean;
  onClose: () => void;
  /** Current draft subject + body to improve. */
  initialSubject: string;
  initialBody: string;
  /** Called when the user accepts the (possibly edited) improved draft. */
  onAccept: (result: ImprovedEmail) => void;
}

const TONES: { value: AiTone; label: string }[] = [
  { value: 'professional', label: 'Professional' },
  { value: 'friendly', label: 'Friendly' },
  { value: 'persuasive', label: 'Persuasive' },
  { value: 'concise', label: 'Concise' },
];

export function AiImproveModal({
  open, onClose, initialSubject, initialBody, onAccept,
}: AiImproveModalProps) {
  const [purpose, setPurpose] = useState('');
  const [audience, setAudience] = useState('');
  const [tone, setTone] = useState<AiTone>('professional');
  const [keyPoints, setKeyPoints] = useState('');

  // The improved draft (editable before accepting).
  const [result, setResult] = useState<ImprovedEmail | null>(null);
  const [editSubject, setEditSubject] = useState('');
  const [editBody, setEditBody] = useState('');

  useEffect(() => {
    if (open) {
      setPurpose('');
      setAudience('');
      setTone('professional');
      setKeyPoints('');
      setResult(null);
      setEditSubject('');
      setEditBody('');
    }
  }, [open]);

  const improve = useMutation({
    mutationFn: () =>
      aiApi.improveEmail({
        subject: initialSubject,
        body: initialBody,
        purpose: purpose.trim() || undefined,
        audience: audience.trim() || undefined,
        tone,
        keyPoints: keyPoints.trim() || undefined,
      }),
    onSuccess: (r) => {
      setResult(r);
      setEditSubject(r.subject);
      setEditBody(r.body);
    },
    onError: (e: unknown) => {
      const msg =
        (e as { response?: { data?: { message?: string } } }).response?.data?.message ??
        'AI could not improve this draft. Please try again.';
      toast.error(msg);
    },
  });

  const canImprove = initialSubject.trim().length > 0 && initialBody.trim().length > 0;

  const handleAccept = () => {
    onAccept({ subject: editSubject, body: editBody });
    toast.success('AI draft applied — review before sending');
    onClose();
  };

  return (
    <Modal
      open={open}
      onClose={onClose}
      title="Improve with AI"
      description="AI polishes your draft for clarity and tone. You review and edit before anything is used — nothing is sent automatically."
      widthClass="max-w-2xl"
      footer={
        result ? (
          <>
            <Button variant="secondary" type="button" onClick={() => setResult(null)}>
              Back
            </Button>
            <Button type="button" onClick={handleAccept}>
              Use this draft
            </Button>
          </>
        ) : (
          <>
            <Button variant="secondary" type="button" onClick={onClose} disabled={improve.isPending}>
              Cancel
            </Button>
            <Button
              type="button"
              onClick={() => improve.mutate()}
              loading={improve.isPending}
              disabled={!canImprove || improve.isPending}
            >
              <Sparkles className="h-4 w-4" />
              {improve.isPending ? 'Improving…' : 'Improve'}
            </Button>
          </>
        )
      }
    >
      {!canImprove && (
        <div className="mb-4 flex items-start gap-2 rounded-lg border border-amber-200 bg-amber-50 px-3 py-2 text-xs text-amber-700">
          <AlertCircle className="mt-0.5 h-4 w-4 shrink-0" />
          <span>Enter a subject and body first, then use AI to improve them.</span>
        </div>
      )}

      {!result ? (
        /* ── Input / context step ─────────────────────────────────────── */
        <div className="space-y-4">
          <div className="rounded-lg border border-slate-100 bg-slate-50 p-3">
            <p className="text-xs font-medium text-slate-400">Current subject</p>
            <p className="text-sm text-slate-700">{initialSubject || <span className="italic text-slate-400">— empty —</span>}</p>
            <p className="mt-2 text-xs font-medium text-slate-400">Current body (preview)</p>
            <p className="line-clamp-3 whitespace-pre-wrap text-sm text-slate-600">
              {initialBody || <span className="italic text-slate-400">— empty —</span>}
            </p>
          </div>

          <div>
            <label className="label">Tone</label>
            <div className="flex flex-wrap gap-2">
              {TONES.map((t) => (
                <button
                  key={t.value}
                  type="button"
                  onClick={() => setTone(t.value)}
                  className={`rounded-lg border px-3 py-1.5 text-sm transition ${
                    tone === t.value
                      ? 'border-brand-400 bg-brand-50 text-brand-700'
                      : 'border-slate-200 text-slate-600 hover:border-slate-300'
                  }`}
                >
                  {t.label}
                </button>
              ))}
            </div>
          </div>

          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <div>
              <label htmlFor="ai-purpose" className="label">Campaign purpose <span className="text-slate-400">(optional)</span></label>
              <input id="ai-purpose" className="input" placeholder="e.g. invite to a webinar"
                     value={purpose} onChange={(e) => setPurpose(e.target.value)} />
            </div>
            <div>
              <label htmlFor="ai-audience" className="label">Target audience <span className="text-slate-400">(optional)</span></label>
              <input id="ai-audience" className="input" placeholder="e.g. final-year CS students"
                     value={audience} onChange={(e) => setAudience(e.target.value)} />
            </div>
          </div>

          <div>
            <label htmlFor="ai-keypoints" className="label">Key points to keep <span className="text-slate-400">(optional)</span></label>
            <textarea id="ai-keypoints" className="input min-h-[60px] resize-y"
                      placeholder="e.g. event date, venue, registration link"
                      value={keyPoints} onChange={(e) => setKeyPoints(e.target.value)} />
          </div>

          <p className="text-xs text-slate-400">
            AI preserves your meaning, facts, links and merge variables like {'{{Name}}'}. It won't invent offers or recipient details.
          </p>
        </div>
      ) : (
        /* ── Review step (editable) ───────────────────────────────────── */
        <div className="space-y-4">
          <div className="flex items-center gap-2 rounded-lg border border-emerald-200 bg-emerald-50 px-3 py-2 text-xs text-emerald-700">
            <Sparkles className="h-4 w-4 shrink-0" />
            <span>Review the improved draft. Edit anything, then click “Use this draft”.</span>
          </div>
          <div>
            <label htmlFor="ai-out-subject" className="label">Improved subject</label>
            <input id="ai-out-subject" className="input"
                   value={editSubject} onChange={(e) => setEditSubject(e.target.value)} />
          </div>
          <div>
            <label htmlFor="ai-out-body" className="label">Improved body</label>
            <textarea id="ai-out-body" className="input min-h-[200px] resize-y font-mono text-sm"
                      value={editBody} onChange={(e) => setEditBody(e.target.value)} />
          </div>
        </div>
      )}
    </Modal>
  );
}
