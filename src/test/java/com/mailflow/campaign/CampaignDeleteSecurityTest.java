package com.mailflow.campaign;

import com.mailflow.auth.entity.Role;
import com.mailflow.auth.entity.User;
import com.mailflow.campaign.controller.CampaignController;
import com.mailflow.campaign.service.CampaignService;
import com.mailflow.common.exception.ConflictException;
import com.mailflow.common.exception.GlobalExceptionHandler;
import com.mailflow.common.exception.ResourceNotFoundException;
import com.mailflow.config.WebConfig;
import com.mailflow.security.JwtAuthFilter;
import com.mailflow.security.JwtService;
import com.mailflow.security.SecurityConfig;
import io.jsonwebtoken.ExpiredJwtException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Security slice for DELETE /api/campaigns/{id}: the real {@link SecurityConfig} filter
 * chain (URL rules, the JWT filter, the 401 entry point), method security on the real
 * {@link CampaignController} and the real {@link GlobalExceptionHandler}, in a small
 * Spring MVC context. The campaign service, the JWT service and the user lookup are
 * mocks: no database, Redis, RabbitMQ or SMTP is used, and no Spring Boot context.
 */
@SpringJUnitWebConfig(CampaignDeleteSecurityTest.Slice.class)
class CampaignDeleteSecurityTest {

    @Configuration
    @EnableWebMvc
    @Import({SecurityConfig.class, WebConfig.class, CampaignController.class, GlobalExceptionHandler.class})
    static class Slice {
        @Bean CampaignService campaignService() { return mock(CampaignService.class); }
        @Bean JwtService jwtService() { return mock(JwtService.class); }
        @Bean UserDetailsService userDetailsService() { return mock(UserDetailsService.class); }
        @Bean JwtAuthFilter jwtAuthFilter(JwtService jwtService, UserDetailsService userDetailsService) {
            return new JwtAuthFilter(jwtService, userDetailsService);
        }
    }

    @Autowired private WebApplicationContext context;
    @Autowired private CampaignService campaignService;
    @Autowired private JwtService jwtService;
    @Autowired private UserDetailsService userDetailsService;
    @Autowired private CampaignController controller;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        reset(campaignService, jwtService, userDetailsService);
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        token("viewer-token", user(3L, "viewer@mailflow.local", "VIEWER"));
        token("manager-token", user(2L, "manager@mailflow.local", "CAMPAIGN_MANAGER"));
        token("admin-token", user(1L, "admin@mailflow.local", "ADMIN"));
        // Valid when the token belongs to the user it was issued for.
        doAnswer(inv -> user(inv.getArgument(0)).equals(((UserDetails) inv.getArgument(1)).getUsername()))
                .when(jwtService).isTokenValid(any(String.class), any(UserDetails.class));
        when(jwtService.extractUsername("expired-token")).thenThrow(new ExpiredJwtException(null, null, "expired"));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void anonymous_delete_gets_401_and_nothing_is_deleted() throws Exception {
        mvc.perform(delete("/api/campaigns/30")).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/campaigns/30").header("Authorization", "Bearer expired-token"))
                .andExpect(status().isUnauthorized());
        verify(campaignService, never()).delete(anyLong());
    }

    @Test
    void viewer_delete_gets_403_and_nothing_is_deleted() throws Exception {
        mvc.perform(delete("/api/campaigns/30").header("Authorization", "Bearer viewer-token"))
                .andExpect(status().isForbidden());
        // The same viewer may read the campaign: the 403 is about the role, not the session.
        mvc.perform(get("/api/campaigns/30").header("Authorization", "Bearer viewer-token"))
                .andExpect(status().isOk());
        verify(campaignService, never()).delete(anyLong());
        verify(campaignService).findById(30L);
    }

    @Test
    void campaign_managers_and_admins_can_delete() throws Exception {
        mvc.perform(delete("/api/campaigns/30").header("Authorization", "Bearer manager-token"))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/api/campaigns/31").header("Authorization", "Bearer admin-token"))
                .andExpect(status().isNoContent());
        verify(campaignService).delete(30L);
        verify(campaignService).delete(31L);
        verify(campaignService, times(2)).delete(anyLong());
    }

    @Test
    void the_method_rule_alone_also_refuses_a_viewer() {
        // Second layer: @PreAuthorize on the controller, called without the URL rules.
        signInAs(user(3L, "viewer@mailflow.local", "VIEWER"));
        assertThatThrownBy(() -> controller.delete(30L)).isInstanceOf(AccessDeniedException.class);
        verify(campaignService, never()).delete(anyLong());

        signInAs(user(2L, "manager@mailflow.local", "CAMPAIGN_MANAGER"));
        assertThatCode(() -> controller.delete(30L)).doesNotThrowAnyException();
        verify(campaignService).delete(30L);
    }

    @Test
    void refusals_reach_the_client_as_409_and_404_with_the_reason() throws Exception {
        doThrow(new ConflictException(CampaignService.DELETE_WHILE_SENDING)).when(campaignService).delete(40L);
        doThrow(new ResourceNotFoundException("Campaign", 41L)).when(campaignService).delete(41L);

        mvc.perform(delete("/api/campaigns/40").header("Authorization", "Bearer manager-token"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value(
                        "This campaign is sending. Cancel it, wait until sending stops, then delete it."));
        mvc.perform(delete("/api/campaigns/41").header("Authorization", "Bearer manager-token"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Campaign not found with id: 41"));
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private static User user(long id, String email, String role) {
        return User.builder().id(id).email(email).passwordHash("not-used").firstName("Test").lastName(role)
                .enabled(true).roles(Set.of(new Role(role))).build();
    }

    private final java.util.Map<String, String> tokenOwners = new java.util.HashMap<>();

    /** A bearer token that the (mocked) JWT service accepts for this user. */
    private void token(String token, User user) {
        tokenOwners.put(token, user.getEmail());
        when(jwtService.extractUsername(token)).thenReturn(user.getEmail());
        when(userDetailsService.loadUserByUsername(user.getEmail())).thenReturn(user);
    }

    /** Email of the user a test token was issued for ("" for an unknown token). */
    private String user(String token) {
        return tokenOwners.getOrDefault(token, "");
    }

    private static void signInAs(User user) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
    }
}
