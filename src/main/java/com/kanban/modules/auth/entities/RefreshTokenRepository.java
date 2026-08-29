package com.kanban.modules.auth.entities;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Integer> {
  Optional<RefreshToken> findByTokenHash(String tokenHash);

  /** UPDATE ... SET is_revoked = true WHERE token_hash = ? AND is_revoked = false → affected rows. */
  @Transactional
  @Modifying
  @Query("update RefreshToken t set t.isRevoked = true where t.tokenHash = :hash and t.isRevoked = false")
  int revokeByTokenHash(@Param("hash") String tokenHash);

  @Transactional
  @Modifying
  @Query("update RefreshToken t set t.isRevoked = true where t.userId = :userId and t.isRevoked = false")
  int revokeAllForUser(@Param("userId") String userId);

  @Transactional
  @Modifying
  @Query("update RefreshToken t set t.isRevoked = true where t.id = :id")
  int revokeById(@Param("id") Integer id);
}
