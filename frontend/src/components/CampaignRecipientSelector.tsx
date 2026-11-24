import { useCallback, useMemo, useState, type Dispatch, type SetStateAction } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Users, FileSpreadsheet, Keyboard, X, Search, Layers, Table2 } from 'lucide-react';
import { recipientsApi } from '../api/recipients';
import {
  combineRecipients,
  keptUploadRows,
  otherSourcesOfTableRows,
  removeAddress,
  skipReason,
  SOURCE_LABEL,
  usableManualRows,
  type CombinedRecipient,
  type ManualEntry,
  type RecipientSelection,
  type UploadSelection,
} from '../lib/recipientSelection';
import { isBlankRow } from '../lib/manualTable';
import { SavedRecipientPicker } from './SavedRecipientPicker';
import { RecipientExcelUpload } from './RecipientExcelUpload';
import { ManualRecipientEntry } from './ManualRecipientEntry';

interface CampaignRecipientSelectorProps {
  value: RecipientSelection;
  /** Accepts an updater: the manual-entry table updates rows when saved-contact checks answer. */
  onChange: Dispatch<SetStateAction<RecipientSelection>>;
  /** Placeholders used by the email content (for upload column coverage). */
  templatePlaceholders?: string[];
}

type Tab = 'saved' | 'upload' | 'manual';

const LIST_PAGE = 50;

/**
 * Recipient selection with three sources that can be combined:
 * saved contacts, an uploaded Excel/CSV file, and the "Add emails manually" table (plus,
 * when a draft is edited, the campaign-only addresses stored with it).
 * The final list below the tabs is de-duplicated by email address.
 */
