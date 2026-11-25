package com.mailflow.auth.repository;

import com.mailflow.auth.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);

    @Query("SELECT u FROM User u WHERE u.email = :email AND u.deletedAt IS NULL")
    Optional<User> findActiveByEmail(String email);

    /** Users of one company, oldest first. Users are not tenant-filtered, so the organization is passed explicitly. */
    @Query("""
           SELECT u FROM User u
           WHERE u.organization.id = :orgId AND u.deletedAt IS NULL
           ORDER BY u.createdAt ASC
           """)
    List<User> findTeam(@Param("orgId") Long organizationId);

    @Query("SELECT u FROM User u WHERE u.id = :id AND u.organization.id = :orgId AND u.deletedAt IS NULL")
    Optional<User> findTeamMember(@Param("orgId") Long organizationId, @Param("id") Long id);
}
