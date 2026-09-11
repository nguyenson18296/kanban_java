package com.kanban;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.kanban.common.api.ApiListResponse;
import com.kanban.common.exception.GlobalExceptionHandler;
import com.kanban.common.exception.NotFoundException;
import com.kanban.common.json.Json;
import com.kanban.config.JacksonConfig;
import com.kanban.config.WebMvcConfig;
import com.kanban.modules.auth.AuthController;
import com.kanban.modules.auth.AuthService;
import com.kanban.modules.auth.JwtService;
import com.kanban.modules.auth.guards.JwtAuthInterceptor;
import com.kanban.modules.auth.interfaces.JwtPayload;
import com.kanban.modules.board.BoardController;
import com.kanban.modules.board.BoardService;
import com.kanban.modules.board.dto.BoardResponse;
import com.kanban.modules.label.LabelController;
import com.kanban.modules.label.LabelService;
import com.kanban.modules.project.ProjectAccessService;
import com.kanban.modules.project.ProjectMember;
import com.kanban.modules.project.ProjectRole;
import com.kanban.modules.project.guards.ProjectRoleInterceptor;
import com.kanban.modules.team.TeamController;
import com.kanban.modules.team.TeamService;
import com.kanban.modules.user.User;
import com.kanban.modules.user.UserController;
import com.kanban.modules.user.UserRole;
import com.kanban.modules.user.UserService;
import java.util.List;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Port of test/app.e2e-spec.ts (GET / → "Hello World!") plus the Nest wire-format
 * contracts: validation error bodies, guard 401 body, pipe 400 body, unknown route 404.
 */
@WebMvcTest(controllers = {AppController.class, AuthController.class, UserController.class,
    BoardController.class, TeamController.class, LabelController.class})
@Import({WebMvcConfig.class, JacksonConfig.class, GlobalExceptionHandler.class, JwtAuthInterceptor.class,
    ProjectRoleInterceptor.class, AppService.class})
class WebLayerTest {
  private static final String OTHER_USER_ID = "22222222-2222-4222-8222-222222222222";

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

  @MockitoBean
  private TeamService teamService;

  @MockitoBean
  private LabelService labelService;

  private void assertUnauthorized(MockHttpServletRequestBuilder request) throws Exception {
    mvc.perform(request)
        .andExpect(status().isUnauthorized())
        .andExpect(content().json("{\"message\":\"Unauthorized\",\"statusCode\":401}", JsonCompareMode.STRICT));
  }