export function CampaignRecipientSelector({ value, onChange, templatePlaceholders }: CampaignRecipientSelectorProps) {
  const [chosenTab, setChosenTab] = useState<Tab | null>(null);
  // "Fill in a table" on the upload panel shows the manual-entry table below it.
  const [tableInUpload, setTableInUpload] = useState(false);

  // Default tab: manual entry when the table already has rows or there are no saved
  // contacts to pick from; saved contacts otherwise.
  const activeContacts = useQuery({
    queryKey: ['recipients', 'active-count'],
    queryFn: () => recipientsApi.list({ status: 'ACTIVE', size: 1 }),
    staleTime: 60_000,
  });
  const tableHasRows = value.manual.some((r) => !isBlankRow(r));
  const defaultTab: Tab =
    tableHasRows || (activeContacts.data && activeContacts.data.totalElements === 0) ? 'manual' : 'saved';
  const tab = chosenTab ?? defaultTab;

  const setManual = useCallback(
    (update: (rows: ManualEntry[]) => ManualEntry[]) => onChange((sel) => ({ ...sel, manual: update(sel.manual) })),
    [onChange],
  );
  const setSaved = useCallback(
    (saved: RecipientSelection['saved']) => onChange((sel) => ({ ...sel, saved })),
    [onChange],
  );
  const setUpload = useCallback(
    (upload: UploadSelection | null) => onChange((sel) => ({ ...sel, upload })),
    [onChange],
  );

  const { list, mergedDuplicates } = useMemo(() => combineRecipients(value), [value]);
  const otherSources = useMemo(() => otherSourcesOfTableRows(value), [value]);
  const counts = {
    saved: Object.keys(value.saved).length,
    upload: keptUploadRows(value.upload).length,
    manual: usableManualRows(value.manual).length,
  };

  const tabs: { key: Tab; label: string; icon: React.ElementType; count: number }[] = [
    { key: 'saved', label: 'Saved contacts', icon: Users, count: counts.saved },
    { key: 'upload', label: 'Upload file', icon: FileSpreadsheet, count: counts.upload },
    { key: 'manual', label: 'Add emails manually', icon: Keyboard, count: counts.manual },
  ];

  return (
    <div className="space-y-5">
      <div role="tablist" aria-label="Recipient sources" className="flex flex-wrap gap-1 rounded-xl bg-slate-100 p-1">
        {tabs.map(({ key, label, icon: Icon, count }) => (
          <button
            key={key}
            type="button"
            role="tab"
            aria-selected={tab === key}
            onClick={() => setChosenTab(key)}
            className={`flex flex-1 items-center justify-center gap-2 rounded-lg px-3 py-2 text-sm font-medium transition ${
              tab === key ? 'bg-white text-slate-900 shadow-sm' : 'text-slate-500 hover:text-slate-700'
            }`}
          >
            <Icon className="h-4 w-4" /> {label}
            {count > 0 && (
              <span className="rounded-full bg-brand-100 px-2 py-0.5 text-xs font-semibold text-brand-700">
                {count.toLocaleString()}
              </span>
            )}
          </button>
        ))}
      </div>

      {/* Panels stay mounted so in-progress input (search text, table rows, pasted text) survives tab switches. */}
      <div className={tab === 'saved' ? '' : 'hidden'}>
        <SavedRecipientPicker selected={value.saved} onChange={setSaved} />
      </div>
      <div className={tab === 'upload' ? 'space-y-4' : 'hidden'}>
        <RecipientExcelUpload
          value={value.upload}
          onChange={setUpload}
          templatePlaceholders={templatePlaceholders}
          onFillTable={() => setTableInUpload(true)}
        />
        {tableInUpload && (
          <div className="space-y-2">
            <div className="flex items-center justify-between gap-2">
              <h4 className="flex items-center gap-2 text-sm font-semibold text-slate-900">
                <Table2 className="h-4 w-4 text-brand-500" /> Fill in a table
              </h4>
              <button
                type="button"
                className="text-xs font-medium text-slate-500 hover:text-slate-700"
                onClick={() => setTableInUpload(false)}
              >
                Hide table
              </button>
            </div>
            <p className="text-xs text-slate-500">
              The same columns as the template: Email, Name and Company. These rows are the same as on the
              "Add emails manually" tab.
            </p>
            <ManualRecipientEntry rows={value.manual} onChange={setManual} otherSources={otherSources} />
          </div>
        )}
      </div>
      <div className={tab === 'manual' ? '' : 'hidden'}>
        <ManualRecipientEntry rows={value.manual} onChange={setManual} otherSources={otherSources} />
      </div>

      <CombinedRecipientList
        list={list}
        mergedDuplicates={mergedDuplicates}
        onRemove={(email) => onChange((sel) => removeAddress(sel, email))}
      />
    </div>
  );
}

