package com.fluidtokens.aquarium.offchain.config;

/**
 * The build identity the oracle stream handshake sends as {@code X-FT-Node-Version} (FAB-179, FAB-213): the short
 * commit and the dirty flag of the build this node runs.
 *
 * <p>⚠ <b>The values are raw.</b> Nothing here checks them; {@code OracleStreamLauncher} sanitises whatever this
 * bean returns at the point it hands the value to the stream client, so a replacement cannot put a placeholder, a
 * space or a CR into the header.
 *
 * <p>The default is {@link BuildInfoAutoConfiguration}'s, read from the {@code aquarium.build.commitShort} and
 * {@code aquarium.build.dirty} properties — which themselves default to the jar's own {@code build-info.properties}
 * ({@code AcquariumOffchainApp.buildProvenance()}), so the header is what it always was.
 *
 * <p>⛔ <b>{@code NodeVersionCheck} deliberately does not read this.</b> It compares the jar's own commit against
 * the published branch, and it reads the jar directly so that no environment variable and no bean can change which
 * commit it compares.
 *
 * <p>A context replaces the default by declaring its own {@code BuildInfo} bean; the auto-configuration then
 * backs off ({@code @ConditionalOnMissingBean}), and with several, a {@code @Primary} one is injected.
 *
 * @param commitShort the short commit, raw — {@code unknown}, blank or {@code null} means no header
 * @param dirty       the dirty flag, raw — {@code true} (any case) appends {@code -dirty}
 */
public record BuildInfo(String commitShort, String dirty) {
}
