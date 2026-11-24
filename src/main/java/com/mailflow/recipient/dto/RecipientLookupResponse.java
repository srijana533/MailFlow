package com.mailflow.recipient.dto;

import java.util.List;

/**
 * Result of {@link RecipientLookupRequest}.
 *
 * @param recipients         saved (non-deleted) contacts matching the requested IDs or
 *                           emails, in any status. IDs that are missing from this list no
 *                           longer exist; emails missing from it are not saved contacts.
 * @param deletedEmails      requested emails that belong to deleted contacts. They can't
 *                           be re-added from a campaign because the address is still
 *                           reserved by the deleted record.
 * @param unsubscribedEmails requested emails (normalised) that are on the unsubscribe
 *                           list, whether or not they belong to a saved contact. They are
 *                           never emailed.
 */
public record RecipientLookupResponse(
    List<RecipientResponse> recipients,
    List<String> deletedEmails,
    List<String> unsubscribedEmails
) {}