function CombinedRecipientList({
  list,
  mergedDuplicates,
  onRemove,
}: {
  list: CombinedRecipient[];
  mergedDuplicates: number;
  onRemove: (email: string) => void;
}) {
  const [query, setQuery] = useState('');
  const [visible, setVisible] = useState(LIST_PAGE);

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (!q) return list;
    return list.filter(
      (r) =>
        r.email.includes(q) ||
        (r.name ?? '').toLowerCase().includes(q) ||
        [r.firstName, r.lastName].filter(Boolean).join(' ').toLowerCase().includes(q) ||
        (r.company ?? '').toLowerCase().includes(q),
    );
  }, [list, query]);

  const saved = list.filter((r) => r.recipientId != null).length;
  // Addresses that aren't saved contacts are used for this campaign only, unless
  // the user chooses on the Review step to save them as contacts.
  const campaignOnly = list.length - saved;
  const inactive = list.filter((r) => skipReason(r.status) !== null).length;

  return (
    <div className="rounded-xl border border-slate-200 bg-white">
      <div className="flex flex-wrap items-center justify-between gap-3 border-b border-slate-100 px-4 py-3">
        <div className="flex items-center gap-2">
          <Layers className="h-4 w-4 text-brand-500" />
          <h4 className="text-sm font-semibold text-slate-900">
            Final recipient list: {list.length.toLocaleString()}
          </h4>
        </div>
        <div className="flex flex-wrap gap-1.5 text-xs">
          <span className="badge bg-brand-50 text-brand-700">{saved.toLocaleString()} saved contacts</span>
          <span
            className="badge bg-emerald-50 text-emerald-700"
            title="Not saved as contacts: used for this campaign only unless you save them as contacts on the Review step"
          >
            {campaignOnly.toLocaleString()} campaign-only
          </span>
          {inactive > 0 && <span className="badge bg-amber-50 text-amber-700">{inactive} will be skipped</span>}
        </div>
      </div>

      {list.length === 0 ? (
        <p className="px-4 py-6 text-center text-sm text-slate-400">
          No recipients yet. Select saved contacts, upload a file or add emails manually. You can also save the draft now and add recipients later.
        </p>
      ) : (
        <>
          <div className="space-y-2 px-4 pt-3">
            {mergedDuplicates > 0 && (
              <p className="text-xs text-slate-500">
                {mergedDuplicates.toLocaleString()} address{mergedDuplicates === 1 ? '' : 'es'} came from more than one source and {mergedDuplicates === 1 ? 'is' : 'are'} listed once. Each address is emailed at most once.
              </p>
            )}
            {list.length > 10 && (
              <div className="relative max-w-xs">
                <Search className="pointer-events-none absolute left-3 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-slate-400" />
                <input
                  className="input py-1.5 pl-8 text-xs"
                  placeholder="Find in list…"
                  value={query}
                  onChange={(e) => { setQuery(e.target.value); setVisible(LIST_PAGE); }}
                  aria-label="Find in final recipient list"
                />
              </div>
            )}
          </div>
          <div className="max-h-72 overflow-y-auto px-4 py-2">
            <table className="w-full text-left text-sm">
              <tbody className="divide-y divide-slate-100">
                {filtered.slice(0, visible).map((r) => (
                  <tr key={r.email}>
                    <td className="py-2 pr-3">
                      <p className="font-medium text-slate-700">{r.email}</p>
                      <p className="text-xs text-slate-400">
                        {[r.name || [r.firstName, r.lastName].filter(Boolean).join(' '), r.company].filter(Boolean).join(' · ') || '—'}
                      </p>
                    </td>
                    <td className="py-2 pr-3">
                      <div className="flex flex-wrap gap-1">
                        {r.sources.map((s) => (
                          <span key={s} className="rounded bg-slate-100 px-1.5 py-0.5 text-[11px] text-slate-600">{SOURCE_LABEL[s]}</span>
                        ))}
                      </div>
                    </td>
                    <td className="py-2 pr-3 text-xs">
                      {r.recipientId != null ? (
                        skipReason(r.status)
                          ? <span className="text-amber-700" title="Skipped when sending">{skipReason(r.status)}: won't be emailed</span>
                          : <span className="text-brand-700">Saved contact</span>
                      ) : (
                        <span className="text-emerald-700" title="Not saved as a contact">Campaign-only</span>
                      )}
                    </td>
                    <td className="py-2 text-right">
                      <button
                        type="button"
                        onClick={() => onRemove(r.email)}
                        className="text-slate-300 transition hover:text-red-500"
                        aria-label={`Remove ${r.email} from this campaign`}
                        title="Remove from this campaign"
                      >
                        <X className="h-4 w-4" />
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            {filtered.length > visible && (
              <button
                type="button"
                className="mt-2 w-full rounded-lg bg-slate-50 py-1.5 text-xs font-medium text-brand-600 hover:bg-slate-100"
                onClick={() => setVisible((v) => v + LIST_PAGE * 2)}
              >
                Show more ({(filtered.length - visible).toLocaleString()} remaining)
              </button>
            )}
            {filtered.length === 0 && (
              <p className="py-4 text-center text-xs text-slate-400">No recipients match “{query}”.</p>
            )}
          </div>
        </>
      )}
    </div>
  );
}
