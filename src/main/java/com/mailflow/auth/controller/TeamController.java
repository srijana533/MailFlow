package com.mailflow.auth.controller;

import com.mailflow.auth.dto.CreateTeamMemberRequest;
import com.mailflow.auth.dto.TeamMemberResponse;
import com.mailflow.auth.dto.UpdateTeamMemberRequest;
import com.mailflow.auth.entity.User;
import com.mailflow.auth.service.TeamService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Team management for a company's Admin. Also restricted to ADMIN in SecurityConfig. */
@RestController
@RequestMapping("/api/team")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Team", description = "Manage the people in your company (Admin only)")
public class TeamController {

    private final TeamService teamService;

    @GetMapping
    @Operation(summary = "List the people in your company")
    public List<TeamMemberResponse> list(@AuthenticationPrincipal User admin) {
        return teamService.list(admin);
    }

    @PostMapping
    @Operation(summary = "Add a person to your company with a role and a starting password")
    public ResponseEntity<TeamMemberResponse> add(@AuthenticationPrincipal User admin,
                                                  @Valid @RequestBody CreateTeamMemberRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(teamService.add(admin, request));
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Change a person's role, or disable / re-enable their account")
    public TeamMemberResponse update(@AuthenticationPrincipal User admin,
                                     @PathVariable Long id,
                                     @Valid @RequestBody UpdateTeamMemberRequest request) {
        return teamService.update(admin, id, request);
    }
}
