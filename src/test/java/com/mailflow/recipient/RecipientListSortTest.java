package com.mailflow.recipient;

import com.mailflow.recipient.controller.RecipientController;
import com.mailflow.recipient.entity.RecipientStatus;
import com.mailflow.recipient.service.RecipientImportService;
import com.mailflow.recipient.service.RecipientService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * The contact list is paged by the requested sort (created_at by default). Many
 * contacts share created_at, and PostgreSQL may return tied rows in a different order
 * for each page, so the list adds the id as a tie-breaker.
 */
class RecipientListSortTest {

    private final RecipientService service = mock(RecipientService.class);
    private final RecipientController controller =
            new RecipientController(service, mock(RecipientImportService.class));

    private Pageable requested(String sort, String dir) {
        controller.list("ada", RecipientStatus.ACTIVE, 2, 20, sort, dir);
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(service).findAll(eq("ada"), eq(RecipientStatus.ACTIVE), pageable.capture());
        return pageable.getValue();
    }

    @Test
    void ties_in_the_requested_sort_are_broken_by_id() {
        Pageable pageable = requested("createdAt", "desc");

        assertThat(pageable.getPageNumber()).isEqualTo(2);
        assertThat(pageable.getPageSize()).isEqualTo(20);
        assertThat(pageable.getSort()).containsExactly(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
    }

    @Test
    void sorting_by_id_is_not_repeated() {
        assertThat(requested("id", "asc").getSort()).containsExactly(Sort.Order.asc("id"));
    }

    @Test
    void no_search_or_status_is_passed_through_as_null() {
        controller.list(null, null, 0, 20, "email", "asc");
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(service).findAll(isNull(), isNull(), pageable.capture());
        assertThat(pageable.getValue().getSort()).containsExactly(Sort.Order.asc("email"), Sort.Order.asc("id"));
    }
}
