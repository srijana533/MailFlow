package com.mailflow.auth.service;

import com.mailflow.auth.RoleLevels;
import com.mailflow.auth.dto.AuthResponse;
import com.mailflow.auth.dto.LoginRequest;
import com.mailflow.auth.dto.RegisterRequest;
import com.mailflow.auth.entity.Role;
import com.mailflow.auth.entity.User;
import com.mailflow.auth.repository.RoleRepository;
import com.mailflow.auth.repository.UserRepository;
import com.mailflow.common.exception.ConflictException;
import com.mailflow.common.exception.ValidationException;
import com.mailflow.organization.entity.Organization;
import com.mailflow.organization.repository.OrganizationRepository;
import com.mailflow.security.JwtService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Sign-up, login and token refresh.
 *
 * <p>Sign-up creates a new company workspace (organization) with the person as its
 * Admin. Further people join a company when its Admin adds them (see
 * {@link TeamService}); they never join an existing company by signing up.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final OrganizationRepository organizationRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthenticationManager authenticationManager;

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String email = request.email().trim().toLowerCase();
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new ConflictException("An account with this email already exists. Sign in instead.");
        }

        Organization organization = organizationRepository.save(
                Organization.builder().name(request.companyName().trim()).build());

        User user = User.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode(request.password()))
                .firstName(request.firstName().trim())
                .lastName(request.lastName().trim())
                .enabled(true)
                .organization(organization)
                .roles(rolesFor(RoleLevels.ADMIN))
                .build();
        userRepository.save(user);

        log.info("New company '{}' (id={}) signed up by {}", organization.getName(), organization.getId(), email);
        return buildAuthResponse(user);
    }

    public AuthResponse login(LoginRequest request) {
        String email = request.email().trim().toLowerCase();
        authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(email, request.password()));
        User user = userRepository.findActiveByEmail(email)
                .orElseThrow(() -> new IllegalStateException("User not found after authentication"));
        log.info("User logged in: {}", user.getEmail());
        return buildAuthResponse(user);
    }

    public AuthResponse refreshToken(String refreshToken) {
        String email;
        try {
            if (!jwtService.isRefreshToken(refreshToken)) {
                throw new ValidationException("Not a refresh token");
            }
            email = jwtService.extractUsername(refreshToken);
        } catch (ValidationException e) {
            throw e;
        } catch (Exception e) {
            throw new ValidationException("Refresh token is invalid or expired");
        }
        User user = userRepository.findActiveByEmail(email)
                .filter(User::isEnabled)
                .orElseThrow(() -> new ValidationException("Refresh token is invalid or expired"));
        if (!jwtService.isTokenValid(refreshToken, user)) {
            throw new ValidationException("Refresh token is invalid or expired");
        }
        return buildAuthResponse(user);
    }

    /** Role entities for a level (see {@link RoleLevels}), created if missing. */
    public Set<Role> rolesFor(String level) {
        Set<Role> roles = new HashSet<>();
        for (String name : RoleLevels.rolesFor(level)) {
            roles.add(roleRepository.findByName(name).orElseGet(() -> roleRepository.save(new Role(name))));
        }
        return roles;
    }

    private AuthResponse buildAuthResponse(User user) {
        String accessToken  = jwtService.generateAccessToken(user);
        String refreshToken = jwtService.generateRefreshToken(user);
        List<String> roles = user.getRoles().stream().map(Role::getName).toList();
        Organization org = user.getOrganization();

        return new AuthResponse(
            accessToken,
            refreshToken,
            jwtService.accessTokenExpirySeconds(),
            new AuthResponse.UserInfo(
                user.getId(),
                user.getEmail(),
                user.getFirstName(),
                user.getLastName(),
                roles,
                org != null ? org.getId() : null,
                org != null ? org.getName() : null
            )
        );
    }
}
