package com.mailflow.campaign;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mailflow.batch.CampaignJobLauncher;
import com.mailflow.campaign.dto.CampaignRequest;
import com.mailflow.campaign.dto.CampaignResponse;
import com.mailflow.campaign.dto.TargetAddress;
import com.mailflow.campaign.entity.Campaign;
import com.mailflow.campaign.entity.CampaignRecipient;
import com.mailflow.campaign.entity.CampaignStatus;
import com.mailflow.campaign.entity.CampaignTargetAddress;
import com.mailflow.campaign.repository.CampaignRecipientRepository;
import com.mailflow.campaign.repository.CampaignRepository;
import com.mailflow.campaign.repository.CampaignTargetAddressRepository;
import com.mailflow.campaign.service.CampaignService;
import com.mailflow.common.dto.PageResponse;
import com.mailflow.common.exception.ValidationException;
import com.mailflow.email.UnsubscribeService;
import com.mailflow.recipient.entity.Recipient;
import com.mailflow.recipient.entity.RecipientStatus;
import com.mailflow.recipient.repository.RecipientRepository;
import com.mailflow.template.entity.EmailTemplate;
import com.mailflow.template.service.TemplateService;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Campaign-only addresses: typed or uploaded addresses that a campaign emails
 * without saving them as contacts. Covers storage (create/update validation and the
 * null-keeps / []-clears rule) and launch resolution against saved contacts.
 */
@ExtendWith(MockitoExtension.class)
class CampaignOnlyAddressesTest {

    private static final long CAMPAIGN_ID = 7L;

    @Mock private CampaignRepository campaignRepository;
    @Mock private TemplateService templateService;
    @Mock private CampaignJobLauncher jobLauncher;
    @Mock private RecipientRepository recipientRepository;
    @Mock private CampaignRecipientRepository campaignRecipientRepository;
    @Mock private UnsubscribeService unsubscribeService;
    @Mock private CampaignTargetAddressRepository targetAddressRepository;

    private CampaignService campaignService;
    private Campaign campaign;

    /** In-memory stand-in for the campaign_target_addresses rows of CAMPAIGN_ID. */
    private final List<CampaignTargetAddress> store = new ArrayList<>();
    private long nextStoredId = 1;

