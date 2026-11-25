package com.mailflow.auth.service;

import com.mailflow.auth.RoleLevels;
import com.mailflow.auth.dto.CreateTeamMemberRequest;
import com.mailflow.auth.dto.TeamMemberResponse;
import com.mailflow.auth.dto.UpdateTeamMemberRequest;
import com.mailflow.auth.entity.Role;
import com.mailflow.auth.entity.User;
import com.mailflow.auth.repository.UserRepository;
import com.mailflow.common.exception.ConflictException;
import com.mailflow.common.exception.ResourceNotFoundException;
import com.mailflow.common.exception.ValidationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * A company Admin manages the people in their own company: list them, add someone
 * with a role, change a role, disable or re-enable an account. Every operation is
 * limited to the Admin's organization; users of other companies are reported as
 * not found.
 *
 * <p>An Admin cannot change their own role or disable themselves, so a company
 * always keeps at least one enabled Admin.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TeamService {

    private final UserRepository userRepository;
    private final AuthService authService;
    private final PasswordEncoder passwordEncoder;

    @Transactional(readOnly = true)
    public List<TeamMemberResponse> list(User admin) {
        return userRepository.findTeam(admin.getOrganizationId()).stream().map(TeamService::toResponse).toList();
    }

    @Transactional
    public TeamMemberResponse add(User admin, CreateTeamMemberRequest request) {
        String email = request.email().trim().toLowerCase();
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new ConflictException("An account with this email already exists: " + email);
        }
        User user = User.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode(request.password()))
                .firstName(request.firstName().trim())
                .lastName(request.lastName().trim())
                .enabled(true)
                .organization(admin.getOrganization())
                .roles(authService.rolesFor(request.role()))
                .build();
        userRepository.save(user);
        log.info("Admin {} added {} as {} to organization {}",
                admin.getEmail(), email, request.role(), admin.getOrganizationId());
        return toResponse(user);
    }

    @Transactional
    public TeamMemberResponse update(User admin, Long userId, UpdateTeamMemberRequest request) {
        User user = userRepository.findTeamMember(admin.getOrganizationId(), userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
        boolean self = user.getId().equals(admin.getId());

        if (request.role() != null && !request.role().equals(levelOf(user))) {
            if (self) throw new ValidationException("You can't change your own role.");
            user.setRoles(authService.rolesFor(request.role()));
        }
        if (request.enabled() != null && request.enabled() != user.isEnabled()) {
            if (self) throw new ValidationException("You can't disable your own account.");
            user.setEnabled(request.enabled());
        }
        userRepository.save(user);
        log.info("Admin {} updated user {} (role={}, enabled={})",
                admin.getEmail(), user.getEmail(), levelOf(user), user.isEnabled());
        return toResponse(user);
    }

    private static String levelOf(User user) {
        return RoleLevels.levelOf(user.getRoles().stream().map(Role::getName).collect(Collectors.toSet()));
    }

    private static TeamMemberResponse toResponse(User u) {
        return new TeamMemberResponse(u.getId(), u.getEmail(), u.getFirstName(), u.getLastName(),
                levelOf(u), u.isEnabled(), u.getCreatedAt());
    }
}
