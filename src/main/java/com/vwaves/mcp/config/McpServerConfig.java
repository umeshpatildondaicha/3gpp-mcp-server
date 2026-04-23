package com.vwaves.mcp.config;

import com.vwaves.mcp.service.ThreeGppToolService;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class McpServerConfig {

    @Bean
    ToolCallbackProvider threeGppTools(ThreeGppToolService toolService) {
        return MethodToolCallbackProvider.builder().toolObjects(toolService).build();
    }
}
