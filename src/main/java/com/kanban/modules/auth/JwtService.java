package com.kanban.modules.auth;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.kanban.common.util.DurationParser;
import com.kanban.config.AppProperties;
import com.kanban.modules.auth.interfaces.JwtPayload;
import java.time.Instant;
import org.springframework.stereotype.Service;

/** Port of @nestjs/jwt JwtService (HS256, {@code expiresIn} in ms grammar). */
@Service
public class JwtService {
  private final Algorithm algorithm;
  private final long expiresInSeconds;

  public JwtService(AppProperties props) {
    String secret = props.jwt().secret();
    if (secret == null || secret.isBlank()) {
      throw new IllegalStateException("JWT_SECRET is required");
    }
    this.algorithm = Algorithm.HMAC256(secret);
    String expiresIn = props.jwt().expiresIn() == null ? "1h" : props.jwt().expiresIn();
    this.expiresInSeconds = DurationParser.toSeconds(expiresIn);
  }

  public String sign(JwtPayload payload) {
    Instant now = Instant.now();
    return JWT.create()
        .withSubject(payload.sub())
        .withClaim("email", payload.email())
        .withClaim("role", payload.role())
        .withIssuedAt(now)
        .withExpiresAt(now.plusSeconds(expiresInSeconds))
        .sign(algorithm);
  }

  /** @throws JWTVerificationException on bad signature / expiry / malformed token. */
  public JwtPayload verify(String token) {
    DecodedJWT decoded = JWT.require(algorithm).build().verify(token);
    return new JwtPayload(decoded.getSubject(), decoded.getClaim("email").asString(),
        decoded.getClaim("role").asString());
  }
}
