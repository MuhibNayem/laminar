package com.nayem.laminar.migration;

import com.nayem.laminar.core.Mutation;
import com.nayem.laminar.core.VersionedMutation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MutationMigratorTest {

    @Test
    void migratesAcrossMultipleTransformers() {
        MutationMigrator migrator = new MutationMigrator();
        migrator.registerTransformer(1, 2, mutation -> new TestVersionedMutation(2, mutation.getEntityKey()));
        migrator.registerTransformer(2, 3, mutation -> new TestVersionedMutation(3, mutation.getEntityKey()));

        Mutation<?> migrated = migrator.migrate(new TestVersionedMutation(1, "user-1"), 3);
        assertInstanceOf(TestVersionedMutation.class, migrated);
        assertEquals(3, ((TestVersionedMutation) migrated).getVersion());
    }

    @Test
    void failsWhenNonVersionedMutationNeedsUpgradeButTransformerIsMissing() {
        MutationMigrator migrator = new MutationMigrator();

        assertThrows(MutationMigrator.MigrationException.class,
            () -> migrator.migrate(new TestMutation("user-1"), 2));
    }

    @Test
    void rejectsDowngradeRequests() {
        MutationMigrator migrator = new MutationMigrator();
        assertThrows(MutationMigrator.MigrationException.class,
            () -> migrator.migrate(new TestVersionedMutation(3, "user-1"), 2));
    }

    private static class TestMutation implements Mutation<Object> {
        private final String entityKey;

        private TestMutation(String entityKey) {
            this.entityKey = entityKey;
        }

        @Override
        public String getEntityKey() {
            return entityKey;
        }

        @Override
        public Mutation<Object> coalesce(Mutation<Object> other) {
            return this;
        }

        @Override
        public void apply(Object entity) {
        }
    }

    private static final class TestVersionedMutation extends TestMutation implements VersionedMutation {
        private final int version;

        private TestVersionedMutation(int version, String entityKey) {
            super(entityKey);
            this.version = version;
        }

        @Override
        public int getVersion() {
            return version;
        }
    }
}
