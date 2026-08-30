package com.kanban;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.kanban.common.exception.GlobalExceptionHandler;
import com.kanban.config.JacksonConfig;
import com.kanban.config.WebMvcConfig;
import com.kanban.modules.auth.AuthController;
import com.kanban.modules.auth.AuthService;
import com.kanban.modules.auth.JwtService;
import com.kanban.modules.auth.guards.JwtAuthInterceptor;
import com.kanban.modules.auth.interfaces.JwtPayload;
import com.kanban.modules.board.BoardController;
import com.kanban.modules.board.BoardService;
import com.kanban.modules.project.ProjectAccessService;
import com.kanban.modules.project.guards.ProjectRoleInterceptor;
import com.kanban.modules.user.User;
import com.kanban.modules.user.UserController;
import com.kanban.modules.user.UserRole;
import com.kanban.modules.user.UserService;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Port of test/app.e2e-spec.ts (GET / → "Hello World!") plus the Nest wire-format
 * contracts: validation error bodies, guard 401 body, pipe 400 body, unknown route 404.
 */
@WebMvcTest(controllers = {AppController.class, AuthController.class, UserController.class,
    BoardController.class})
@Import({WebMvcConfig.class, JacksonConfig.class, GlobalExceptionHandler.class, JwtAuthInterceptor.class,
    ProjectRoleInterceptor.class, AppService.class})
class WebLayerTest {
  @Autowired
  private MockMvc mvc;

  @MockitoBean
  private AuthService authService;

  @MockitoBean
  private JwtService jwtService;

  @MockitoBean
  private UserService userService;

  @MockitoBean
  private BoardService boardService;

  @MockitoBean
  private ProjectAccessService projectAccessService;

  @Test
  @DisplayName("/ (GET) → 200 Hello World!")
  void hello() throws Exception {
    mvc.perform(get("/api"))
        .andExpect(status().isOk())
        .andExpect(content().string("Hello World!"));
  }

  @Test
  @DisplayName("ValidationPipe body: { message: [...], error: 'Bad Request', statusCode: 400 }")
  void validationErrorBody() throws Exception {
    mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("Bad Request"))
        .andExpect(jsonPath("$.statusCode").value(400))
        .andExpect(jsonPath("$.message[0]").value("email must be an email"))
        .andExpect(jsonPath("$.message.length()").value(7));
  }

  @Test
  @DisplayName("forbidNonWhitelisted: unknown key → 'property x should not exist'")
  void unknownKey() throws Exception {
    mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"a@b.co\",\"password\":\"x\",\"extra\":1}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message[0]").value("property extra should not exist"));
  }

  @Test
  @DisplayName("JwtAuthGuard without token → { message: 'Unauthorized', statusCode: 401 }")
  void unauthorizedBody() throws Exception {
    mvc.perform(get("/api/auth/me"))
        .andExpect(status().isUnauthorized())
        .andExpect(content().json("{\"message\":\"Unauthorized\",\"statusCode\":401}", true));
  }

  @Test
  @DisplayName("GET /board/:projectId without token → 401 (KAN: board is now guarded)")
  void boardRequiresToken() throws Exception {
    mvc.perform(get("/api/board/UrzWUH3e"))
        .andExpect(status().isUnauthorized())
        .andExpect(content().json("{\"message\":\"Unauthorized\",\"statusCode\":401}", true));
  }

  @Test
  @DisplayName("JwtAuthGuard with a valid token → current user profile (no password_hash)")
  void authorizedProfile() throws Exception {
    when(jwtService.verify("tok")).thenReturn(new JwtPayload("u1", "a@b.co", "backend_developer"));
    User user = new User("u1", "a@b.co", "A", UserRole.BACKEND_DEVELOPER, "https://x/y", true);
    user.setPasswordHash("secret");
    when(authService.validateUserById("u1")).thenReturn(user);
    mvc.perform(get("/api/auth/me").header("Authorization", "Bearer tok"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value("u1"))
        .andExpect(jsonPath("$.full_name").value("A"))
        .andExpect(jsonPath("$.role").value("backend_developer"))
        .andExpect(jsonPath("$.password_hash").doesNotExist());
  }

  @Test
  @DisplayName("ParseUUIDPipe → 400 'Validation failed (uuid is expected)'")
  void uuidPipe() throws Exception {
    mvc.perform(get("/api/users/not-a-uuid"))
        .andExpect(status().isBadRequest())
        .andExpect(content().json(
            "{\"message\":\"Validation failed (uuid is expected)\",\"error\":\"Bad Request\",\"statusCode\":400}",
            true));
  }

  @Test
  @DisplayName("unknown route → { message: 'Cannot GET /api/nope', error: 'Not Found', statusCode: 404 }")
  void unknownRoute() throws Exception {
    mvc.perform(get("/api/nope"))
        .andExpect(status().isNotFound())
        .andExpect(content().json(
            "{\"message\":\"Cannot GET /api/nope\",\"error\":\"Not Found\",\"statusCode\":404}", true));
  }

  @Test
  @DisplayName("malformed JSON → 400 Bad Request body")
  void malformedJson() throws Exception {
    mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{bad json"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("Bad Request"))
        .andExpect(jsonPath("$.statusCode").value(400))
        .andExpect(jsonPath("$.message", Matchers.instanceOf(String.class)));
  }

  @Test
  @DisplayName("login success passes through the service response (200, not 201)")
  void loginStatus() throws Exception {
    when(authService.login(any(), any(), any())).thenReturn(new com.kanban.modules.auth.dto.AuthResponseDto(
        "at", "rt", new com.kanban.modules.auth.dto.AuthResponseDto.AuthUserDto("u1", "a@b.co", "A",
            UserRole.QA, null)));
    mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"a@b.co\",\"password\":\"secret\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.access_token").value("at"))
        .andExpect(jsonPath("$.user.role").value("qa"))
        .andExpect(jsonPath("$.user.avatar_url").value(Matchers.nullValue()));
  }
}
