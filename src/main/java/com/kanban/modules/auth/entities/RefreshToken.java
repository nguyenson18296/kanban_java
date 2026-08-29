package com.kanban.modules.auth.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.SourceType;

@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Integer id;

  @Column(name = "user_id", nullable = false, columnDefinition = "uuid")
  private String userId;

  @Column(name = "token_hash", nullable = false, unique = true, columnDefinition = "text")
  private String tokenHash;

  @Column(name = "device_info", length = 255)
  private String deviceInfo;

  @Column(name = "ip_address", length = 45)
  private String ipAddress;

  @Column(name = "is_revoked", nullable = false)
  private boolean isRevoked = false;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @CreationTimestamp(source = SourceType.DB)
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  public Integer getId() { return id; }
  public void setId(Integer id) { this.id = id; }
  public String getUserId() { return userId; }
  public void setUserId(String userId) { this.userId = userId; }
  public String getTokenHash() { return tokenHash; }
  public void setTokenHash(String tokenHash) { this.tokenHash = tokenHash; }
  public String getDeviceInfo() { return deviceInfo; }
  public void setDeviceInfo(String deviceInfo) { this.deviceInfo = deviceInfo; }
  public String getIpAddress() { return ipAddress; }
  public void setIpAddress(String ipAddress) { this.ipAddress = ipAddress; }
  public boolean isRevoked() { return isRevoked; }
  public void setRevoked(boolean revoked) { isRevoked = revoked; }
  public Instant getExpiresAt() { return expiresAt; }
  public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
  public Instant getCreatedAt() { return createdAt; }
  public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