  /** Makes {@code Bearer tok} resolve to the active user {@code u1}. */
  private void authenticateU1() {
    when(jwtService.verify("tok")).thenReturn(new JwtPayload("u1", "a@b.co", "backend_developer"));
    when(authService.validateUserById("u1"))
        .thenReturn(new User("u1", "a@b.co", "A", UserRole.BACKEND_DEVELOPER, null, true));
  }

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
        .andExpect(content().json("{\"message\":\"Unauthorized\",\"statusCode\":401}", JsonCompareMode.STRICT));
  }

  @Test
  @DisplayName("GET /board/:projectId without token → 401 (KAN: board is now guarded)")
  void boardRequiresToken() throws Exception {
    mvc.perform(get("/api/board/UrzWUH3e"))
        .andExpect(status().isUnauthorized())
        .andExpect(content().json("{\"message\":\"Unauthorized\",\"statusCode\":401}", JsonCompareMode.STRICT));
  }

  @Test
  @DisplayName("GET /board/:projectId as a non-member → masked 404 (@RequireProjectRole gate)")
  void boardNonMemberMasked404() throws Exception {
    when(jwtService.verify("tok")).thenReturn(new JwtPayload("u1", "a@b.co", "backend_developer"));
    when(authService.validateUserById("u1"))
        .thenReturn(new User("u1", "a@b.co", "A", UserRole.BACKEND_DEVELOPER, null, true));
    when(projectAccessService.ensureRole(eq("UrzWUH3e"), eq("u1"), any()))
        .thenThrow(new NotFoundException(Json.map(
            "statusCode", 404, "message", "Project with id \"UrzWUH3e\" not found")));

    mvc.perform(get("/api/board/UrzWUH3e").header("Authorization", "Bearer tok"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.message").value("Project with id \"UrzWUH3e\" not found"));
  }

  @Test
  @DisplayName("GET /board/:projectId as a member → 200 (gate passes to the handler)")
  void boardMemberOk() throws Exception {
    when(jwtService.verify("tok")).thenReturn(new JwtPayload("u1", "a@b.co", "backend_developer"));
    when(authService.validateUserById("u1"))
        .thenReturn(new User("u1", "a@b.co", "A", UserRole.BACKEND_DEVELOPER, null, true));
    when(projectAccessService.ensureRole(eq("UrzWUH3e"), eq("u1"), any()))
        .thenReturn(new ProjectMember("UrzWUH3e", "u1", ProjectRole.VIEWER));
    when(boardService.getBoard(eq("UrzWUH3e"), any())).thenReturn(new BoardResponse(List.of()));

    mvc.perform(get("/api/board/UrzWUH3e").header("Authorization", "Bearer tok"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.columns").isArray());
  }

  @Test
  @DisplayName("GET /users, /users/me/projects, /users/:id, /users/:id/projects without token → 401 (JAV-21)")
  void userReadsRequireToken() throws Exception {
    assertUnauthorized(get("/api/users"));
    assertUnauthorized(get("/api/users/me/projects"));
    assertUnauthorized(get("/api/users/" + OTHER_USER_ID));
    assertUnauthorized(get("/api/users/" + OTHER_USER_ID + "/projects"));
  }

  @Test
  @DisplayName("GET /users/:id/projects hands the caller id to the service (the self-only check lives there)")
  void userProjectsPassCallerId() throws Exception {
    authenticateU1();
    when(userService.findProjects(OTHER_USER_ID, "u1")).thenReturn(ApiListResponse.ok(List.of()));

    mvc.perform(get("/api/users/" + OTHER_USER_ID + "/projects").header("Authorization", "Bearer tok"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data").isArray());
    verify(userService).findProjects(OTHER_USER_ID, "u1");
  }

  @Test
  @DisplayName("GET /users/me/projects resolves both ids to the caller")
  void myProjectsUseCallerId() throws Exception {
    authenticateU1();
    when(userService.findProjects("u1", "u1")).thenReturn(ApiListResponse.ok(List.of()));

    mvc.perform(get("/api/users/me/projects").header("Authorization", "Bearer tok"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data").isArray());
    verify(userService).findProjects("u1", "u1");
  }

  @Test
  @DisplayName("GET /projects/:projectId/teams[/:teamId[/members]] without token → 401 (JAV-21)")
  void teamReadsRequireToken() throws Exception {
    assertUnauthorized(get("/api/projects/UrzWUH3e/teams"));
    assertUnauthorized(get("/api/projects/UrzWUH3e/teams/7"));
    assertUnauthorized(get("/api/projects/UrzWUH3e/teams/7/members"));
  }

  @Test
  @DisplayName("every /labels route without token → 401 (JAV-21: labels stay global, JWT stops anonymous use)")
  void labelRoutesRequireToken() throws Exception {
    assertUnauthorized(get("/api/labels"));
    assertUnauthorized(get("/api/labels/1"));
    assertUnauthorized(post("/api/labels"));
    assertUnauthorized(patch("/api/labels/1"));
    assertUnauthorized(delete("/api/labels/1"));
  }

  @Test
  @DisplayName("team reads as a non-member → masked project 404 before the handler runs (@RequireProjectRole)")
  void teamReadsNonMemberMasked404() throws Exception {
    authenticateU1();
    when(projectAccessService.ensureRole(eq("UrzWUH3e"), eq("u1"), any()))
        .thenThrow(new NotFoundException(Json.map(
            "statusCode", 404, "message", "Project with id \"UrzWUH3e\" not found")));

    for (String path : List.of("/api/projects/UrzWUH3e/teams", "/api/projects/UrzWUH3e/teams/7",
        "/api/projects/UrzWUH3e/teams/7/members")) {
      mvc.perform(get(path).header("Authorization", "Bearer tok"))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.message").value("Project with id \"UrzWUH3e\" not found"));
    }
    verifyNoInteractions(teamService);
  }

  @Test
  @DisplayName("GET /projects/:projectId/teams as a member → viewer gate runs, then the handler")
  void teamListMemberOk() throws Exception {
    authenticateU1();
    when(projectAccessService.ensureRole(eq("UrzWUH3e"), eq("u1"), any()))
        .thenReturn(new ProjectMember("UrzWUH3e", "u1", ProjectRole.VIEWER));
    when(teamService.findAllByProject("UrzWUH3e")).thenReturn(ApiListResponse.ok(List.of()));

    mvc.perform(get("/api/projects/UrzWUH3e/teams").header("Authorization", "Bearer tok"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data").isArray());
    verify(projectAccessService).ensureRole("UrzWUH3e", "u1", ProjectRole.VIEWER);
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
  @DisplayName("ParseUUIDPipe → 400 'Validation failed (uuid is expected)' (route is @JwtAuth since JAV-21)")
  void uuidPipe() throws Exception {
    authenticateU1();
    mvc.perform(get("/api/users/not-a-uuid").header("Authorization", "Bearer tok"))
        .andExpect(status().isBadRequest())
        .andExpect(content().json(
            "{\"message\":\"Validation failed (uuid is expected)\",\"error\":\"Bad Request\",\"statusCode\":400}",
            JsonCompareMode.STRICT));
  }

  @Test
  @DisplayName("unknown route → { message: 'Cannot GET /api/nope', error: 'Not Found', statusCode: 404 }")
  void unknownRoute() throws Exception {
    mvc.perform(get("/api/nope"))
        .andExpect(status().isNotFound())
        .andExpect(content().json(
            "{\"message\":\"Cannot GET /api/nope\",\"error\":\"Not Found\",\"statusCode\":404}", JsonCompareMode.STRICT));
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
