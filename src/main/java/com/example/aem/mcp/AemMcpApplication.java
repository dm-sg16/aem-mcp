package com.example.aem.mcp;

import com.example.aem.mcp.aem.AemProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(AemProperties.class)
public class AemMcpApplication {

    public static void main(String[] args) {
        SpringApplication.run(AemMcpApplication.class, args);
    }
}
