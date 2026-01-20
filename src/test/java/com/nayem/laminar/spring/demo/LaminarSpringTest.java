package com.nayem.laminar.spring.demo;

import com.nayem.laminar.spring.*;
import com.nayem.laminar.core.Mutation;
import com.nayem.laminar.demo.XPMutation;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(classes = LaminarSpringTest.TestConfig.class)
public class LaminarSpringTest {

    @Autowired
    TestService service;

    @Autowired
    TestHandler handler;

    @Test
    public void testAnnotationMagic() throws InterruptedException {
        // user_1 starts with 100 XP
        handler.db.set(100);

        // Call the method annotated with @Dispatch
        // This should trigger the aspect -> engine -> worker -> handler.save
        service.addXp("user_1", 50);

        // Wait a bit for the async engine
        Thread.sleep(100);

        // Verify DB updated
        assertEquals(150, handler.db.get());
    }

    @Configuration
    @Component
    @org.springframework.context.annotation.EnableAspectJAutoProxy
    static class TestConfig {

        @Bean
        public LaminarProperties laminarProperties() {
            return new LaminarProperties();
        }

        @Bean
        @org.springframework.context.annotation.Primary
        public LaminarRegistry registry(java.util.List<LaminarHandler<?>> handlers,
                LaminarProperties properties) {
            return new LaminarAutoConfiguration().laminarRegistry(handlers,
                    new org.springframework.beans.factory.support.StaticListableBeanFactory()
                            .getBeanProvider(io.micrometer.core.instrument.MeterRegistry.class),
                    properties);
        }

        @Bean
        public LaminarAspect aspect(LaminarRegistry reg) {
            return new LaminarAspect(reg);
        }

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
