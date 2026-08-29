package com.kanban.modules.auth;

import com.kanban.common.exception.NotFoundException;
import com.kanban.common.exception.UnauthorizedException;
import com.kanban.config.AppProperties;
import com.kanban.modules.auth.dto.AuthResponseDto;
import com.kanban.modules.auth.dto.LoginDto;
import com.kanban.modules.auth.dto.RegisterDto;
import com.kanban.modules.auth.entities.RefreshToken;
import com.kanban.modules.auth.entities.RefreshTokenRepository;
import com.kanban.modules.auth.interfaces.JwtPayload;
import com.kanban.modules.user.User;
import com.kanban.modules.user.UserService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {
  private static final Logger log = LoggerFactory.getLogger(AuthService.class);
  private static final Pattern DAYS = Pattern.compile("^(\\d+)d$");
  private static final SecureRandom RANDOM = new SecureRandom();

  private final UserService userService;
  private final JwtService jwtService;
  private final RefreshTokenRepository refreshTokenRepository;
  private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder(BCryptPasswordEncoder.BCryptVersion.$2B, 10);
  private final String refreshTokenExpiresIn;

  public AuthService(UserService userService, JwtService jwtService, RefreshTokenRepository refreshTokenRepository,
      AppProperties props) {
    this.userService = userService;
    this.jwtService = jwtService;
    this.refreshTokenRepository = refreshTokenRepository;
    this.refreshTokenExpiresIn = props.refreshToken() == null || props.refreshToken().expiresIn() == null
        ? "30d" : props.refreshToken().expiresIn();
  }

  public AuthResponseDto register(RegisterDto dto, String ip, String deviceInfo) {
    String passwordHash = bcrypt.encode(dto.password);
    User user = userService.create(dto.email, dto.full_name, passwordHash);
    String accessToken = signToken(user);
    String refreshToken = randomToken();
    storeRefreshToken(user.getId(), refreshToken, deviceInfo, ip);
    return new AuthResponseDto(accessToken, refreshToken, AuthResponseDto.AuthUserDto.of(user));
  }

  public AuthResponseDto login(LoginDto dto, String ip, String deviceInfo) {
    User user = userService.findOneByEmailWithPassword(dto.email);
    if (user == null) {
      throw new UnauthorizedException("Invalid credentials");
    }
    if (!bcrypt.matches(dto.password, user.getPasswordHash())) {
      throw new UnauthorizedException("Invalid credentials");
    }
    if (!user.isActive()) {
      throw new UnauthorizedException("Account is deactivated");
    }
    String accessToken = signToken(user);
    String refreshToken = randomToken();
    storeRefreshToken(user.getId(), refreshToken, deviceInfo, ip);
    return new AuthResponseDto(accessToken, refreshToken, AuthResponseDto.AuthUserDto.of(user));
  }

  public AuthResponseDto refresh(String oldRefreshToken, String ip, String deviceInfo) {
    String oldHash = hashToken(oldRefreshToken);
    RefreshToken existing = refreshTokenRepository.findByTokenHash(oldHash).orElse(null);
    if (existing == null) {
      throw new UnauthorizedException("Refresh token invalid");
    }
    // Reuse detection: if token was already revoked, revoke ALL user tokens
    if (existing.isRevoked()) {
      refreshTokenRepository.revokeAllForUser(existing.getUserId());
      log.warn("Refresh token reuse detected — all sessions revoked for user {}", existing.getUserId());
      throw new UnauthorizedException("Refresh token revoked");
    }
    if (existing.getExpiresAt().isBefore(Instant.now())) {
      refreshTokenRepository.revokeById(existing.getId());
      throw new UnauthorizedException("Refresh token expired");
    }
    refreshTokenRepository.revokeById(existing.getId());

    User user;
    try {
      user = userService.findOneById(existing.getUserId());
    } catch (NotFoundException e) {
      throw new UnauthorizedException("Account no longer exists");
    }
    if (!user.isActive()) {
      throw new UnauthorizedException("Account is deactivated");
    }
    String newRefreshToken = randomToken();
    storeRefreshToken(user.getId(), newRefreshToken, deviceInfo, ip);
    String accessToken = signToken(user);
    return new AuthResponseDto(accessToken, newRefreshToken, AuthResponseDto.AuthUserDto.of(user));
  }

  public boolean logout(String refreshToken) {
    return refreshTokenRepository.revokeByTokenHash(hashToken(refreshToken)) > 0;
  }

  public int logoutAll(String userId) {
    return refreshTokenRepository.revokeAllForUser(userId);
  }

  /** Null when the user is missing or inactive (JwtStrategy then rejects with 401). */
  public User validateUserById(String id) {
    try {
      User user = userService.findOneById(id);
      return user.isActive() ? user : null;
    } catch (NotFoundException e) {
      return null;
    } catch (RuntimeException e) {
      log.error("Unexpected error validating user {}", id, e);
      throw e;
    }
  }

  private String signToken(User user) {
    return jwtService.sign(new JwtPayload(user.getId(), user.getEmail(), user.getRole().value()));
  }

  /** {@code randomBytes(32).toString('base64url')} */
  static String randomToken() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  static String hashToken(String raw) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private void storeRefreshToken(String userId, String rawToken, String deviceInfo, String ip) {
    RefreshToken token = new RefreshToken();
    token.setUserId(userId);
    token.setTokenHash(hashToken(rawToken));
    token.setDeviceInfo(deviceInfo == null || deviceInfo.isEmpty() ? null : deviceInfo);
    token.setIpAddress(ip == null || ip.isEmpty() ? null : ip);
    token.setExpiresAt(Instant.now().plus(getRefreshTokenTtlDays(), ChronoUnit.DAYS));
    refreshTokenRepository.save(token);
  }

  int getRefreshTokenTtlDays() {
    Matcher m = DAYS.matcher(refreshTokenExpiresIn);
    return m.matches() ? Integer.parseInt(m.group(1)) : 30;
  }
}
