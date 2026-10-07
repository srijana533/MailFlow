package com.mailflow.analytics.controller;

import com.mailflow.analytics.AnalyticsService;
import com.mailflow.analytics.dto.CampaignAnalytics;
import com.mailflow.analytics.dto.DashboardSummary;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/analytics")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Analytics", description = "Campaign performance and system analytics")
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    @GetMapping("/dashboard")
    @Operation(summary = "Get overall dashboard summary")
    public ResponseEntity<DashboardSummary> dashboard() {
        return ResponseEntity.ok(analyticsService.getDashboardSummary());
    }

    @GetMapping("/campaigns/{id}")
    @Operation(summary = "Get analytics for a specific campaign")
    public ResponseEntity<CampaignAnalytics> campaignAnalytics(@PathVariable Long id) {
        return ResponseEntity.ok(analyticsService.getCampaignAnalytics(id));
    }

    @GetMapping("/volume/daily")
    @Operation(summary = "Get daily email volume for the past N days")
    public ResponseEntity<List<Map<String, Object>>> dailyVolume(
            @RequestParam(defaultValue = "30") int days) {
        return ResponseEntity.ok(analyticsService.getDailyVolume(days));
    }
}
