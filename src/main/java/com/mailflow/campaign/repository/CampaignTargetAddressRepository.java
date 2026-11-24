package com.mailflow.campaign.repository;

import com.mailflow.campaign.entity.CampaignTargetAddress;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface CampaignTargetAddressRepository extends JpaRepository<CampaignTargetAddress, Long> {

    List<CampaignTargetAddress> findByCampaignIdOrderByIdAsc(Long campaignId);

    /**
     * Deletes one campaign's campaign-only address list (when the campaign is deleted)
     * and returns how many rows were removed. Only rows of this campaign are touched.
     */
    @Modifying
    @Query("DELETE FROM CampaignTargetAddress t WHERE t.campaignId = :campaignId")
    int deleteByCampaignId(@Param("campaignId") Long campaignId);

    long countByCampaignId(Long campaignId);

    /** Rows of {@code [campaignId, count]} for the given campaigns (campaigns without addresses are absent). */
    @Query("""
           SELECT t.campaignId, COUNT(t) FROM CampaignTargetAddress t
           WHERE t.campaignId IN :campaignIds
           GROUP BY t.campaignId
           """)
    List<Object[]> countByCampaignIds(@Param("campaignIds") Collection<Long> campaignIds);
}
