package com.kanban.config;

import com.kanban.modules.dashboard.DashboardCacheProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({AppProperties.class, DashboardCacheProperties.class})
public class PropertiesConfig {}
