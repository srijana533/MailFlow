interface ApiErrorBody {
  message?: string;
  fieldErrors?: { field: string; message: string }[];
}

/**
 * Extracts a readable message from an API error (the backend's ApiError body),
 * including field-level validation errors when present.
 */
export function apiErrorMessage(e: unknown, fallback: string): string {
  const res = (e as { response?: { status?: number; data?: ApiErrorBody } }).response;
  const data = res?.data;
  if (data?.fieldErrors && data.fieldErrors.length > 0) {
    return data.fieldErrors.map((f) => `${f.field}: ${f.message}`).join('; ');
  }
  if (data?.message) return data.message;
  if (res?.status === 413) return 'The file is too large to upload.';
  return fallback;
}

/** HTTP status of a failed API call, or undefined when no response arrived. */
export function apiErrorStatus(e: unknown): number | undefined {
  return (e as { response?: { status?: number } }).response?.status;
}

/** True when the server refused the action for the user's role (HTTP 403). */
export function isForbidden(e: unknown): boolean {
  return apiErrorStatus(e) === 403;
}

/** Field-level validation errors of a 400 response; empty when there are none. */
export function apiFieldErrors(e: unknown): { field: string; message: string }[] {
  const data = (e as { response?: { data?: ApiErrorBody } }).response?.data;
  return Array.isArray(data?.fieldErrors) ? data.fieldErrors : [];
}
