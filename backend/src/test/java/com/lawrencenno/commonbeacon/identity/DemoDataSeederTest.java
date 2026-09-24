package com.lawrencenno.commonbeacon.identity;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DemoDataSeederTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(DemoDataSeeder.class)
            .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
            .withBean(org.springframework.transaction.PlatformTransactionManager.class, () -> mock(org.springframework.transaction.PlatformTransactionManager.class))
            .withBean(PasswordEncoder.class, () -> mock(PasswordEncoder.class));

    @Test void requiresLocalProfileAndExplicitOptIn() {
        context.withPropertyValues("commonbeacon.demo.enabled=true").run(c -> assertThat(c).doesNotHaveBean(DemoDataSeeder.class));
        context.withPropertyValues("spring.profiles.active=local").run(c -> assertThat(c).doesNotHaveBean(DemoDataSeeder.class));
        context.withPropertyValues("spring.profiles.active=local", "commonbeacon.demo.enabled=true")
                .run(c -> assertThat(c).hasSingleBean(DemoDataSeeder.class));
    }

    @Test void productionBlocksSeedingEvenWhenLocalAndEnabledAreAlsoSet() {
        context.withPropertyValues("spring.profiles.active=local,prod", "commonbeacon.demo.enabled=true")
                .run(c -> assertThat(c).doesNotHaveBean(DemoDataSeeder.class));
        context.withPropertyValues("spring.profiles.active=prod", "commonbeacon.demo.enabled=true")
                .run(c -> assertThat(c).doesNotHaveBean(DemoDataSeeder.class));
    }

    @Test void missingOrInvalidPasswordFailsBeforeWritingAnyData() {
        var jdbc = mock(JdbcTemplate.class);
        var encoder = mock(PasswordEncoder.class);
        for (String password : new String[] {"", "short", "x".repeat(129)}) {
            assertThatThrownBy(() -> new DemoDataSeeder(jdbc, encoder, password, mock(org.springframework.transaction.PlatformTransactionManager.class)).run(new DefaultApplicationArguments()))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("DEMO_PASSWORD");
        }
        verifyNoInteractions(jdbc, encoder);
    }
}
