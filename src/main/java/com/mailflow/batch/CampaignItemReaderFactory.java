package com.mailflow.batch;

/**
 * Retained as an empty placeholder so that any references in comments or
 * documentation still resolve.  The actual reader bean is defined in
 * {@link CampaignBatchConfig#campaignItemReader(Long)} so that {@code @StepScope}
 * late-binding works correctly through Spring's CGLIB proxy.
 *
 * <p>This class is intentionally empty and not registered as a Spring bean.
 */
public final class CampaignItemReaderFactory {
    private CampaignItemReaderFactory() {}
}
