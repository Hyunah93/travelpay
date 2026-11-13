package com.travelpay.user;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.travelpay.common.auth.AuthHeaders;
import com.travelpay.user.application.service.LoginAttemptLimiter;
import com.travelpay.user.application.service.PasswordChangeService;
import com.travelpay.user.common.config.LoginAttemptProperties;
import com.travelpay.user.common.config.PasswordChangeProperties;
import com.travelpay.user.infrastructure.redis.RedisLoginAttemptLimiter;
import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/** 테스트용으로 고른 빈이 아니라 실제 애플리케이션의 컴포넌트 스캔·자동 설정·application.yml로 시작합니다. */
@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "spring.cloud.discovery.enabled=false",
        "spring.cloud.service-registry.auto-registration.enabled=false",
        // 로그 형식은 테스트 JVM 전체에 남습니다. 다른 테스트가 일반 텍스트 로그를 확인하므로 JSON 로그를 켜지 않습니다.
        "logging.structured.format.console="
})
@ExtendWith(OutputCaptureExtension.class)
class UserServiceApplicationTest {

    private static final String REDIS_PASSWORD = UUID.randomUUID().toString();

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-bookworm")
            .withDatabaseName("travelpay_user_app_test")
            .withUsername("travelpay_test")
            .withPassword(UUID.randomUUID().toString())
            .withInitScript("postgres/user-schema.sql")
            .withUrlParam("currentSchema", "user_service");

    private static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:8.2.11-bookworm"))
            .withExposedPorts(6379)
            .withCommand("redis-server", "--requirepass", REDIS_PASSWORD, "--appendonly", "no", "--save", "");

    static {
        POSTGRES.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void connections(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> REDIS_PASSWORD);
    }

    private static final Set<RequestMethod> STATE_CHANGING =
            Set.of(RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE);
    private static final Set<String> PUBLIC_MUTATIONS = Set.of("POST /api/v1/users", "POST /api/v1/auth/login");

    @Autowired private ApplicationContext context;

    @Test
    void startsWithoutTheUnusedDefaultUserOrItsGeneratedPassword(CapturedOutput output) {
        assertEquals(Map.of(), context.getBeansOfType(UserDetailsService.class));
        assertFalse(output.getAll().contains("generated security password"));
    }

    @Test
    void wiresTheRedisLoginAttemptLimiterWithTheConfiguredPolicy() {
        assertInstanceOf(RedisLoginAttemptLimiter.class, context.getBean(LoginAttemptLimiter.class));
        assertEquals(new LoginAttemptProperties(5, 20, Duration.ofMinutes(10), Duration.ofMinutes(20)),
                context.getBean(LoginAttemptProperties.class));
    }

    @Test
    void startsOnTheSchemaFlywayMigratedWhileHibernateOnlyValidatesIt() {
        var applied = Arrays.stream(context.getBean(Flyway.class).info().applied())
                .map(migration -> migration.getVersion().getVersion())
                .toList();

        assertEquals(List.of("1", "2", "3"), applied);
        assertEquals("validate", context.getBean(EntityManagerFactory.class).getProperties().get("hibernate.hbm2ddl.auto"));
    }

    @Test
    void wiresPasswordChangeWithTheConfiguredDatabaseTransactionTimeout() {
        assertInstanceOf(PasswordChangeService.class, context.getBean(PasswordChangeService.class));
        assertEquals(Duration.ofSeconds(5), context.getBean(PasswordChangeProperties.class).transactionTimeout());
    }

    // 로그인 세션이 없으면 CSRF를 확인하지 않으므로, 로그인 없이 상태를 바꾸는 API는 SecurityConfiguration의 공개 목록에
    // 있어야 CSRF를 확인합니다. 실제 컨트롤러의 상태 변경 API 전체를 세션·CSRF 없이 불러, 목록 밖의 API가 로그인 없이 열리지 않았는지 봅니다.
    @Test
    void everyStateChangingApiNeedsALoginUnlessItIsAPublicApiThatChecksCsrf() throws Exception {
        var mvc = MockMvcBuilders.webAppContextSetup((WebApplicationContext) context).apply(springSecurity()).build();
        var mappings = context.getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping.class);
        var checked = new ArrayList<String>();
        for (var mapping : mappings.getHandlerMethods().keySet()) {
            // 메서드를 정하지 않은 매핑은 모든 메서드를 받으므로 상태 변경 메서드 전체로 봅니다.
            var methods = mapping.getMethodsCondition().getMethods().isEmpty()
                    ? STATE_CHANGING : mapping.getMethodsCondition().getMethods();
            for (var method : methods) {
                if (!STATE_CHANGING.contains(method)) {
                    continue;
                }
                for (var pattern : mapping.getPathPatternsCondition().getPatternValues()) {
                    var api = method + " " + pattern;
                    var path = pattern.replaceAll("\\{[^}]+}", UUID.randomUUID().toString());
                    // 세션 확인 API는 원래 메서드 헤더가 없으면 인증 전에 400이므로 함께 보냅니다(다른 API는 쓰지 않음).
                    var response = mvc.perform(request(HttpMethod.valueOf(method.name()), path)
                            .header(AuthHeaders.ORIGINAL_METHOD, "POST")).andReturn().getResponse();
                    var publicApi = PUBLIC_MUTATIONS.contains(api);
                    assertEquals(publicApi ? 403 : 401, response.getStatus(), api);
                    assertTrue(response.getContentAsString(UTF_8).contains(
                            publicApi ? "COMMON_CSRF_INVALID" : "COMMON_UNAUTHORIZED"), api);
                    checked.add(api);
                }
            }
        }
        // 대상을 하나도 찾지 못하면 이 시험은 아무것도 보지 않으므로, 아는 API가 포함됐는지 먼저 봅니다.
        assertThat(checked).contains("POST /api/v1/users", "POST /api/v1/auth/login", "POST /api/v1/auth/logout",
                "PATCH /api/v1/users/me/password", "POST /internal/v1/auth/session");
    }
}
