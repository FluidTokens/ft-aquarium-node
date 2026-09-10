package com.fluidtokens.aquarium.offchain;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

@SpringBootApplication(scanBasePackages = "com.fluidtokens.aquarium.offchain")
public class AcquariumOffchainApp {

    /**
     * ⛔ The value every provenance field falls back to. It is deliberately NOT the empty string and
     * deliberately NOT {@code false}: an operator reading {@code docker logs} or {@code /actuator/info}
     * must be able to tell "this build does not know" apart from "this build was clean". They are
     * different claims and only one of them is safe to trust.
     */
    public static final String UNKNOWN = "unknown";

    /** Written by Gradle's {@code bootBuildInfo}; absent from a jar built without it. */
    private static final String BUILD_INFO = "/META-INF/build-info.properties";

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(AcquariumOffchainApp.class);
        // Default properties, so an operator's -D or environment variable still wins. This is also
        // what makes banner.txt's ${aquarium.build.*} placeholders resolvable: SpringApplication puts
        // default properties into the Environment before the banner is printed.
        application.setDefaultProperties(new LinkedHashMap<>(buildProvenance()));
        application.run(args);
    }

    /**
     * What this process was built from, read off the classpath and offered to the Environment under
     * {@code aquarium.build.*}.
     *
     * <p>⚠ <b>A missing or unreadable {@code build-info.properties} is not a startup condition.</b>
     * The node runs on operators' machines and is also built in ways that never invoke
     * {@code bootBuildInfo}; refusing to boot because the process cannot describe itself would turn a
     * cosmetic gap into an outage. Every field then reads {@link #UNKNOWN}, which is a true statement.
     */
    public static Map<String, Object> buildProvenance() {
        Properties properties = new Properties();
        try (InputStream stream = AcquariumOffchainApp.class.getResourceAsStream(BUILD_INFO)) {
            if (stream != null) {
                properties.load(stream);
            }
        } catch (IOException | RuntimeException ignored) {
            // Deliberately swallowed -- see the javadoc above. Nothing here is worth a failed boot.
        }

        Map<String, Object> provenance = new LinkedHashMap<>();
        provenance.put("aquarium.build.commit", value(properties, "build.commit"));
        provenance.put("aquarium.build.commitShort", value(properties, "build.commitShort"));
        provenance.put("aquarium.build.dirty", value(properties, "build.dirty"));
        provenance.put("aquarium.build.time", value(properties, "build.time"));
        return provenance;
    }

    /**
     * ⚠ Blank counts as absent. A property present but empty would render the banner as a line with a
     * hole in it and would report an empty {@code dirty} -- both of which read as "fine" rather than
     * as "unknown".
     */
    private static String value(Properties properties, String key) {
        String raw = properties.getProperty(key);
        return raw == null || raw.isBlank() ? UNKNOWN : raw.trim();
    }

}
