package com.adobe.mcp;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

class AemMcpApplicationTest {

    @Test
    void main_delegatesToSpringApplicationRun() {
        String[] args = { "--server.port=0" };
        try (MockedStatic<SpringApplication> springApp = mockStatic(SpringApplication.class)) {
            springApp.when(() -> SpringApplication.run(eq(AemMcpApplication.class), any(String[].class)))
                    .thenReturn(mock(ConfigurableApplicationContext.class));

            AemMcpApplication.main(args);

            springApp.verify(() -> SpringApplication.run(AemMcpApplication.class, args));
        }
    }

    @Test
    void defaultConstructor() {
        assertThat(new AemMcpApplication()).isNotNull();
    }
}