    @BeforeEach
    void setUp() {
        campaignService = new CampaignService(
                campaignRepository, templateService, jobLauncher,
                recipientRepository, campaignRecipientRepository, unsubscribeService,
                targetAddressRepository);

        EmailTemplate template = new EmailTemplate();
        template.setId(1L);
        template.setName("Welcome");
        template.setSubject("Hello");
        template.setPlainBody("Hello {{FirstName}}");

        campaign = Campaign.builder()
                .id(CAMPAIGN_ID)
                .name("Campaign-only test")
                .subject("Hello {{FirstName}}")
                .senderEmail("sender@test.local")
                .status(CampaignStatus.DRAFT)
                .template(template)
                .build();
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private static CampaignTargetAddress stored(String email, String name) {
        return CampaignTargetAddress.builder().campaignId(CAMPAIGN_ID).email(email).name(name).build();
    }

    private static Recipient contact(long id, String email, RecipientStatus status) {
        return Recipient.builder().id(id).email(email).status(status).build();
    }

    private static TargetAddress address(String email) {
        return new TargetAddress(email, null, null, null, null, null);
    }

    private static CampaignRequest request(List<TargetAddress> targetAddresses) {
        return new CampaignRequest("Campaign-only test", null, "Hello {{FirstName}}", null,
                null, "sender@test.local", null, null, null, null, targetAddresses, null);
    }

    private void useInMemoryTargetStore() {
        lenient().when(targetAddressRepository.findByCampaignIdOrderByIdAsc(CAMPAIGN_ID))
                .thenAnswer(inv -> new ArrayList<>(store));
        lenient().when(targetAddressRepository.saveAll(anyList())).thenAnswer(inv -> {
            List<CampaignTargetAddress> rows = inv.getArgument(0);
            for (CampaignTargetAddress t : rows) {
                if (t.getId() == null) {
                    t.setId(nextStoredId++);
                    store.add(t);
                }
            }
            return rows;
        });
        lenient().doAnswer(inv -> {
            Iterable<CampaignTargetAddress> gone = inv.getArgument(0);
            gone.forEach(store::remove);
            return null;
        }).when(targetAddressRepository).deleteAll(anyIterable());
    }

    private void prepareLaunch() {
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));
        lenient().when(campaignRepository.save(any(Campaign.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @SuppressWarnings("unchecked")
    private List<CampaignRecipient> savedRows() {
        ArgumentCaptor<List<CampaignRecipient>> captor = ArgumentCaptor.forClass(List.class);
        verify(campaignRecipientRepository).saveAll(captor.capture());
        return captor.getValue();
    }

    private void verifyNoContactWasCreated() {
        verify(recipientRepository, never()).save(any());
        verify(recipientRepository, never()).saveAll(any());
    }

    // ── Launch ──────────────────────────────────────────────────────────────────

    @Test
    void launch_with_only_campaign_only_addresses_creates_rows_without_a_contact() {
        prepareLaunch();
        CampaignTargetAddress ada = CampaignTargetAddress.builder()
                .campaignId(CAMPAIGN_ID).email("ada@test.local").name("Ada Lovelace")
                .firstName("Ada").lastName("Lovelace").company("Acme")
                .customFields(Map.of("Team", "Platform")).build();
        CampaignTargetAddress bob = stored("bob@test.local", null);
        when(targetAddressRepository.countByCampaignId(CAMPAIGN_ID)).thenReturn(2L);
        when(targetAddressRepository.findByCampaignIdOrderByIdAsc(CAMPAIGN_ID)).thenReturn(List.of(ada, bob));
        when(recipientRepository.findByEmailIn(List.of("ada@test.local", "bob@test.local"))).thenReturn(List.of());
        when(recipientRepository.findDeletedEmails(List.of("ada@test.local", "bob@test.local"))).thenReturn(List.of());
        when(campaignRecipientRepository.countByCampaignId(CAMPAIGN_ID)).thenReturn(2L);

        CampaignResponse response = campaignService.launch(CAMPAIGN_ID);

        List<CampaignRecipient> rows = savedRows();
        assertThat(rows).hasSize(2).allSatisfy(row -> {
            assertThat(row.getRecipient()).isNull();
            assertThat(row.getCampaign()).isSameAs(campaign);
            assertThat(row.getDeliveryStatus()).isEqualTo(CampaignRecipient.DeliveryStatus.PENDING);
            assertThat(row.getIdempotencyKey())
                    .startsWith("campaign_7_addr_").hasSizeLessThanOrEqualTo(100).doesNotContain("@");
        });
        assertThat(rows).extracting(CampaignRecipient::getIdempotencyKey).doesNotHaveDuplicates();

        CampaignRecipient adaRow = rows.get(0);
        assertThat(adaRow.getSnapshotEmail()).isEqualTo("ada@test.local");
        assertThat(adaRow.getSnapshotName()).isEqualTo("Ada Lovelace");
        assertThat(adaRow.getSnapshotFirstName()).isEqualTo("Ada");
        assertThat(adaRow.getSnapshotLastName()).isEqualTo("Lovelace");
        assertThat(adaRow.getSnapshotCompany()).isEqualTo("Acme");
        assertThat(adaRow.getSnapshotCustomFields()).containsExactly(entry("Team", "Platform"));
        assertThat(adaRow.getIdempotencyKey())
                .isEqualTo(CampaignRecipient.buildAddressIdempotencyKey(CAMPAIGN_ID, "ada@test.local"));
        assertThat(rows.get(1).targetEmail()).isEqualTo("bob@test.local");

        assertThat(response.status()).isEqualTo(CampaignStatus.QUEUED);
        assertThat(response.totalRecipients()).isEqualTo(2);
        assertThat(response.targetAddressCount()).isEqualTo(2);
        verify(jobLauncher).launchCampaignJob(CAMPAIGN_ID);
        verify(recipientRepository, never()).findAllActiveRecipients();
        verify(recipientRepository, never()).findByIdsIn(any());
        verifyNoContactWasCreated();
    }

    @Test
    void address_that_is_also_a_selected_saved_contact_gets_one_row_for_the_contact() {
        prepareLaunch();
        Recipient ada = contact(10L, "ada@test.local", RecipientStatus.ACTIVE);
        campaign.setRecipientIds(new HashSet<>(Set.of(10L)));
        when(recipientRepository.findByIdsIn(Set.of(10L))).thenReturn(List.of(ada));
        when(targetAddressRepository.findByCampaignIdOrderByIdAsc(CAMPAIGN_ID))
                .thenReturn(List.of(stored("ada@test.local", "Typed Ada"), stored("new@test.local", null)));
        when(recipientRepository.findByEmailIn(List.of("ada@test.local", "new@test.local"))).thenReturn(List.of(ada));
        when(recipientRepository.findDeletedEmails(anyList())).thenReturn(List.of());
        when(campaignRecipientRepository.countByCampaignId(CAMPAIGN_ID)).thenReturn(2L);

        campaignService.launch(CAMPAIGN_ID);

        List<CampaignRecipient> rows = savedRows();
        assertThat(rows).extracting(CampaignRecipient::targetEmail)
                .containsExactly("ada@test.local", "new@test.local");
        CampaignRecipient adaRow = rows.get(0);
        assertThat(adaRow.getRecipient()).isSameAs(ada);
        assertThat(adaRow.getSnapshotEmail()).isNull();
        assertThat(adaRow.getIdempotencyKey()).isEqualTo("campaign_7_recipient_10");
        assertThat(rows.get(1).getRecipient()).isNull();
        assertThat(rows.get(1).getSnapshotEmail()).isEqualTo("new@test.local");
    }

    @Test
    void address_that_has_since_become_a_contact_uses_the_contact_and_respects_its_status() {
        prepareLaunch();
        when(targetAddressRepository.countByCampaignId(CAMPAIGN_ID)).thenReturn(3L);
        when(targetAddressRepository.findByCampaignIdOrderByIdAsc(CAMPAIGN_ID)).thenReturn(List.of(
                stored("now.active@test.local", "Snapshot name"),
                stored("now.unsubscribed@test.local", null),
                stored("now.bounced@test.local", null)));
        Recipient active = contact(20L, "now.active@test.local", RecipientStatus.ACTIVE);
        when(recipientRepository.findByEmailIn(anyList())).thenReturn(List.of(
                active,
                contact(21L, "now.unsubscribed@test.local", RecipientStatus.UNSUBSCRIBED),
                contact(22L, "now.bounced@test.local", RecipientStatus.BOUNCED)));
        when(recipientRepository.findDeletedEmails(anyList())).thenReturn(List.of());
        when(campaignRecipientRepository.countByCampaignId(CAMPAIGN_ID)).thenReturn(1L);

        campaignService.launch(CAMPAIGN_ID);

        List<CampaignRecipient> rows = savedRows();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getRecipient()).isSameAs(active);
        assertThat(rows.get(0).getSnapshotEmail()).isNull();
        assertThat(rows.get(0).getIdempotencyKey()).isEqualTo("campaign_7_recipient_20");
        verifyNoContactWasCreated();
    }

    @Test
    void addresses_of_deleted_contacts_and_unsubscribed_addresses_are_skipped() {
        prepareLaunch();
        when(targetAddressRepository.countByCampaignId(CAMPAIGN_ID)).thenReturn(3L);
        when(targetAddressRepository.findByCampaignIdOrderByIdAsc(CAMPAIGN_ID)).thenReturn(List.of(
                stored("gone@test.local", null), stored("optout@test.local", null), stored("ok@test.local", null)));
        when(recipientRepository.findByEmailIn(anyList())).thenReturn(List.of());
        when(recipientRepository.findDeletedEmails(anyList())).thenReturn(List.of("gone@test.local"));
        when(unsubscribeService.isUnsubscribed(anyString()))
                .thenAnswer(inv -> "optout@test.local".equals(inv.getArgument(0)));
        when(campaignRecipientRepository.countByCampaignId(CAMPAIGN_ID)).thenReturn(1L);

        campaignService.launch(CAMPAIGN_ID);

        assertThat(savedRows()).extracting(CampaignRecipient::getSnapshotEmail).containsExactly("ok@test.local");
    }

    @Test
    void launch_fails_without_sending_when_every_campaign_only_address_is_skipped() {
        prepareLaunch();
        when(targetAddressRepository.countByCampaignId(CAMPAIGN_ID)).thenReturn(2L);
        when(targetAddressRepository.findByCampaignIdOrderByIdAsc(CAMPAIGN_ID))
                .thenReturn(List.of(stored("gone@test.local", null), stored("optout@test.local", null)));
        when(recipientRepository.findByEmailIn(anyList())).thenReturn(List.of());
        when(recipientRepository.findDeletedEmails(anyList())).thenReturn(List.of("gone@test.local"));
        when(unsubscribeService.isUnsubscribed("optout@test.local")).thenReturn(true);

        assertThatThrownBy(() -> campaignService.launch(CAMPAIGN_ID))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("None of the selected recipients can be emailed");
        verify(campaignRecipientRepository, never()).saveAll(any());
        verify(jobLauncher, never()).launchCampaignJob(any());
        assertThat(campaign.getStatus()).isEqualTo(CampaignStatus.DRAFT);
    }

    @Test
    void relaunch_from_failed_does_not_duplicate_existing_rows() {
        prepareLaunch();
        campaign.setStatus(CampaignStatus.FAILED);
        Recipient ada = contact(10L, "ada@test.local", RecipientStatus.ACTIVE);
        campaign.setRecipientIds(new HashSet<>(Set.of(10L)));
        when(recipientRepository.findByIdsIn(Set.of(10L))).thenReturn(List.of(ada));
        // First launch: a contact row for ada and a campaign-only row for bob.
        when(campaignRecipientRepository.findTargetedEmails(CAMPAIGN_ID))
                .thenReturn(List.of("ada@test.local", "bob@test.local"));
        when(targetAddressRepository.findByCampaignIdOrderByIdAsc(CAMPAIGN_ID))
                .thenReturn(List.of(stored("ada@test.local", null), stored("bob@test.local", null)));
        when(recipientRepository.findByEmailIn(anyList())).thenReturn(List.of(ada));
        when(recipientRepository.findDeletedEmails(anyList())).thenReturn(List.of());
        when(campaignRecipientRepository.countByCampaignId(CAMPAIGN_ID)).thenReturn(2L);

        CampaignResponse response = campaignService.launch(CAMPAIGN_ID);

        verify(campaignRecipientRepository, never()).saveAll(any());
        assertThat(response.status()).isEqualTo(CampaignStatus.QUEUED);
        assertThat(response.totalRecipients()).isEqualTo(2);
        verify(jobLauncher, times(1)).launchCampaignJob(CAMPAIGN_ID);
    }

    @Test
    void relaunch_from_paused_does_not_add_a_contact_row_for_an_address_that_became_a_contact() {
        prepareLaunch();
        campaign.setStatus(CampaignStatus.PAUSED);
        when(targetAddressRepository.countByCampaignId(CAMPAIGN_ID)).thenReturn(1L);
        // bob already has a campaign-only row from the first launch ...
        when(campaignRecipientRepository.findTargetedEmails(CAMPAIGN_ID)).thenReturn(List.of("bob@test.local"));
        when(targetAddressRepository.findByCampaignIdOrderByIdAsc(CAMPAIGN_ID))
                .thenReturn(List.of(stored("bob@test.local", null)));
        // ... and has been saved as a contact since then.
        when(recipientRepository.findByEmailIn(anyList()))
                .thenReturn(List.of(contact(30L, "bob@test.local", RecipientStatus.ACTIVE)));
        when(recipientRepository.findDeletedEmails(anyList())).thenReturn(List.of());
        when(campaignRecipientRepository.countByCampaignId(CAMPAIGN_ID)).thenReturn(1L);

        campaignService.launch(CAMPAIGN_ID);

        verify(campaignRecipientRepository, never()).saveAll(any());
        verify(campaignRecipientRepository, never()).existsByCampaignIdAndRecipientId(any(), any());
    }

    @Test
    void address_idempotency_key_is_unique_per_campaign_and_address_and_fits_the_column() {
        String longAddress = "x".repeat(240) + "@test.local";
        String key = CampaignRecipient.buildAddressIdempotencyKey(Long.MAX_VALUE, longAddress);

        assertThat(key).startsWith("campaign_" + Long.MAX_VALUE + "_addr_")
                .hasSizeLessThanOrEqualTo(100).doesNotContain("@");
        assertThat(CampaignRecipient.buildAddressIdempotencyKey(7L, " Ada@Test.Local "))
                .isEqualTo(CampaignRecipient.buildAddressIdempotencyKey(7L, "ada@test.local"));
        assertThat(CampaignRecipient.buildAddressIdempotencyKey(7L, "ada@test.local"))
                .isNotEqualTo(CampaignRecipient.buildAddressIdempotencyKey(7L, "bob@test.local"))
                .isNotEqualTo(CampaignRecipient.buildAddressIdempotencyKey(8L, "ada@test.local"))
                .isNotEqualTo(CampaignRecipient.buildIdempotencyKey(7L, 1L));
    }

    // ── Create / update ─────────────────────────────────────────────────────────

    @Test
    void create_stores_normalised_unique_addresses_without_creating_contacts() {
        when(campaignRepository.save(any(Campaign.class))).thenAnswer(inv -> {
            Campaign c = inv.getArgument(0);
            c.setId(CAMPAIGN_ID);
            return c;
        });
        useInMemoryTargetStore();
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put(" Team ", " Platform ");
        fields.put("email", "shadow@test.local"); // would shadow {{email}}: ignored
        fields.put("Blank", "  ");                 // blank values are dropped

        CampaignResponse response = campaignService.create(request(List.of(
                new TargetAddress(" Ada@Test.Local ", " Ada Lovelace ", "Ada", " ", "Acme", fields),
                new TargetAddress("ada@test.local", "Second copy", null, null, null, null),
                address("BOB@test.local"))));

        assertThat(response.status()).isEqualTo(CampaignStatus.DRAFT);
        assertThat(response.targetAddressCount()).isEqualTo(2);
        assertThat(response.targetAddresses()).extracting(TargetAddress::email)
                .containsExactly("ada@test.local", "bob@test.local");
        TargetAddress ada = response.targetAddresses().get(0);
        assertThat(ada.name()).isEqualTo("Ada Lovelace");
        assertThat(ada.firstName()).isEqualTo("Ada");
        assertThat(ada.lastName()).isNull();
        assertThat(ada.company()).isEqualTo("Acme");
        assertThat(ada.fields()).containsExactly(entry("Team", "Platform"));
        assertThat(store).extracting(CampaignTargetAddress::getCampaignId).containsOnly(CAMPAIGN_ID);
        verifyNoContactWasCreated();
    }

    @Test
    void create_rejects_invalid_addresses_and_names_them() {
        CampaignRequest request = request(List.of(
                address("good@test.local"), address("not-an-email"), address("   "), address("bad@@test.local")));

        assertThatThrownBy(() -> campaignService.create(request))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("(3)")
                .hasMessageContaining("not-an-email")
                .hasMessageContaining("(blank)")
                .hasMessageContaining("bad@@test.local")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("good@test.local"));
        verify(campaignRepository, never()).save(any());
        verify(targetAddressRepository, never()).saveAll(any());
    }

    @Test
    void create_rejects_more_than_ten_thousand_addresses() {
        List<TargetAddress> tooMany = IntStream.rangeClosed(1, CampaignService.MAX_TARGET_ADDRESSES + 1)
                .mapToObj(i -> address("user" + i + "@test.local"))
                .toList();

        assertThatThrownBy(() -> campaignService.create(request(tooMany)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("at most 10,000");
        verify(campaignRepository, never()).save(any());
    }

    @Test
    void update_rejects_invalid_addresses_before_changing_the_draft() {
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));

        assertThatThrownBy(() -> campaignService.update(CAMPAIGN_ID, new CampaignRequest(
                "Renamed", null, "Hello", null, null, "sender@test.local", null, null, null, null,
                List.of(address("broken.address")), null)))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Invalid campaign-only email address: broken.address");
        assertThat(campaign.getName()).isEqualTo("Campaign-only test");
        verify(campaignRepository, never()).save(any());
    }

    @Test
    void update_with_null_target_addresses_keeps_the_stored_ones() {
        store.add(stored("ada@test.local", "Ada"));
        useInMemoryTargetStore();
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));
        when(campaignRepository.save(any(Campaign.class))).thenAnswer(inv -> inv.getArgument(0));

