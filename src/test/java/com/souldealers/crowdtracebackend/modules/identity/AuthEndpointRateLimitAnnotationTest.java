package com.souldealers.crowdtracebackend.modules.identity;

import com.souldealers.crowdtracebackend.shared.ratelimit.RateLimit;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AuthEndpointRateLimitAnnotationTest {

    @Test
    void everyStateChangingAuthEndpointCarriesARateLimit() {
        List<Method> unprotected = Arrays.stream(AuthController.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(PostMapping.class))
                .filter(method -> !method.isAnnotationPresent(RateLimit.class))
                .toList();

        assertThat(unprotected)
                .as("every POST on AuthController must name an IP rate-limit policy")
                .isEmpty();
    }

    @Test
    void allSixAuthEndpointsAreCovered() {
        long annotated = Arrays.stream(AuthController.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(RateLimit.class))
                .count();

        assertThat(annotated).isEqualTo(6);
    }
}
