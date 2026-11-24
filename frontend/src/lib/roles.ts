/**
 * Roles that may create, edit, delete, preview with sample data and send. This
 * mirrors the backend (SecurityConfig and @PreAuthorize): VIEWER can only read.
 */
const MANAGER_ROLES = ['ADMIN', 'CAMPAIGN_MANAGER'];

/**
 * True when the roles allow changing templates and campaigns: false for VIEWER and
 * for no roles. Pass the roles from useAuthStore. The server checks every request;
 * this only decides what the UI offers.
 */
export function canManage(roles: readonly string[] | null | undefined): boolean {
  return (roles ?? []).some((r) => MANAGER_ROLES.includes(r));
}

/**
 * Message for a request the server refused with 403, e.g. noPermissionMessage('delete templates').
 * A missing or expired session answers 401 instead, and the API client then opens the login page.
 */
export function noPermissionMessage(action: string): string {
  return `You don't have permission to ${action}. Only admins and campaign managers can do this.`;
}
