package com.kanban.config;

import com.kanban.modules.dashboard.DashboardCacheProperties;
import com.kanban.modules.presence.PresenceProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({AppProperties.class, DashboardCacheProperties.class, PresenceProperties.class})
public class PropertiesConfig {}
