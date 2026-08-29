package com.kanban.modules.label;

import com.kanban.common.exception.ConflictException;
import com.kanban.common.exception.HttpException;
import com.kanban.common.exception.InternalServerErrorException;
import com.kanban.common.exception.NotFoundException;
import com.kanban.common.json.Json;
import com.kanban.common.util.PgErrors;
import com.kanban.modules.label.dto.CreateLabelDto;
import com.kanban.modules.label.dto.UpdateLabelDto;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class LabelService {
  private static final Logger log = LoggerFactory.getLogger(LabelService.class);
  private final LabelRepository labelRepository;

  public LabelService(LabelRepository labelRepository) {
    this.labelRepository = labelRepository;
  }

  public Label create(CreateLabelDto dto) {
    try {
      Label label = new Label();
      label.setName(dto.name);
      label.setColor(dto.color);
      return labelRepository.saveAndFlush(label);
    } catch (RuntimeException error) {
      if (PgErrors.isCode(error, PgErrors.UNIQUE_VIOLATION)) {
        throw new ConflictException(Json.map(
            "statusCode", 409,
            "message", "Label with name \"" + dto.name + "\" already exists",
            "error", PgErrors.message(error)));
      }
      log.error("Failed to create label", error);
      throw internal("Failed to create label", error);
    }
  }

  public List<Label> findAll() {
    try {
      return labelRepository.findAll();
    } catch (RuntimeException e) {
      log.error("Failed to fetch labels", e);
      throw internal("Failed to fetch labels", e);
    }
  }

  public Label findOneById(int id) {
    try {
      return labelRepository.findById(id).orElseThrow(() -> new NotFoundException(Json.map(
          "statusCode", 404,
          "message", "Label with id \"" + id + "\" not found")));
    } catch (NotFoundException e) {
      throw e;
    } catch (RuntimeException e) {
      log.error("Failed to fetch label", e);
      throw internal("Failed to fetch label", e);
    }
  }

  public Label update(int id, UpdateLabelDto dto) {
    try {
      Label label = findOneById(id);
      if (dto.has("name")) {
        label.setName(dto.name);
      }
      if (dto.has("color")) {
        label.setColor(dto.color);
      }
      return labelRepository.saveAndFlush(label);
    } catch (NotFoundException | ConflictException e) {
      throw e;
    } catch (RuntimeException error) {
      if (PgErrors.isCode(error, PgErrors.UNIQUE_VIOLATION)) {
        throw new ConflictException(Json.map(
            "statusCode", 409,
            "message", "Label with name \"" + dto.name + "\" already exists",
            "error", PgErrors.message(error)));
      }
      log.error("Failed to update label", error);
      throw internal("Failed to update label", error);
    }
  }

  public void remove(int id) {
    try {
      findOneById(id);
      labelRepository.deleteById(id);
    } catch (NotFoundException e) {
      throw e;
    } catch (RuntimeException error) {
      log.error("Failed to delete label", error);
      throw internal("Failed to delete label", error);
    }
  }

  private static HttpException internal(String message, Throwable cause) {
    return new InternalServerErrorException(Json.map(
        "statusCode", 500, "message", message, "error", PgErrors.message(cause)));
  }
}
