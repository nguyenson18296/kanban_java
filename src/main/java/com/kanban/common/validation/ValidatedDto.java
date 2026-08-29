package com.kanban.common.validation;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.HashSet;
import java.util.Set;

/**
 * Base class for request DTOs. Tracks which keys were present in the payload so
 * services can distinguish "absent" (undefined) from an explicit {@code null},
 * exactly as the TypeScript services do with {@code !== undefined} checks.
 */
public abstract class ValidatedDto {
  @JsonIgnore
  private final transient Set<String> presentKeys = new HashSet<>();

  void markPresent(String key) {
    presentKeys.add(key);
  }

  /** True when the key appeared in the request payload (even with a null value). */
  public boolean has(String key) {
    return presentKeys.contains(key);
  }

  /** Test helper: mark a key as provided. */
  public <T extends ValidatedDto> T with(String key) {
    presentKeys.add(key);
    @SuppressWarnings("unchecked")
    T self = (T) this;
    return self;
  }
}
