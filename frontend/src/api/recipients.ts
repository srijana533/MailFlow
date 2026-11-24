import { apiClient } from './client';
import type { PageResponse, Recipient } from '../types';

// ── Types mirroring the backend DTOs ────────────────────────────────────────────

/**
 * VALID     – new address (not a saved contact yet)
 * EXISTING  – belongs to a saved contact that can be emailed; that contact is reused
 * SKIPPED   – valid, but never emailed: unsubscribed, or a saved contact that bounced
 *             or is marked invalid (the reason says which)
 * DUPLICATE – repeats an address from an earlier row of the same file
 * INVALID   – missing/malformed address, no email column, or a deleted contact
 *
 * Earlier campaigns play no part: a saved contact can be in any number of campaigns.
 */
export type RowStatus = 'VALID' | 'EXISTING' | 'SKIPPED' | 'INVALID' | 'DUPLICATE';

export interface ExcelRowPreview {
  rowNumber: number;
  email: string | null;
  name: string | null;
  company: string | null;
  status: RowStatus;
  reason: string | null;
  /** All mapped values (email, name, firstName, lastName, company, role, …). */
  fields: Record<string, string>;
  /** Saved contact ID when status is EXISTING (or SKIPPED for a saved contact). */
  recipientId: number | null;
}

/** totalRows = validRows + invalidRows + duplicateRows + skippedRows; validRows = newRows + existingRows. */
export interface ExcelPreviewResponse {
  fileName: string;
  totalRows: number;
  /** Rows that can be emailed: new addresses plus saved contacts. */
  validRows: number;
  /** Valid rows that are new addresses. */
  newRows: number;
  /** Valid rows that are saved contacts. */
  existingRows: number;
  invalidRows: number;
  /** Repeats of an earlier row in the same file. */
  duplicateRows: number;
  /** Valid addresses that won't be emailed (unsubscribed, bounced, marked invalid). */
  skippedRows: number;
  /** All header names in file order. */
  columns: string[];
  /** header -> target field (e.g. "First Name" -> "firstName", "Team" -> "custom:Team"). */
  detectedColumns: Record<string, string>;
  unmappedColumns: string[];
  hasEmailColumn: boolean;
  rows: ExcelRowPreview[];
}

/** Column mapping override: header -> email | name | firstName | lastName | company | custom:<Key> | ignore */
export type ColumnMapping = Record<string, string>;

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
  /** Recorded as the new contacts' import source. */
  source?: 'UPLOAD' | 'MANUAL';
}

export interface BulkRecipientResponse {
  created: number;
  reused: number;
  skippedInvalid: number;
  skippedDeleted: number;
  recipientIds: number[];
}

export interface RecipientLookupResponse {
  /** Saved (non-deleted) contacts matching the requested IDs or emails, any status. */
  recipients: Recipient[];
  /** Requested emails that belong to deleted contacts and can't be used. */
  deletedEmails: string[];
  /** Requested emails on the unsubscribe list (contacts or not); never emailed. */
  unsubscribedEmails: string[];
}

/** Upload limits enforced by the backend (kept in sync for early client-side checks). */
export const UPLOAD_MAX_BYTES = 5 * 1024 * 1024;
export const UPLOAD_MAX_ROWS = 10_000;

// ── API ─────────────────────────────────────────────────────────────────────────

export const recipientsApi = {
  /** List existing (saved) recipients with optional search + pagination. */
  list: (params?: { search?: string; status?: string; page?: number; size?: number }) =>
    apiClient
      .get<PageResponse<Recipient>>('/api/recipients', { params })
      .then((r) => r.data),

  /** Read-only lookup of saved contacts by ID and/or email. Creates nothing. */
  lookup: (body: { ids?: number[]; emails?: string[] }) =>
    apiClient
      .post<RecipientLookupResponse>('/api/recipients/lookup', body)
      .then((r) => r.data),

  /**
   * Validate an uploaded Excel/CSV file WITHOUT saving anything.
   * `mapping` optionally overrides the automatic column detection.
   */
  previewUpload: (file: File, mapping?: ColumnMapping) => {
    const fd = new FormData();
    fd.append('file', file);
    if (mapping && Object.keys(mapping).length > 0) {
      fd.append('mapping', JSON.stringify(mapping));
    }
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
