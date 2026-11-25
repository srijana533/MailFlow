package com.mailflow.auth;

import com.mailflow.auth.dto.CreateTeamMemberRequest;
import com.mailflow.auth.dto.UpdateTeamMemberRequest;
import com.mailflow.auth.entity.Role;
import com.mailflow.auth.entity.User;
import com.mailflow.auth.repository.UserRepository;
import com.mailflow.auth.service.AuthService;
import com.mailflow.auth.service.TeamService;
import com.mailflow.common.exception.ConflictException;
import com.mailflow.common.exception.ResourceNotFoundException;
import com.mailflow.common.exception.ValidationException;
import com.mailflow.organization.entity.Organization;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TeamServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private AuthService authService;
    @Mock private PasswordEncoder passwordEncoder;
    @InjectMocks private TeamService teamService;

    private User admin;

    private static Set<Role> roles(String level) {
        return RoleLevels.rolesFor(level).stream().map(Role::new).collect(Collectors.toCollection(HashSet::new));
    }

    private static User user(long id, long orgId, String level) {
        return User.builder().id(id).email("u" + id + "@test.local").firstName("U").lastName(String.valueOf(id))
                .enabled(true).organization(Organization.builder().id(orgId).name("Org").build())
                .roles(roles(level)).build();
    }

    @BeforeEach
    void setUp() {
        admin = user(1, 7, RoleLevels.ADMIN);
    }

    @Test
    void an_added_person_joins_the_admins_company_with_the_chosen_role() {
        when(userRepository.existsByEmailIgnoreCase("new@test.local")).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("hash");
        when(authService.rolesFor(RoleLevels.CAMPAIGN_MANAGER)).thenReturn(roles(RoleLevels.CAMPAIGN_MANAGER));

        var response = teamService.add(admin, new CreateTeamMemberRequest(
                " New@Test.Local ", "New", "Person", "Password1", RoleLevels.CAMPAIGN_MANAGER));

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        assertThat(saved.getValue().getOrganizationId()).isEqualTo(7L);
        assertThat(saved.getValue().getEmail()).isEqualTo("new@test.local");
        assertThat(response.role()).isEqualTo(RoleLevels.CAMPAIGN_MANAGER);
    }

    @Test
    void an_email_that_already_has_an_account_is_refused() {
        when(userRepository.existsByEmailIgnoreCase("taken@test.local")).thenReturn(true);
        assertThatThrownBy(() -> teamService.add(admin, new CreateTeamMemberRequest(
                "taken@test.local", "A", "B", "Password1", RoleLevels.VIEWER)))
                .isInstanceOf(ConflictException.class);
        verify(userRepository, never()).save(any());
    }

    @Test
    void a_person_of_another_company_is_not_found() {
        when(userRepository.findTeamMember(7L, 99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> teamService.update(admin, 99L, new UpdateTeamMemberRequest(RoleLevels.VIEWER, null)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void an_admin_cannot_demote_or_disable_themselves() {
        when(userRepository.findTeamMember(7L, 1L)).thenReturn(Optional.of(admin));
        assertThatThrownBy(() -> teamService.update(admin, 1L, new UpdateTeamMemberRequest(RoleLevels.VIEWER, null)))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> teamService.update(admin, 1L, new UpdateTeamMemberRequest(null, false)))
                .isInstanceOf(ValidationException.class);
        verify(userRepository, never()).save(any());
    }

    @Test
    void an_admin_can_change_a_role_and_disable_a_teammate() {
        User teammate = user(2, 7, RoleLevels.VIEWER);
        when(userRepository.findTeamMember(7L, 2L)).thenReturn(Optional.of(teammate));
        when(authService.rolesFor(RoleLevels.ADMIN)).thenReturn(roles(RoleLevels.ADMIN));

        var response = teamService.update(admin, 2L, new UpdateTeamMemberRequest(RoleLevels.ADMIN, false));

        assertThat(response.role()).isEqualTo(RoleLevels.ADMIN);
        assertThat(response.enabled()).isFalse();
        verify(userRepository).save(teammate);
    }
}
