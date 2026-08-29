package com.kanban.modules.auth.interfaces;

public record JwtPayload(String sub, String email, String role) {}
