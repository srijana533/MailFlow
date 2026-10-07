package com.mailflow.batch;

/**
 * Thread-local carrier for the current campaign ID during batch execution.
 *
 * <p>Spring Batch step-scoped beans cannot easily receive constructor-injected
 * runtime values, so we use a thread-local populated before the job starts.
 * It is cleared after the job completes to prevent memory leaks.
 */
public final class BatchJobContext {

    private static final ThreadLocal<Long> CAMPAIGN_ID = new ThreadLocal<>();

    private BatchJobContext() {}

    public static void setCampaignId(Long id) {
        CAMPAIGN_ID.set(id);
    }

    public static Long getCampaignId() {
        Long id = CAMPAIGN_ID.get();
        if (id == null) {
            throw new IllegalStateException("No campaign ID in BatchJobContext");
        }
        return id;
    }

    public static void clear() {
        CAMPAIGN_ID.remove();
    }
}
