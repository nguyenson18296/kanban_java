package com.kanban.modules.events.guards;

import com.kanban.modules.auth.AuthService;
import com.kanban.modules.auth.JwtService;
import com.kanban.modules.auth.interfaces.JwtPayload;
import com.kanban.modules.events.WsException;
import com.kanban.modules.events.socket.SocketClient;
import com.kanban.modules.user.User;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class WsJwtGuard {
  private static final Logger log = LoggerFactory.getLogger(WsJwtGuard.class);
  private final JwtService jwtService;
  private final AuthService authService;

  public WsJwtGuard(JwtService jwtService, AuthService authService) {
    this.jwtService = jwtService;
    this.authService = authService;
  }

  public boolean canActivate(SocketClient client) {
    User user = validateToken(client);
    client.data().put("user", user);
    return true;
  }

  /** Token from {@code handshake.auth.token}, falling back to the Authorization header. */
  public User validateToken(SocketClient client) {
    String token = null;
    Map<String, Object> auth = client.handshake() == null ? null : client.handshake().auth();
    if (auth != null && auth.get("token") != null) {
      token = String.valueOf(auth.get("token"));
    } else if (client.handshake() != null) {
      String authorization = client.handshake().header("authorization");
      if (authorization != null) {
        String[] parts = authorization.split(" ");
        token = parts.length > 1 ? parts[1] : null;
      }
    }
    if (token == null) {
      throw new WsException("Missing authentication token");
    }
    try {
      JwtPayload payload = jwtService.verify(token);
      User user = authService.validateUserById(payload.sub());
      if (user == null) {
        throw new WsException("User not found or inactive");
      }
      return user;
    } catch (WsException e) {
      throw e;
    } catch (RuntimeException e) {
      log.warn("WebSocket auth failed: {}", e.getMessage());
      throw new WsException("Invalid or expired token");
    }
  }
}
