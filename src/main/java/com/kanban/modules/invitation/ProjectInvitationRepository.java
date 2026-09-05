package com.kanban.modules.invitation;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface ProjectInvitationRepository extends JpaRepository<ProjectInvitation, String> {
  Optional<ProjectInvitation> findByIdAndProjectId(String id, String projectId);

  Optional<ProjectInvitation> findByTokenHash(String tokenHash);

  @Query("select i from ProjectInvitation i where i.projectId = :projectId and i.email = :email "
      + "and i.acceptedAt is null and i.revokedAt is null and i.expiresAt > :now")
  Optional<ProjectInvitation> findPendingByProjectIdAndEmail(
      @Param("projectId") String projectId, @Param("email") String email, @Param("now") Instant now);

  @EntityGraph(attributePaths = "inviter")
  @Query("select i from ProjectInvitation i where i.projectId = :projectId "
      + "and i.acceptedAt is null and i.revokedAt is null and i.expiresAt > :now "
      + "order by i.createdAt desc")
  List<ProjectInvitation> findPendingByProjectId(@Param("projectId") String projectId, @Param("now") Instant now);

  /**
   * UPDATE ... SET revoked_at = ? WHERE id = ? AND project_id = ? AND accepted_at IS NULL
   * AND revoked_at IS NULL &rarr; affected rows. One statement so a concurrent accept cannot
   * slip between a read and the write.
   */
  @Transactional
  @Modifying
  @Query("update ProjectInvitation i set i.revokedAt = :revokedAt where i.id = :id and i.projectId = :projectId "
      + "and i.acceptedAt is null and i.revokedAt is null")
  int revokePending(@Param("id") String id, @Param("projectId") String projectId,
      @Param("revokedAt") Instant revokedAt);
}
