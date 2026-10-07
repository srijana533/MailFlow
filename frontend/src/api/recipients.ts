import { apiClient } from './client';

// ── Types mirroring the backend DTOs ────────────────────────────────────────────

export type RowStatus = 'VALID' | 'INVALID' | 'DUPLICATE';

export interface ExcelRowPreview {
  rowNumber: number;
  email: string | null;
  name: string | null;
  company: string | null;
  status: RowStatus;
  reason: string | null;
  /** All mapped values (email, name, firstName, lastName, company, role, …). */
  fields: Record<string, string>;
}

export interface ExcelPreviewResponse {
  fileName: string;
  totalRows: number;
  validRows: number;
  invalidRows: number;
  duplicateRows: number;
  /** header -> target field (e.g. "First Name" -> "firstName"). */
  detectedColumns: Record<string, string>;
  unmappedColumns: string[];
  hasEmailColumn: boolean;
  rows: ExcelRowPreview[];
}

export interface BulkRecipientRow {
  email: string;
  name?: string | null;
  firstName?: string | null;
  lastName?: string | null;
  company?: string | null;
  /** Extra personalization fields: role, phone, location, interest, … */
  fields?: Record<string, string>;
}

export interface BulkRecipientRequest {
  rows: BulkRecipientRow[];
}

export interface BulkRecipientResponse {
  created: number;
  reused: number;
  skippedInvalid: number;
  recipientIds: number[];
}

// ── API ─────────────────────────────────────────────────────────────────────────

export const recipientsApi = {
  /** Validate an uploaded Excel/CSV file WITHOUT saving anything. */
  previewUpload: (file: File) => {
    const fd = new FormData();
    fd.append('file', file);
    return apiClient
      .post<ExcelPreviewResponse>('/api/recipients/import/excel/preview', fd, {
        headers: { 'Content-Type': 'multipart/form-data' },
      })
      .then((r) => r.data);
  },

  /** Create new / reuse existing recipients, returning their IDs. */
  bulkCreate: (payload: BulkRecipientRequest) =>
    apiClient
      .post<BulkRecipientResponse>('/api/recipients/bulk', payload)
      .then((r) => r.data),

  /** Download the sample .xlsx import template (triggers a browser download). */
  downloadTemplate: async () => {
    const res = await apiClient.get('/api/recipients/template/excel', {
      responseType: 'blob',
    });
    const url = window.URL.createObjectURL(res.data as Blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = 'mailflow-recipients-template.xlsx';
    document.body.appendChild(a);
    a.click();
    a.remove();
    window.URL.revokeObjectURL(url);
  },
};
