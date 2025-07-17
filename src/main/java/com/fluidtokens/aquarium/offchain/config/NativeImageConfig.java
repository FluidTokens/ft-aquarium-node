package com.fluidtokens.aquarium.offchain.config;

import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportRuntimeHints;

@Configuration
@ImportRuntimeHints(NativeImageConfig.JsonTypeRuntimeHints.class)
public class NativeImageConfig {

    static class JsonTypeRuntimeHints implements RuntimeHintsRegistrar {
        @Override
        public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
            // Register JsonType classes for reflection
            hints.reflection()
                .registerType(io.hypersistence.utils.hibernate.type.json.JsonType.class, 
                    MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                    MemberCategory.INVOKE_DECLARED_METHODS,
                    MemberCategory.DECLARED_FIELDS)
                .registerType(io.hypersistence.utils.hibernate.type.json.JsonBinaryType.class,
                    MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                    MemberCategory.INVOKE_DECLARED_METHODS,
                    MemberCategory.DECLARED_FIELDS)
                .registerType(io.hypersistence.utils.hibernate.type.json.JsonStringType.class,
                    MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                    MemberCategory.INVOKE_DECLARED_METHODS,
                    MemberCategory.DECLARED_FIELDS);
            
            // Register Flyway classes for reflection
            hints.reflection()
                .registerType(org.flywaydb.core.internal.publishing.PublishingConfigurationExtension.class,
                    MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                    MemberCategory.INVOKE_DECLARED_METHODS,
                    MemberCategory.DECLARED_FIELDS);
            
            // Register PostgreSQL SQL files from Yaci Store dependencies for Flyway
            hints.resources()
                .registerPattern("db/store/postgresql/.*");
        }
    }
}