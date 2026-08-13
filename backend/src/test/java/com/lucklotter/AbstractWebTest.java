package com.lucklotter;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base for tests that exercise the HTTP layer through the real filter chain.
 *
 * <p>Deliberately {@code @SpringBootTest} with {@code MockMvc} rather than a
 * {@code @WebMvcTest} slice with mocked services. The behaviour worth testing up
 * here is precisely what a slice replaces: the JWT filter, the security rules,
 * the exception handler and the JSON mapping, assembled in the order the running
 * application assembles them. A slice would confirm the controller returns what
 * it was told to return — which the service tests already cover — while the
 * things that have actually broken (an unknown route answering 500, a tenant
 * boundary) live in the wiring between them.
 *
 * <p>The seeder stays off: these tests build the exact fixtures they need, and a
 * seeded business would make "no flags for this tenant" accidentally true or
 * accidentally false depending on load order.
 */
@SpringBootTest(properties = "lucklotter.seed.enabled=false")
@AutoConfigureMockMvc
public abstract class AbstractWebTest {

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        PostgresTestContainer.registerDatasourceProperties(registry);
    }
}
