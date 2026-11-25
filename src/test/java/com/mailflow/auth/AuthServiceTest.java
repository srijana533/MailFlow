package com.mailflow.auth;

import com.mailflow.auth.dto.LoginRequest;
import com.mailflow.auth.dto.RegisterRequest;
import com.mailflow.auth.entity.Role;
import com.mailflow.auth.entity.User;
import com.mailflow.auth.repository.RoleRepository;
import com.mailflow.auth.repository.UserRepository;
import com.mailflow.auth.service.AuthService;
import com.mailflow.common.exception.ConflictException;
import com.mailflow.config.MailFlowProperties;
import com.mailflow.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private RoleRepository roleRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private JwtService jwtService;
    @Mock private AuthenticationManager authenticationManager;
    @Mock private com.mailflow.organization.repository.OrganizationRepository organizationRepository;
    @InjectMocks private AuthService authService;

    private Role viewerRole;
    private User savedUser;

    @BeforeEach
    void setUp() {
        viewerRole = new Role(1L, "VIEWER", "Viewer");
        savedUser = User.builder()
                .id(1L)
                .email("test@example.com")
                .passwordHash("hash")
                .firstName("Test")
                .lastName("User")
                .roles(Set.of(viewerRole))
                .build();
    }

    @Test
    void sign_up_creates_a_company_with_the_person_as_its_admin() {
        when(userRepository.existsByEmailIgnoreCase(anyString())).thenReturn(false);
        when(organizationRepository.save(any())).thenAnswer(inv -> {
            com.mailflow.organization.entity.Organization o = inv.getArgument(0);
            o.setId(42L);
            return o;
        });
        when(roleRepository.findByName(anyString())).thenAnswer(inv -> Optional.of(new Role(inv.<String>getArgument(0))));
        when(passwordEncoder.encode(anyString())).thenReturn("hashed");
        when(userRepository.save(any(User.class))).thenReturn(savedUser);
        when(jwtService.generateAccessToken(any())).thenReturn("access-token");
        when(jwtService.generateRefreshToken(any())).thenReturn("refresh-token");

        var response = authService.register(new RegisterRequest(
            "  Acme Ltd ", "Test@Example.com", "Password@123", "Test", "User"));

        assertThat(response.accessToken()).isEqualTo("access-token");
        assertThat(response.user().email()).isEqualTo("test@example.com");
        assertThat(response.user().organizationId()).isEqualTo(42L);
        assertThat(response.user().organizationName()).isEqualTo("Acme Ltd");
        assertThat(response.user().roles()).containsExactlyInAnyOrder("ADMIN", "CAMPAIGN_MANAGER", "VIEWER");
        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        assertThat(saved.getValue().getOrganizationId()).isEqualTo(42L);
    }

    @Test
    void throws_conflict_for_duplicate_email() {
        when(userRepository.existsByEmailIgnoreCase("test@example.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(
            new RegisterRequest("Acme", "test@example.com", "Password@123", "Test", "User")))
            .isInstanceOf(ConflictException.class);
    }

    @Test
    void logs_in_existing_user() {
        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
            .thenReturn(null);
        when(userRepository.findActiveByEmail("test@example.com")).thenReturn(Optional.of(savedUser));
        when(jwtService.generateAccessToken(any())).thenReturn("access-token");
        when(jwtService.generateRefreshToken(any())).thenReturn("refresh-token");

        var response = authService.login(new LoginRequest("test@example.com", "Password@123"));

        assertThat(response.accessToken()).isEqualTo("access-token");
        assertThat(response.user().email()).isEqualTo("test@example.com");
    }
}
