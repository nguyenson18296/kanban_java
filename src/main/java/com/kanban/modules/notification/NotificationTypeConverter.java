package com.kanban.modules.notification;

import com.kanban.modules.common.WireEnumConverter;
import jakarta.persistence.Converter;

@Converter
public class NotificationTypeConverter extends WireEnumConverter<NotificationType> {
  public NotificationTypeConverter() {
    super(NotificationType.class);
  }
}
