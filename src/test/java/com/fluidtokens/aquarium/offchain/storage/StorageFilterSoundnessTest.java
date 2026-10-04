package com.fluidtokens.aquarium.offchain.storage;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.UtxoCache;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.repository.TxInputRepository;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.repository.UtxoRepository;
import com.fluidtokens.aquarium.offchain.config.AppConfig;
import com.fluidtokens.aquarium.offchain.service.ContractRegistry;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;
import com.fluidtokens.aquarium.offchain.service.ParametersContractService;
import com.fluidtokens.aquarium.offchain.service.StakerContractService;
import com.fluidtokens.aquarium.offchain.service.TankContractService;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * ⛔ FAB-139 (FAB-135 T4): the index's write-time filter is EXACTLY the set it must be, and every read of
 * the index stays inside it.
 *
 * <h2>Why this is the epic's load-bearing pin</h2>
 * {@link TankUtxoStorage#saveUnspent} keeps only outputs whose payment credential is in
 * {@link TankUtxoStorage#indexedPaymentCredentials()}, a set built ONCE at construction
 * ({@code officina:yaci-store-index-scoping} §2). A row outside it is discarded at write time and leaves no
 * trace, so an index read for a credential outside the set answers "empty" — indistinguishable from
 * "nothing there" (§4/§4b). A narrowed set is worse than blind: {@code saveSpent} is not filtered, so an
 * object that moves while its credential is missing is DELETED from the index while still live on chain
 * (§2a). Three things therefore fail here:
 * <ol>
 *   <li><b>a.</b> the set, built from the SHIPPED base-document mainnet values, is not exactly
 *       {wallet, parameters, staker, tank} ∪ the Lending v4 registry's credentials ∪ {the Minswap V2 pool};</li>
 *   <li><b>b.</b> a {@code src/main} class mentions {@code UtxoRepository} without being in the enumerated
 *       reader inventory, or calls a repository method other than the four inventoried reads (the stake-scoped
 *       reads bypass the payment-credential filter outright);</li>
 *   <li><b>c.</b> a credential read's argument, resolved under the shipped configuration, is outside the set
 *       — or is an expression this test does not know, which fails naming its file and line. The one address
 *       read refuses an unwatched address before it reaches the repository.</li>
 * </ol>
 * Source scans blank comments and literals first (the {@code BlockfrostBuildWiringGuardTest} pattern), and
 * each must find the sites it knows exist, so an empty or broken scan cannot pass.
 */
class StorageFilterSoundnessTest {

    private static final Path MAIN = Path.of("src/main/java");

    private static final String UTXO_REPOSITORY_FQN =
            "com.bloxbean.cardano.yaci.store.utxo.storage.impl.repository.UtxoRepository";

    /**
     * Every {@code src/main} class that mentions {@code UtxoRepository}, as measured by grep at FAB-135 T4
     * dispatch (c6e610a). {@code YaciConfig} hands the repository to the {@code IndexFirstUtxoSupplier} and
     * {@code MinswapPoolResolver} beans and reads nothing itself.
     */
    private static final Set<String> READER_INVENTORY = Set.of(
            "service/AppUtxoService.java",
            "service/StakerService.java",
            "service/ScheduledTransactionService.java",
            "service/ParametersService.java",
            "service/loans/LenderBondService.java",
            "service/loans/CompoundCandidateScanner.java",
            "service/loans/LoanService.java",
            "service/loans/LiquidationUtxoResolver.java",
            "storage/IndexFirstUtxoSupplier.java",
            "storage/TankUtxoStorage.java",
            "service/loans/MinswapPoolResolver.java",
            "config/YaciConfig.java");

    private static final String CREDENTIAL_READ = "findUnspentByOwnerPaymentCredential";

    private static final String ADDRESS_READ = "findUnspentByOwnerAddr";

    /**
     * The repository methods {@code src/main} may call: the credential read (checked by part c), the address
     * read (behind {@code requireWatched}), and two OUT-REF reads. {@code findById} / {@code findAllById} name
     * one output by its transaction hash and index: they cannot widen a scan to another credential, and an
     * out-ref the filter discarded answers absent, which every caller treats as "not indexed".
     */
    private static final Set<String> ALLOWED_REPOSITORY_METHODS =
            Set.of(CREDENTIAL_READ, ADDRESS_READ, "findById", "findAllById");

    /** Stake-scoped reads: they bypass the payment-credential filter entirely. */
    private static final List<String> STAKE_SCOPED_READS =
            List.of("findUnspentByOwnerStakeAddr", "findUnspentByOwnerStakeCredential");

    /**
     * The direct {@code findUnspentByOwnerPaymentCredential(} sites at dispatch: tank, parameters (×2), staker,
     * wallet, loans, lender bonds, asset manager, Minswap pool, and the resolver's {@code unspentAt}.
     */
    private static final int KNOWN_CREDENTIAL_READ_SITES = 10;

    // ---- the shipped configuration ----------------------------------------------------------------

    private static StandardEnvironment shipped;
    private static ContractRegistry aquarium;
    private static LoansContractRegistry registry;
    private static Account account;
    private static String walletPkh;
    private static String minswapPool;

    @BeforeAll
    static void shippedConfiguration() throws Exception {
        List<PropertySource<?>> docs = new YamlPropertySourceLoader()
                .load("application.yaml", new ClassPathResource("application.yaml"));
        shipped = new StandardEnvironment();
        // Hermetic: an operator variable in the developer's shell must not decide this test.
        shipped.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        shipped.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        shipped.getPropertySources().addLast(docs.get(0));

        var cfg = mock(AppConfig.AquariumConfiguration.class);
        when(cfg.getGenesisTxHash()).thenReturn(required("aquarium.genesis.tx-hash"));
        when(cfg.getGenesisOutputIndex()).thenReturn(Integer.valueOf(required("aquarium.genesis.output-index")));
        when(cfg.getStakingTokenPolicy()).thenReturn(required("aquarium.staking.token.policy"));
        when(cfg.getStakingTokenName()).thenReturn(required("aquarium.staking.token.name"));
        aquarium = new ContractRegistry(cfg);

        registry = new LoansContractRegistry(
                required("loans.config.policy-id"),
                required("loans.lm-config.policy-id"),
                required("loans.config.asset-name"),
                required("loans.smart-tokens-spend-script-hash"),
                required("loans.minswap.pool-policy-id"),
                required("loans.minswap.pool-spend-script-hash"),
                required("loans.minswap.order-spend-script-hash"));
        assertTrue(registry.isConfigured(), "fixture: the shipped mainnet coordinates must configure the registry");

        account = new Account(Networks.mainnet());
        walletPkh = account.getBaseAddress().getPaymentCredentialHash().map(HexUtil::encodeHexString).get();
        minswapPool = required("loans.minswap.pool-spend-script-hash").toLowerCase(Locale.ROOT);
    }

    private static String required(String key) {
        String value = shipped.getProperty(key);
        assertTrue(value != null && !value.isBlank(), "the base document ships no value for " + key);
        return value;
    }

    private static TankUtxoStorage storage(String minswapPoolSpendScriptHash,
                                           ObjectProvider<LoansContractRegistry> loans) {
        return new TankUtxoStorage(mock(UtxoRepository.class), mock(TxInputRepository.class), mock(DSLContext.class),
                new UtxoCache(), null, account,
                new ParametersContractService(aquarium), new StakerContractService(aquarium),
                new TankContractService(aquarium), loans, minswapPoolSpendScriptHash);
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<LoansContractRegistry> providing(LoansContractRegistry loans) {
        ObjectProvider<LoansContractRegistry> provider = mock(ObjectProvider.class);
        doAnswer(inv -> {
            ((Consumer<LoansContractRegistry>) inv.getArgument(0)).accept(loans);
            return null;
        }).when(provider).ifAvailable(any());
        return provider;
    }

    private static TankUtxoStorage shippedStorage() {
        return storage(required("loans.minswap.pool-spend-script-hash"), providing(registry));
    }

    private static Set<String> aquariumSet() {
        return new LinkedHashSet<>(List.of(walletPkh, aquarium.getParametersScriptHashHex(),
                aquarium.getStakerScriptHashHex(), aquarium.getTankScriptHashHex()));
    }

    /** {wallet, parameters, staker, tank} ∪ registry ∪ {Minswap pool}, computed independently of the class. */
    private static Set<String> expectedSet() {
        Set<String> expected = aquariumSet();
        expected.addAll(registry.indexedPaymentCredentials());
        expected.add(minswapPool);
        return expected;
    }

    // ---- a. the exact set -------------------------------------------------------------------------

    /** The size measured at FAB-135 T4: 4 Aquarium + 9 Lending v4 registry + 1 Minswap pool. */
    private static final int SHIPPED_SET_SIZE = 14;

    @Test
    void theShippedFilterIsExactlyTheWatchedSet() {
        Set<String> actual = shippedStorage().indexedPaymentCredentials();

        assertEquals(new TreeSet<>(expectedSet()), new TreeSet<>(actual),
                "the write-time filter is not exactly {wallet, parameters, staker, tank} ∪ registry ∪ {Minswap pool}: "
                        + "a missing credential's rows are discarded at write time, an extra one is never read");
        assertEquals(SHIPPED_SET_SIZE, actual.size(), "the shipped filter's size changed: " + actual);
        assertTrue(actual.contains("ea07b733d932129c378af627436e7cbc2ef0bf96e0036bb51b3bde6b"),
                "the Minswap V2 mainnet pool credential is not watched: " + actual);
        assertEquals(9, registry.indexedPaymentCredentials().size(),
                "the registry's credential count changed: " + registry.indexedPaymentCredentials());
    }

    @Test
    void aBlankMinswapHashDropsExactlyThePoolCredential() {
        Set<String> expected = expectedSet();
        expected.remove(minswapPool);

        assertEquals(new TreeSet<>(expected),
                new TreeSet<>(storage("", providing(registry)).indexedPaymentCredentials()));
    }

    @Test
    void anUnconfiguredRegistryDropsExactlyTheRegistrysCredentials() {
        var unconfigured = new LoansContractRegistry("", "", required("loans.config.asset-name"), "");
        assertFalse(unconfigured.isConfigured(), "fixture: this registry must be the UNCONFIGURED one");
        Set<String> expected = aquariumSet();
        expected.add(minswapPool);

        assertEquals(new TreeSet<>(expected), new TreeSet<>(storage(required("loans.minswap.pool-spend-script-hash"),
                providing(unconfigured)).indexedPaymentCredentials()));
    }

    // ---- b. the reader inventory --------------------------------------------------------------------

    @Test
    void everyClassThatMentionsUtxoRepositoryIsInTheInventory() {
        Set<String> mentioning = new TreeSet<>();
        sources().forEach((file, code) -> {
            if (!scanSource(file, code, UTXO_REPOSITORY_MENTION).isEmpty()) {
                mentioning.add(file);
            }
        });

        Set<String> unknown = new TreeSet<>(mentioning);
        unknown.removeAll(READER_INVENTORY);
        assertTrue(unknown.isEmpty(), "src/main classes read the index without being in the reader inventory — "
                + "add each to this test only after proving its reads stay inside the watched set: " + unknown);
        assertEquals(new TreeSet<>(READER_INVENTORY), mentioning,
                "the scan did not find exactly the known inventory: either a reader went away (update the inventory) "
                        + "or the scan is not reading the source it guards");
    }

    /** Each spelling of a mention, on inputs whose answer is known. */
    @Test
    void theMentionPatternMatchesEverySpelling() {
        for (String code : List.of(
                "private final UtxoRepository r;",
                "private final " + UTXO_REPOSITORY_FQN + " r;",
                "private final com . bloxbean.cardano.yaci.store.utxo.storage.impl.repository . UtxoRepository r;",
                "Function<String, ?> f = UtxoRepository::findUnspentByOwnerAddr;",
                "x(" + UTXO_REPOSITORY_FQN + "::findById)",
                "interface Mine extends UtxoRepository {}",
                "class Mine implements\n " + UTXO_REPOSITORY_FQN + " {}")) {
            assertEquals(1, scanSource("x.java", code, UTXO_REPOSITORY_MENTION).size(), code);
        }
        assertEquals(0, scanSource("x.java", "TxInputRepository t; MyUtxoRepository m; UtxoRepositoryHelper h; "
                        + "/* UtxoRepository */ String s = \"UtxoRepository\"; // UtxoRepository::findById\n",
                UTXO_REPOSITORY_MENTION).size(),
                "other types, comments and literals are not mentions");
    }

    /**
     * ⛔ Only the four inventoried repository methods are called, every call goes through a field named
     * {@code utxoRepository} (so a renamed field cannot walk past the call scan), and no {@code src/main} file
     * calls a stake-scoped read.
     */
    @Test
    void onlyTheInventoriedRepositoryMethodsAreCalled() {
        Set<String> repositoryMethods = new TreeSet<>();
        for (Method method : UtxoRepository.class.getMethods()) {
            repositoryMethods.add(method.getName());
        }
        assertTrue(repositoryMethods.containsAll(ALLOWED_REPOSITORY_METHODS),
                "fixture: UtxoRepository no longer declares " + ALLOWED_REPOSITORY_METHODS);

        List<String> violations = new ArrayList<>();
        Map<String, String> sources = sources();
        sources.forEach((file, code) -> {
            for (Site site : scanSource(file, code, Pattern.compile("\\b(?:" + QUALIFIER + ")?UtxoRepository\\s+(\\w+)\\s*[;,)=]"))) {
                if (!site.group().equals("utxoRepository")) {
                    violations.add(site + ": a UtxoRepository named [" + site.group() + "] — the call scan reads "
                            + "`utxoRepository.`; name it so");
                }
            }
            for (Site site : scanSource(file, code, Pattern.compile("\\butxoRepository\\s*\\.\\s*(\\w+)\\s*\\("))) {
                if (!ALLOWED_REPOSITORY_METHODS.contains(site.group())) {
                    violations.add(site + ": utxoRepository." + site.group() + "( is not an inventoried read");
                }
            }
            for (Site site : scanSource(file, code, Pattern.compile("\\bUtxoRepository\\s*::\\s*(\\w+)"))) {
                violations.add(site + ": UtxoRepository::" + site.group() + " — a method reference hides its "
                        + "argument from part c");
            }
            violations.addAll(methodReferenceReads(file, code));
            for (String stake : STAKE_SCOPED_READS) {
                for (Site site : scanSource(file, code, Pattern.compile("\\b" + stake + "\\b"))) {
                    violations.add(site + ": " + stake + " is stake-scoped and bypasses the payment-credential filter");
                }
            }
        });
        assertTrue(violations.isEmpty(), String.join("\n", violations));

        long calls = sources.entrySet().stream()
                .mapToLong(e -> scanSource(e.getKey(), e.getValue(),
                        Pattern.compile("\\butxoRepository\\s*\\.\\s*(\\w+)\\s*\\(")).size())
                .sum();
        assertTrue(calls >= KNOWN_CREDENTIAL_READ_SITES + 3,
                "the call scan found " + calls + " utxoRepository calls, fewer than the known ones: it is not "
                        + "reading the source it guards");
    }

    /**
     * ⛔ FAB-135 T4 r2: an INSTANCE method reference to a repository read — {@code utxoRepository::findUnspentBy…},
     * {@code this.utxoRepository::findById}, {@code repo :: findUnspentByOwnerAddr} — is a read whose argument
     * no call scan sees: it is applied later, through a functional interface, to whatever value its caller
     * chooses. So each one is a violation, like the type form {@code UtxoRepository::m}:
     * <ul>
     *   <li>any {@code ::} reference to a {@code find…} method whose receiver is a field or variable declared
     *       {@code UtxoRepository} in that file (any name, {@code this.}-qualified or not);</li>
     *   <li>any {@code ::} reference to a {@code findUnspentByOwner…} method, whatever the receiver.</li>
     * </ul>
     */
    private static List<String> methodReferenceReads(String file, String source) {
        String code = blankCommentsAndLiterals(source);
        Set<String> receivers = new HashSet<>(Set.of("utxoRepository"));
        Matcher declared = Pattern.compile("(?<![\\w$])" + QUALIFIER + "UtxoRepository\\s+([\\w$]+)").matcher(code);
        while (declared.find()) {
            receivers.add(declared.group(1));
        }
        List<String> violations = new ArrayList<>();
        Matcher reference = Pattern.compile("(?:\\bthis\\s*\\.\\s*)?([\\w$]*)\\s*::\\s*(find\\w*)\\b").matcher(code);
        while (reference.find()) {
            if (receivers.contains(reference.group(1)) || reference.group(2).startsWith("findUnspentByOwner")) {
                int line = 1 + (int) code.substring(0, reference.start()).chars().filter(c -> c == '\n').count();
                violations.add(file + ":" + line + ": " + reference.group(1) + "::" + reference.group(2)
                        + " — a method-reference read of the index hides its argument from part c");
            }
        }
        return violations;
    }

    /** Each method-reference spelling, on inputs whose answer is known. */
    @Test
    void theMethodReferenceReadPatternMatchesEverySpelling() {
        for (String code : List.of(
                "private final UtxoRepository utxoRepository; Object f = utxoRepository::findUnspentByOwnerPaymentCredential;",
                "Object f = utxoRepository :: findById;",
                "Object f = this.utxoRepository::findAllById;",
                "Object f = this . utxoRepository\n    ::\n findUnspentByOwnerAddr;",
                "private final " + UTXO_REPOSITORY_FQN + " repo; Object f = repo :: findById;",
                "UtxoRepository r = x; Object f = r::findUnspentByOwnerPaymentCredential;",
                "Object f = someOtherName :: findUnspentByOwnerPaymentCredential;",
                "Object f = holder.repository()::findUnspentByOwnerAddr;")) {
            assertEquals(1, methodReferenceReads("x.java", code).size(), code);
        }
        // Near misses: a non-repository receiver's find method, comments and literals.
        assertEquals(0, methodReferenceReads("x.java", "Object f = list::findFirst; Object g = map::find; "
                + "/* utxoRepository::findById */ String s = \"repo :: findUnspentByOwnerAddr\"; "
                + "// utxoRepository::findUnspentByOwnerPaymentCredential\n").size());
    }

    // ---- b. raw access to the index tables ----------------------------------------------------------

    /**
     * ⛔ FAB-135 T4 r2: {@code UtxoRepository} is not the only way into the index. Yaci's
     * {@code UtxoStorageReader}, a jOOQ {@code DSLContext}, or SQL naming {@code address_utxo} / {@code tx_input}
     * read the same tables and would escape every check above. None may appear in {@code src/main} outside
     * {@code TankUtxoStorage} (which hands its {@code DSLContext} to Yaci's own writer), and
     * {@code UtxoStorageReader} nowhere at all. Scanned with comments blanked but string literals KEPT, since
     * raw SQL lives in literals.
     */
    private static final Pattern RAW_INDEX_ACCESS =
            Pattern.compile("(?i)(?<![\\w$])(UtxoStorageReader|DSLContext|address_utxo|tx_input)(?![\\w$])");

    private static final String TANK_UTXO_STORAGE = "storage/TankUtxoStorage.java";

    private static List<String> rawIndexAccess(String file, String source) {
        String code = blankComments(source);
        Matcher matcher = RAW_INDEX_ACCESS.matcher(code);
        List<String> violations = new ArrayList<>();
        while (matcher.find()) {
            boolean storageReader = matcher.group(1).equalsIgnoreCase("UtxoStorageReader");
            if (storageReader || !file.equals(TANK_UTXO_STORAGE)) {
                int line = 1 + (int) code.substring(0, matcher.start()).chars().filter(c -> c == '\n').count();
                violations.add(file + ":" + line + ": " + matcher.group(1) + " — raw access to the index tables "
                        + "outside TankUtxoStorage escapes the reader inventory and part c");
            }
        }
        return violations;
    }

    @Test
    void noClassReachesTheIndexTablesOutsideTheRepository() {
        List<String> violations = new ArrayList<>();
        rawSources().forEach((file, source) -> violations.addAll(rawIndexAccess(file, source)));
        assertTrue(violations.isEmpty(), String.join("\n", violations));

        // The scan must see what is there: TankUtxoStorage's own DSLContext (allowed, so counted directly).
        String tank = rawSources().get(TANK_UTXO_STORAGE);
        assertTrue(tank != null && RAW_INDEX_ACCESS.matcher(blankComments(tank)).find(),
                "the raw-access scan did not see TankUtxoStorage's DSLContext: it is not reading the source it guards");
    }

    /** Each raw-access spelling, on inputs whose answer is known. */
    @Test
    void theRawAccessPatternMatchesEverySpelling() {
        for (String code : List.of(
                "private final DSLContext dsl;",
                "private final org.jooq.DSLContext dsl;",
                "dsl.fetch(\"select * from address_utxo where owner_payment_credential = ?\", x);",
                "dsl.fetch(\"\"\"\n  select 1 from TX_INPUT\n\"\"\");",
                "dsl.selectFrom(ADDRESS_UTXO);",
                "private final UtxoStorageReader reader;",
                "private final com.bloxbean.cardano.yaci.store.utxo.storage.UtxoStorageReader reader;")) {
            assertEquals(1, rawIndexAccess("service/X.java", code).size(), code);
        }
        assertEquals(1, rawIndexAccess(TANK_UTXO_STORAGE, "UtxoStorageReader r;").size(),
                "UtxoStorageReader is refused even in TankUtxoStorage");
        assertEquals(0, rawIndexAccess(TANK_UTXO_STORAGE, "DSLContext dsl;").size(),
                "TankUtxoStorage's own DSLContext is the one allowed site");
        assertEquals(0, rawIndexAccess("service/X.java", "/* DSLContext address_utxo */ // tx_input\n"
                + "MyDSLContextHolder h; address_utxo_count n; tx_inputs m;").size(),
                "comments and other identifiers are not raw access");
    }

    // ---- c. every read stays inside the set ----------------------------------------------------------

    /**
     * Where a credential read's argument is a PARAMETER, the methods whose callers supply it: the argument
     * expression at each of their call sites is resolved in turn. {@code scope} null means every src/main file.
     */
    private record Forward(String method, String scope) {
    }

    private static final Map<String, List<Forward>> FORWARDS = Map.of(
            // unspentAt(String paymentCredential) is fed by resolveAt (public) and holderOf (private), both of
            // whose parameter is also named paymentCredential, and by resolveConfigHolder's policyId.
            "service/loans/LiquidationUtxoResolver.java:paymentCredential", List.of(
                    new Forward("unspentAt", "service/loans/LiquidationUtxoResolver.java"),
                    new Forward("resolveAt", null),
                    new Forward("holderOf", "service/loans/LiquidationUtxoResolver.java")),
            "service/loans/LiquidationUtxoResolver.java:policyId", List.of(
                    new Forward("resolveConfigHolder", "service/loans/LiquidationUtxoResolver.java")));

    /**
     * Arguments that are local names, with the value they hold under the shipped configuration and where
     * that is established. Each is proved by the declaring source line named alongside it.
     */
    private static Map<String, String> localTerminals() {
        return Map.of(
                // AppUtxoService: `String walletPkh = account.getBaseAddress().getPaymentCredentialHash()...`
                "service/AppUtxoService.java:walletPkh", walletPkh,
                // MinswapPoolResolver(utxoRepository, poolSpendScriptHash, ...): YaciConfig passes
                // loans.minswap.pool-spend-script-hash, lowercased.
                "service/loans/MinswapPoolResolver.java:poolSpendScriptHash", minswapPool);
    }

    private static final Map<String, String> LOCAL_TERMINAL_PROOFS = Map.of(
            "service/AppUtxoService.java",
            "String walletPkh = account.getBaseAddress().getPaymentCredentialHash()",
            "service/loans/MinswapPoolResolver.java",
            "this.poolSpendScriptHash = poolSpendScriptHash;",
            "config/YaciConfig.java",
            "String spendHash = loansConfiguration.getMinswapPoolSpendScriptHash();");

    /** A getter on a receiver whose shipped instance this test holds: {@code registry.getX()}, {@code tankContractService.getScriptHashHex()}. */
    private static final Pattern GETTER = Pattern.compile("(\\w+)\\.(get\\w+)\\(\\)");

    private static Object receiver(String name) {
        return switch (name) {
            case "registry" -> registry;
            case "parametersContractService" -> new ParametersContractService(aquarium);
            case "stakerContractService" -> new StakerContractService(aquarium);
            case "tankContractService" -> new TankContractService(aquarium);
            default -> null;
        };
    }

    private record Read(Site site, String expression, String value) {
    }

    @Test
    void everyCredentialReadQueriesACredentialInsideTheSet() {
        Set<String> watched = shippedStorage().indexedPaymentCredentials();
        Map<String, String> sources = sources();
        List<String> problems = new ArrayList<>();
        List<Read> reads = new ArrayList<>();

        List<Site> direct = new ArrayList<>();
        sources.forEach((file, code) -> direct.addAll(callSites(file, code, CREDENTIAL_READ)));
        assertTrue(direct.size() >= KNOWN_CREDENTIAL_READ_SITES, "the scan found " + direct.size() + " `"
                + CREDENTIAL_READ + "(` sites, fewer than the " + KNOWN_CREDENTIAL_READ_SITES + " known: " + direct);
        for (Site site : direct) {
            resolve(site, sources, new HashSet<>(), reads, problems);
        }

        for (Read read : reads) {
            if (!watched.contains(read.value())) {
                problems.add(read.site() + ": " + CREDENTIAL_READ + " reaches [" + read.expression() + "] = "
                        + read.value() + ", which the index does NOT watch — its rows were discarded at write "
                        + "time, so this read answers \"empty\" forever");
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));

        // The resolver's six forwarded reads and the nine direct ones were all seen.
        Set<String> expressions = new TreeSet<>();
        reads.forEach(read -> expressions.add(read.expression()));
        assertEquals(new TreeSet<>(List.of(
                "parametersContractService.getScriptHashHex()",
                "stakerContractService.getScriptHashHex()",
                "tankContractService.getScriptHashHex()",
                "walletPkh",
                "poolSpendScriptHash",
                "registry.getLoanSpendScriptHash()",
                "registry.getLenderManagerSpendScriptHash()",
                "registry.getAssetManagerSpendScriptHash()",
                "registry.getPoolSpendScriptHash()",
                "registry.getPoolManagerSpendScriptHash()",
                "registry.getConfigPolicyId()",
                "registry.getLmConfigPolicyId()")), expressions,
                "the credential reads resolved to a different set of argument expressions than the ones known at "
                        + "dispatch: " + reads);
    }

    /** The local-name terminals are what the test says they are: the declaring lines are in the source. */
    @Test
    void theLocalTerminalsAreDeclaredAsStated() {
        Map<String, String> sources = sources();
        LOCAL_TERMINAL_PROOFS.forEach((file, line) -> assertTrue(
                sources.getOrDefault(file, "").replaceAll("\\s+", " ").contains(line),
                file + " no longer contains `" + line + "`: the value this test assigns to its argument is unproved"));
    }

    private static void resolve(Site site, Map<String, String> sources, Set<String> visited,
                                List<Read> reads, List<String> problems) {
        String expression = site.group();
        String key = site.file() + ":" + expression;
        String local = localTerminals().get(key);
        if (local != null) {
            reads.add(new Read(site, expression, local));
            return;
        }
        Matcher getter = GETTER.matcher(expression);
        if (getter.matches() && receiver(getter.group(1)) != null) {
            Object target = receiver(getter.group(1));
            try {
                Object value = target.getClass().getMethod(getter.group(2)).invoke(target);
                reads.add(new Read(site, expression, String.valueOf(value)));
            } catch (ReflectiveOperationException e) {
                problems.add(site + ": [" + expression + "] cannot be evaluated on the shipped "
                        + getter.group(1) + ": " + e);
            }
            return;
        }
        List<Forward> forwards = FORWARDS.get(key);
        if (forwards == null) {
            problems.add(site + ": " + CREDENTIAL_READ + " argument [" + expression + "] is an expression this test "
                    + "does not know — map it to its value under the shipped configuration and prove it is watched");
            return;
        }
        if (!visited.add(key)) {
            return;
        }
        for (Forward forward : forwards) {
            sources.forEach((file, code) -> {
                if (forward.scope() == null || forward.scope().equals(file)) {
                    for (Site caller : callSites(file, code, forward.method())) {
                        resolve(caller, sources, visited, reads, problems);
                    }
                }
            });
        }
    }

    // ---- c. the address read --------------------------------------------------------------------------

    @Test
    void theAddressReadOccursOnlyInIndexFirstUtxoSupplierGetPage() {
        List<Site> sites = new ArrayList<>();
        sources().forEach((file, code) -> sites.addAll(callSites(file, code, ADDRESS_READ)));
        assertEquals(1, sites.size(), "expected exactly one `" + ADDRESS_READ + "(` in src/main: " + sites);
        assertEquals("storage/IndexFirstUtxoSupplier.java", sites.getFirst().file(), sites.toString());
    }

    @Test
    void anAddressOutsideTheSetIsRefusedBeforeItReachesTheRepository() {
        Set<String> watched = shippedStorage().indexedPaymentCredentials();
        String unwatchedHash = registry.getLenderManagerWithdrawScriptHash();
        assertFalse(watched.contains(unwatchedHash), "fixture: the withdraw hash must be outside the set");
        String unwatched = AddressProvider.getEntAddress(
                Credential.fromScript(HexUtil.decodeHexString(unwatchedHash)), Networks.mainnet()).toBech32();

        UtxoRepository repository = mock(UtxoRepository.class);
        var supplier = new IndexFirstUtxoSupplier(repository, () -> watched, mock(UtxoSupplier.class), Set::of);

        assertThrows(IllegalStateException.class, () -> supplier.getPage(unwatched, 100, 0, null));
        verifyNoInteractions(repository);
    }

    /** Positive control: a watched address does reach the repository, so the refusal above is not universal. */
    @Test
    void aWatchedAddressReachesTheRepository() {
        Set<String> watched = shippedStorage().indexedPaymentCredentials();
        String wallet = account.baseAddress();
        UtxoRepository repository = mock(UtxoRepository.class);
        var supplier = new IndexFirstUtxoSupplier(repository, () -> watched, mock(UtxoSupplier.class), Set::of);

        supplier.getPage(wallet, 100, 0, null);

        verify(repository).findUnspentByOwnerAddr(any(), any());
    }

    // ---- scanning -----------------------------------------------------------------------------------

    private record Site(String file, int line, String group) {
        @Override
        public String toString() {
            return file + ":" + line;
        }
    }

    private static final String QUALIFIER = "(?:[A-Za-z_$][\\w$]*\\s*\\.\\s*)*";

    /**
     * A mention of the type: the simple name, the fully-qualified name (any spacing around its dots), a
     * method reference {@code UtxoRepository::m}, and {@code extends}/{@code implements} all end in the same
     * token, which is what this matches once comments and literals are blanked.
     */
    private static final Pattern UTXO_REPOSITORY_MENTION = Pattern.compile("(?<![\\w$])" + QUALIFIER + "UtxoRepository\\b");

    /** Every {@code .java} under src/main, by path relative to it, comments and literals blanked. */
    private static Map<String, String> sources() {
        assertTrue(Files.isDirectory(MAIN), "not run from the project root: " + MAIN.toAbsolutePath());
        Map<String, String> sources = new TreeMap<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                String relative = MAIN.relativize(file).toString().replace('\\', '/')
                        .replaceFirst("^com/fluidtokens/aquarium/offchain/", "");
                sources.put(relative, blankCommentsAndLiterals(Files.readString(file, StandardCharsets.UTF_8)));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return sources;
    }

    /** Matches of {@code pattern} in already-blanked code (or raw code, blanked here), with group 1 if any. */
    private static List<Site> scanSource(String file, String source, Pattern pattern) {
        String code = blankCommentsAndLiterals(source);
        Matcher matcher = pattern.matcher(code);
        List<Site> sites = new ArrayList<>();
        while (matcher.find()) {
            int line = 1 + (int) code.substring(0, matcher.start()).chars().filter(c -> c == '\n').count();
            sites.add(new Site(file, line, matcher.groupCount() >= 1 ? matcher.group(1) : matcher.group()));
        }
        return sites;
    }

    /**
     * Each CALL of {@code method} in {@code code}, with its first top-level argument (whitespace collapsed).
     * A declaration ({@code Optional<Utxo> holderOf(String paymentCredential, ...)}) is not a call: its first
     * "argument" is a typed parameter, and it is skipped.
     */
    private static List<Site> callSites(String file, String code, String method) {
        Matcher matcher = Pattern.compile("(?<![\\w$])" + Pattern.quote(method) + "\\s*\\(").matcher(code);
        List<Site> sites = new ArrayList<>();
        while (matcher.find()) {
            String argument = firstArgument(code, matcher.end()).replaceAll("\\s+", " ").strip()
                    .replaceAll("\\s*([.()])\\s*", "$1");
            if (argument.matches("(?:final )?[\\w.$<>\\[\\], ]+ [\\w$]+")) {
                continue;
            }
            int line = 1 + (int) code.substring(0, matcher.start()).chars().filter(c -> c == '\n').count();
            sites.add(new Site(file, line, argument));
        }
        return sites;
    }

    private static String firstArgument(String code, int start) {
        int depth = 0;
        for (int i = start; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                if (depth == 0) {
                    return code.substring(start, i);
                }
                depth--;
            } else if (c == ',' && depth == 0) {
                return code.substring(start, i);
            }
        }
        throw new IllegalStateException("unbalanced parentheses after offset " + start);
    }

    /** Every {@code .java} under src/main, by path relative to it, untouched. */
    private static Map<String, String> rawSources() {
        assertTrue(Files.isDirectory(MAIN), "not run from the project root: " + MAIN.toAbsolutePath());
        Map<String, String> sources = new TreeMap<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                String relative = MAIN.relativize(file).toString().replace('\\', '/')
                        .replaceFirst("^com/fluidtokens/aquarium/offchain/", "");
                sources.put(relative, Files.readString(file, StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return sources;
    }

    /** Comments, string, char and text-block contents become spaces; newlines survive so lines still count. */
    private static String blankCommentsAndLiterals(String source) {
        return blank(source, false);
    }

    /** Comments become spaces; string, char and text-block literals are KEPT (raw SQL lives in them). */
    private static String blankComments(String source) {
        return blank(source, true);
    }

    private static String blank(String source, boolean keepLiterals) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        int n = source.length();
        while (i < n) {
            char c = source.charAt(i);
            if (c == '/' && i + 1 < n && source.charAt(i + 1) == '/') {
                while (i < n && source.charAt(i) != '\n') {
                    out.append(' ');
                    i++;
                }
            } else if (c == '/' && i + 1 < n && source.charAt(i + 1) == '*') {
                int end = source.indexOf("*/", i + 2);
                end = end < 0 ? n : end + 2;
                blank(source, i, end, out);
                i = end;
            } else if (source.startsWith("\"\"\"", i)) {
                int end = source.indexOf("\"\"\"", i + 3);
                end = end < 0 ? n : end + 3;
                if (keepLiterals) {
                    out.append(source, i, end);
                } else {
                    blank(source, i, end, out);
                }
                i = end;
            } else if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < n && source.charAt(j) != c && source.charAt(j) != '\n') {
                    j += source.charAt(j) == '\\' ? 2 : 1;
                }
                int end = Math.min(n, j + 1);
                if (keepLiterals) {
                    out.append(source, i, end);
                } else {
                    blank(source, i, end, out);
                }
                i = end;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static void blank(String source, int from, int to, StringBuilder out) {
        for (int k = from; k < to; k++) {
            out.append(source.charAt(k) == '\n' ? '\n' : ' ');
        }
    }
}
