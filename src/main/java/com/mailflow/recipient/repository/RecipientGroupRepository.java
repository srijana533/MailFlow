package com.mailflow.recipient.repository;

import com.mailflow.recipient.entity.RecipientGroup;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RecipientGroupRepository extends JpaRepository<RecipientGroup, Long> {

    List<RecipientGroup> findAllByOrderByCreatedAtDesc();
}
