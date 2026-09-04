package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.repository.UtxoRepository;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.loans.CollateralAsset;
import com.fluidtokens.aquarium.offchain.model.loans.Loan;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;
import com.fluidtokens.aquarium.offchain.util.UtxoUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.util.Collection;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Reads indexed Lending v4 loan UTxOs and decodes them.
 * <p>
 * Read-only. Everything comes from the local index — no Blockfrost, no chain queries — so this
 * is only as fresh as the Yaci Store cursor.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LoanService {

    private final UtxoRepository utxoRepository;

    private final LoansContractRegistry registry;

    private final LoanDatumConverter converter = new LoanDatumConverter();

    /**
     * Every loan currently unspent at the loan {@code general_spend} credential.
     * <p>
     * Indexing is by payment credential, which is deliberate: loan UTxOs carry the lender's
     * stake credential so the bech32 address differs per lender while the payment credential
     * is fixed. It is also market-independent — {@code loan.ak} is parameterised only by the
     * config NFT, so USDM, DJED and ada loans all live at the same credential and are told
     * apart by {@code datum.principalAsset}.
     * <p>
     * A UTxO that fails to decode is logged and skipped rather than failing the whole call:
     * anyone can pay junk to a script address, and one bad output must not blank the endpoint.
     */
    /**
     * <b>What one scan of the loan credential actually saw, including what it could not read.</b>
     *
     * <h2>Why the raw delta is NOT the signal (T-060)</h2>
     * {@code utxosAtCredential - loans.size()} is the obvious discriminator and it is <b>wrong</b>:
     * anyone can pay junk to a script address, so that delta mixes UTxOs carrying <em>no</em> loan
     * NFT — which are not loans and never were — with UTxOs that <b>carry exactly one loan NFT and
     * still could not be turned into a {@link Loan}</b>. Only the second kind is evidence of
     * blindness, and only it may be read as such.
     *
     * @param loans             the ones that decoded
     * @param utxosAtCredential everything unspent at the loan spend credential in the index
     * @param unreadable        <b>the number that matters.</b> UTxOs carrying loan NFTs that this
     *                          node could not read: exactly one loan NFT but no inline datum or an
     *                          undecodable datum, or more than one loan NFT so the identity is
     *                          ambiguous. <b>Each one makes some bond report {@code LOAN_NOT_FOUND}
     *                          for a loan that is alive, in the index, and at the right address.</b>
     * @param notALoan          UTxOs at the credential carrying no loan NFT at all — junk, and
     *                          entirely expected at a public script address
     */
    public record Census(List<Loan> loans, int utxosAtCredential, int unreadable, int notALoan) {
    }

    /**
     * ⚠ <b>{@code final}, deliberately.</b> It delegates to {@link #census()}, which is the single
     * seam — so a test double that overrides this instead would be silently bypassed the moment a
     * caller asks for the census. That is not hypothetical: when the seam moved for T-060, three
     * fakes overriding {@code findAll()} stopped being consulted at once and 52 tests went red.
     * <b>Final turns that into a compile error instead of a surprise.</b>
     */
    public final List<Loan> findAll() {
        return census().loans();
    }

    /**
     * The same scan as {@link #findAll()}, plus the count of loan-bearing UTxOs it had to drop.
     *
     * <h2>Why this exists</h2>
     * {@code LiquidationExclusion.LOAN_NOT_FOUND} conflates two very different things: a loan that
     * was <b>settled</b> (the NFT is burned, the bond survives as the lender's claim ticket — the
     * ordinary post-settlement state) and a loan this node <b>cannot see or cannot read</b>. The
     * scan histogram cannot tell them apart, and the second is the shape that has already cost this
     * project an afternoon once.
     * <p>
     * <b>Two of the blindness routes are ruled out structurally and need no instrument.</b> The loan
     * NFT and the lender bond carry the <em>same</em> 28-byte asset name, both
     * {@code hash_output_ref(request outputRef)} from one {@code check_lend} (findings §11.2), so
     * they are minted in the <b>same {@code Lend} transaction</b>; and both
     * {@code loanSpendScriptHash} and {@code lenderManagerSpendScriptHash} are in
     * {@code indexedPaymentCredentials()}. So a <em>visible bond</em> proves its block was indexed
     * under a filter that also kept the loan credential — neither a redeploy nor a late
     * {@code sync-start-*} can hide a loan whose bond is visible, because both would have hidden the
     * bond too.
     * <p>
     * <b>The route that survives is this one.</b> Loans datum decoding is <b>hand-written</b>
     * (blueprint codegen is not viable for the loans blueprint), so a datum shape we do not model is
     * a live risk rather than a theoretical one — and such a loan is on chain, in the index, and
     * reported as {@code LOAN_NOT_FOUND}.
     * <p>
     * ⇒ <b>{@code unreadable == 0} is what makes "every {@code LOAN_NOT_FOUND} is a settled loan"
     * provable rather than probable.</b> While it is non-zero, that histogram is contaminated and
     * must not be suppressed or explained away.
     */
    public Census census() {
        var loanPolicyId = registry.getLoanPolicyId();

        var utxos = utxoRepository
                .findUnspentByOwnerPaymentCredential(registry.getLoanSpendScriptHash(), Pageable.unpaged())
                .stream()
                .flatMap(Collection::stream)
                .map(UtxoUtil::toUtxo)
                .toList();

        return classify(utxos, loanPolicyId);
    }

    /**
     * The classification itself, over a list of UTxOs — <b>separated from the repository so it can be
     * tested without one</b>, which is the only way to prove that junk at a public script address
     * does not inflate the blindness count.
     */
    Census classify(List<Utxo> utxos, String loanPolicyId) {
        List<Loan> loans = new ArrayList<>();
        int unreadable = 0;
        int notALoan = 0;
        for (Utxo utxo : utxos) {
            long loanNfts = utxo.getAmount().stream()
                    .filter(amount -> amount.getUnit().startsWith(loanPolicyId))
                    .count();
            if (loanNfts == 0) {
                // Junk. Anyone can pay to a script address, and this is not evidence of anything.
                notALoan++;
                continue;
            }
            Optional<Loan> loan = toLoan(utxo, loanPolicyId);
            if (loan.isPresent()) {
                loans.add(loan.get());
            } else {
                // Carries a loan NFT and still did not decode. THIS is the blindness signal.
                unreadable++;
            }
        }
        loans.sort(Comparator.comparing(Loan::loanId));

        if (unreadable > 0) {
            // WARN, not DEBUG. This used to be a debug line reporting only the raw counts, which an
            // INFO-level node never prints — so the one symptom of a decoder gap was written to a
            // stream nobody reads. Every one of these makes a bond report LOAN_NOT_FOUND for a loan
            // that is alive and indexed.
            log.warn("{} of {} utxos at the loan credential carry loan NFTs but could NOT be read — "
                            + "each one makes a bond report LOAN_NOT_FOUND for a loan that is ALIVE "
                            + "and INDEXED. Do not read the scan's LOAN_NOT_FOUND count as settled "
                            + "loans while this is non-zero.",
                    unreadable, utxos.size());
        }
        log.debug("{} utxos at the loan credential, {} decoded, {} unreadable, {} not loans",
                utxos.size(), loans.size(), unreadable, notALoan);
        return new Census(List.copyOf(loans), utxos.size(), unreadable, notALoan);
    }

    private Optional<Loan> toLoan(Utxo utxo, String loanPolicyId) {
        // The loan NFT is what separates a genuine loan from anything else paid to the address.
        var loanTokens = utxo.getAmount().stream()
                .filter(amount -> amount.getUnit().startsWith(loanPolicyId))
                .toList();
        if (loanTokens.size() != 1) {
            log.debug("skipping {}#{}: {} loan NFTs", utxo.getTxHash(), utxo.getOutputIndex(), loanTokens.size());
            return Optional.empty();
        }
        if (utxo.getInlineDatum() == null) {
            log.warn("loan utxo {}#{} carries the loan NFT but has no inline datum",
                    utxo.getTxHash(), utxo.getOutputIndex());
            return Optional.empty();
        }

        try {
            var datum = converter.deserialize(utxo.getInlineDatum());
            var loanId = loanTokens.getFirst().getUnit().substring(loanPolicyId.length());
            return Optional.of(new Loan(
                    utxo.getTxHash(),
                    utxo.getOutputIndex(),
                    utxo.getAddress(),
                    loanId,
                    collateralAmount(utxo, datum.collateral()),
                    quantityOf(utxo, AssetType.LOVELACE),
                    datum));
        } catch (Exception e) {
            log.warn("could not decode loan datum at {}#{}: {}",
                    utxo.getTxHash(), utxo.getOutputIndex(), e.getMessage());
            log.debug("undecodable loan datum", e);
            return Optional.empty();
        }
    }

    /**
     * {@code finance.get_collateral_amount}: a named collateral is that one asset's quantity,
     * while a collection (no asset name) is the sum across the whole policy.
     * <p>
     * Ada collateral is the empty policy id, and on chain
     * {@code quantity_of(value, "", "")} is the <em>lovelace</em> quantity. Matching a unit
     * literally named {@code ""} instead finds nothing and reports zero collateral, which reads
     * as a fully undercollateralised loan.
     */
    private static BigInteger collateralAmount(Utxo utxo, CollateralAsset collateral) {
        if (collateral.isAda()) {
            return quantityOf(utxo, AssetType.LOVELACE);
        }
        return collateral.assetName()
                .map(name -> quantityOf(utxo, collateral.policyId() + name))
                .orElseGet(() -> utxo.getAmount().stream()
                        .filter(amount -> amount.getUnit().startsWith(collateral.policyId()))
                        .map(Amount::getQuantity)
                        .reduce(BigInteger.ZERO, BigInteger::add));
    }

    private static BigInteger quantityOf(Utxo utxo, String unit) {
        if (unit == null) {
            return BigInteger.ZERO;
        }
        return utxo.getAmount().stream()
                .filter(amount -> unit.equals(amount.getUnit()))
                .map(Amount::getQuantity)
                .reduce(BigInteger.ZERO, BigInteger::add);
    }
}
