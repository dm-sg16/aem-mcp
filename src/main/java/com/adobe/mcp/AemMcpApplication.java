package com.adobe.mcp;

import com.adobe.mcp.aem.AemProperties;
import com.adobe.mcp.config.AemMcpAuthProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({AemProperties.class, AemMcpAuthProperties.class})
public class AemMcpApplication {

    public static void main(String[] args) {
        SpringApplication.run(AemMcpApplication.class, args);
    }
}
