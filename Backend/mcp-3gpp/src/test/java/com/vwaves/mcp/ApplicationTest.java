package com.vwaves.mcp;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.boot.SpringApplication;

/**
 * Covers {@link Application#main} without booting a Spring context:
 * {@link SpringApplication#run} is statically mocked, so the test only pins
 * that main delegates to Spring with the untouched argument array.
 */
class ApplicationTest {

    @Test
    void mainDelegatesToSpringApplicationRunWithTheGivenArgs() {
        String[] args = {"--server.port=0", "--some.flag=on"};
        try (MockedStatic<SpringApplication> mocked = Mockito.mockStatic(SpringApplication.class)) {
            Application.main(args);
            mocked.verify(() -> SpringApplication.run(Application.class, args));
            mocked.verifyNoMoreInteractions();
        }
    }

    @Test
    void classIsInstantiableAsABeanRoot() {
        // Spring instantiates the @SpringBootApplication class as a bean.
        assertDoesNotThrow(Application::new);
    }
}
