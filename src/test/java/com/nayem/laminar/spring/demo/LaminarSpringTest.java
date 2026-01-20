package com.nayem.laminar.spring.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nayem.laminar.core.Mutation;
import com.nayem.laminar.demo.XPMutation;
import com.nayem.laminar.spring.Dispatch;
import com.nayem.laminar.spring.LaminarAutoConfiguration;
import com.nayem.laminar.spring.LaminarHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(classes = LaminarSpringTest.TestConfig.class)
@Import(LaminarAutoConfiguration.class)
public class LaminarSpringTest {

    @Autowired
    TestService service;

    @Autowired
    TestHandler handler;

    // Mock Redis dependencies that are now required by AutoConfiguration
    @MockitoBean
    StringRedisTemplate redisTemplate;

    @MockitoBean
    ObjectMapper objectMapper;

    @Test
    public void testAnnotationMagic() throws InterruptedException {
        // user_1 starts with 100 XP
        handler.db.set(100);

        // Call the method annotated with @Dispatch
        service.addXp("user_1", 50);

        // Wait a bit for the async engine
        Thread.sleep(200);

        // Verify DB updated
        assertEquals(150, handler.db.get());
    }

    @Configuration
    static class TestConfig {

        @Bean
        public TestHandler testHandler() {
            return new TestHandler();
        }

        @Bean
        public TestService testService() {
            return new TestService();
        }
    }

    static class TestHandler implements LaminarHandler<AtomicLong> {
        public AtomicLong db = new AtomicLong(0);

        @Override
        public AtomicLong load(String key) {
            return db;
        }

        @Override
        public void save(AtomicLong entity) {
            // No-op for test
        }

        @Override
        public Class<AtomicLong> getEntityType() {
            return AtomicLong.class;
        }
    }

    static class TestService {
        @Dispatch
        public Mutation<AtomicLong> addXp(String userId, long amount) {
            return new XPMutation(userId, amount);
        }
    }
}