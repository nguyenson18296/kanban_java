package com.kanban.modules.subscription;

import com.kanban.modules.common.WireEnumConverter;
import jakarta.persistence.Converter;

@Converter
public class SubscriptionSourceConverter extends WireEnumConverter<SubscriptionSource> {
  public SubscriptionSourceConverter() {
    super(SubscriptionSource.class);
  }
}