        CampaignResponse response = campaignService.update(CAMPAIGN_ID, request(null));

        assertThat(response.targetAddresses()).extracting(TargetAddress::email).containsExactly("ada@test.local");
        assertThat(response.targetAddressCount()).isEqualTo(1);
        verify(targetAddressRepository, never()).saveAll(any());
        verify(targetAddressRepository, never()).deleteAll(anyIterable());
    }

    @Test
    void update_with_an_empty_list_removes_all_stored_addresses() {
        store.add(stored("ada@test.local", "Ada"));
        store.add(stored("bob@test.local", "Bob"));
        useInMemoryTargetStore();
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));
        when(campaignRepository.save(any(Campaign.class))).thenAnswer(inv -> inv.getArgument(0));

        CampaignResponse response = campaignService.update(CAMPAIGN_ID, request(List.of()));

        assertThat(store).isEmpty();
        assertThat(response.targetAddresses()).isEmpty();
        assertThat(response.targetAddressCount()).isZero();
    }

    @Test
    void update_with_a_list_replaces_the_addresses_and_keeps_unchanged_rows() {
        CampaignTargetAddress ada = stored("ada@test.local", "Ada");
        ada.setId(nextStoredId++);
        CampaignTargetAddress bob = stored("bob@test.local", "Bob");
        bob.setId(nextStoredId++);
        CampaignTargetAddress carol = stored("carol@test.local", "Carol");
        carol.setId(nextStoredId++);
        store.addAll(List.of(ada, bob, carol));
        useInMemoryTargetStore();
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));
        when(campaignRepository.save(any(Campaign.class))).thenAnswer(inv -> inv.getArgument(0));

        CampaignResponse response = campaignService.update(CAMPAIGN_ID, request(List.of(
                new TargetAddress("ada@test.local", "Ada", null, null, null, Map.of()),       // unchanged
                new TargetAddress("carol@test.local", "Carol King", null, null, null, null),  // changed
                address("dave@test.local"))));                                                 // new

        assertThat(response.targetAddresses()).extracting(TargetAddress::email)
                .containsExactly("ada@test.local", "carol@test.local", "dave@test.local");
        assertThat(store).contains(ada, carol).doesNotContain(bob);
        assertThat(ada.getId()).isEqualTo(1L);
        assertThat(carol.getName()).isEqualTo("Carol King");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CampaignTargetAddress>> saved = ArgumentCaptor.forClass(List.class);
        verify(targetAddressRepository).saveAll(saved.capture());
        assertThat(saved.getValue()).extracting(CampaignTargetAddress::getEmail)
                .containsExactly("carol@test.local", "dave@test.local");
    }

    // ── Responses ───────────────────────────────────────────────────────────────

    @Test
    void list_responses_carry_the_count_but_not_the_addresses() throws Exception {
        Campaign other = Campaign.builder().id(8L).name("Other").subject("S").build();
        PageRequest pageable = PageRequest.of(0, 20);
        when(campaignRepository.findAll(null, null, pageable)).thenReturn(new PageImpl<>(List.of(campaign, other)));
        when(targetAddressRepository.countByCampaignIds(List.of(CAMPAIGN_ID, 8L)))
                .thenReturn(List.<Object[]>of(new Object[]{CAMPAIGN_ID, 3L}));

        PageResponse<CampaignResponse> page = campaignService.findAll(null, null, pageable);

        assertThat(page.content()).extracting(CampaignResponse::targetAddressCount).containsExactly(3L, 0L);
        assertThat(page.content()).allSatisfy(r -> assertThat(r.targetAddresses()).isNull());
        verify(targetAddressRepository, never()).findByCampaignIdOrderByIdAsc(any());

        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(page.content().get(0));
        assertThat(json).contains("\"targetAddressCount\":3").doesNotContain("\"targetAddresses\"");
    }

    @Test
    void request_limits_match_the_database_columns() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("k".repeat(41), "value");
        fields.put("Team", "v".repeat(1001));
        TargetAddress tooLong = new TargetAddress("ada@test.local", "n".repeat(256), "f".repeat(101),
                "l".repeat(100), "c".repeat(255), fields);

        Set<ConstraintViolation<CampaignRequest>> violations = validator.validate(request(List.of(tooLong)));

        assertThat(violations).extracting(v -> v.getPropertyPath().toString())
                .hasSize(4)
                .anyMatch(p -> p.equals("targetAddresses[0].name"))
                .anyMatch(p -> p.equals("targetAddresses[0].firstName"))
                .filteredOn(p -> p.startsWith("targetAddresses[0].fields")).hasSize(2);
        assertThat(validator.validate(request(List.of(
                new TargetAddress("ada@test.local", "n".repeat(255), "f".repeat(100), null, null,
                        Map.of("Team", "Platform")))))).isEmpty();
    }
}
