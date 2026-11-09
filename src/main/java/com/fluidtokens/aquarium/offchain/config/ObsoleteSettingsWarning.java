package com.fluidtokens.aquarium.offchain.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Says, once at boot, that a setting which no longer exists is still being passed (FAB-183).
 *
 * <p>Since FAB-183 the liquidation and compound stages run on committed blocks and oracle price changes, so their
 * delay settings ({@link #OBSOLETE_NAMES}) are read by nothing and no longer shipped. An operator whose
 * {@code docker/.env} still sets one would otherwise get silence, which reads exactly like the setting working
 * (docs/upgrading.md §7).
 *
 * <p>Each name is checked with {@link Environment#containsProperty}, so a property name also matches its relaxed
 * environment-variable form ({@code LOANS_LIQUIDATION_DELAY_SECONDS}: dots and dashes both become underscores),
 * and the {@code AQUARIUM_*} names the shipped YAML used to read are checked as themselves. Only
 * names are logged, never values. At most one WARN per name; it never throws, and nothing is said when none is
 * set.
 */
@Slf4j
@Component
public class ObsoleteSettingsWarning {

    /** Checked in this order; one WARN for each that is set. */
    static final List<String> OBSOLETE_NAMES = List.of(
            "loans.liquidation.delay-seconds",
            "AQUARIUM_LIQUIDATION_DELAY_SECONDS",
            "loans.compound.delay-seconds",
            "AQUARIUM_COMPOUND_DELAY_SECONDS");

    private final Environment environment;

    private final AtomicBoolean checked = new AtomicBoolean();

    public ObsoleteSettingsWarning(Environment environment) {
        this.environment = Objects.requireNonNull(environment, "environment");
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warnAboutObsoleteSettings() {
        if (!checked.compareAndSet(false, true)) {
            return;
        }
        for (String name : OBSOLETE_NAMES) {
            try {
                if (environment.containsProperty(name)) {
                    log.warn("{} is set but no longer exists: liquidation, compound and wallet housekeeping run on "
                            + "committed blocks and oracle price changes, never on a timer (FAB-183) — remove it; "
                            + "see docs/upgrading.md §7", name);
                }
            } catch (RuntimeException e) {
                log.debug("could not check obsolete setting {}: {}", name, e.toString());
            }
        }
    }
}
