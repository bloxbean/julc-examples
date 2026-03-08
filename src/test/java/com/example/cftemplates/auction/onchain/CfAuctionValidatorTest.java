package com.example.cftemplates.auction.onchain;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.ledger.*;
import com.bloxbean.cardano.julc.testkit.ContractTest;
import com.bloxbean.cardano.julc.testkit.TestDataBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class CfAuctionValidatorTest extends ContractTest {

    static final byte[] SELLER = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10,
            11, 12, 13, 14, 15, 16, 17, 18, 19, 20,
            21, 22, 23, 24, 25, 26, 27, 28};
    static final byte[] BIDDER1 = new byte[]{31, 32, 33, 34, 35, 36, 37, 38, 39, 40,
            41, 42, 43, 44, 45, 46, 47, 48, 49, 50,
            51, 52, 53, 54, 55, 56, 57, 58};
    static final byte[] BIDDER2 = new byte[]{61, 62, 63, 64, 65, 66, 67, 68, 69, 70,
            71, 72, 73, 74, 75, 76, 77, 78, 79, 80,
            81, 82, 83, 84, 85, 86, 87, 88};
    static final byte[] ASSET_POLICY = new byte[]{(byte) 0xAA, (byte) 0xBB, (byte) 0xCC, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0};
    static final byte[] ASSET_NAME = new byte[]{0x4E, 0x46, 0x54}; // "NFT"
    static final BigInteger EXPIRATION = BigInteger.valueOf(1_700_000_000_000L);
    static final byte[] EMPTY_BYTES = new byte[0];

    // Script hash for the auction script address
    static final byte[] SCRIPT_HASH = new byte[]{(byte) 0xB1, (byte) 0xB2, (byte) 0xB3, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0};

    @BeforeAll
    static void setup() {
        initCrypto();
    }

    static Address scriptAddress() {
        return new Address(
                new Credential.ScriptCredential(new ScriptHash(SCRIPT_HASH)),
                Optional.empty());
    }

    static Address pubKeyAddress(byte[] pkh) {
        return new Address(
                new Credential.PubKeyCredential(new PubKeyHash(pkh)),
                Optional.empty());
    }

    // AuctionDatum = Constr(0, [seller, highestBidder, highestBid, expiration, assetPolicy, assetName])
    static PlutusData auctionDatum(byte[] seller, byte[] bidder, BigInteger bid) {
        return PlutusData.constr(0,
                PlutusData.bytes(seller), PlutusData.bytes(bidder),
                PlutusData.integer(bid), PlutusData.integer(EXPIRATION),
                PlutusData.bytes(ASSET_POLICY), PlutusData.bytes(ASSET_NAME));
    }

    @Nested
    class EndTests {

        @Test
        void end_afterExpiration_noBids_sellerReclaims_passes() throws Exception {
            var compiled = compileValidator(CfAuctionValidator.class);
            var program = compiled.program();

            var datum = auctionDatum(SELLER, EMPTY_BYTES, BigInteger.ZERO);
            // End = tag 2
            var redeemer = PlutusData.constr(2);

            var spentRef = TestDataBuilder.randomTxOutRef_typed();
            var spentInput = new TxInInfo(spentRef,
                    new TxOut(scriptAddress(),
                            Value.lovelace(BigInteger.valueOf(2_000_000)),
                            new OutputDatum.OutputDatumInline(datum),
                            Optional.empty()));

            var ctx = spendingContext(spentRef, datum)
                    .redeemer(redeemer)
                    .input(spentInput)
                    .signer(SELLER)
                    .validRange(Interval.after(EXPIRATION))
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertSuccess(result);
            logBudget("end_afterExpiration_noBids_sellerReclaims_passes", result);
        }

        @Test
        void end_beforeExpiration_fails() throws Exception {
            var compiled = compileValidator(CfAuctionValidator.class);
            var program = compiled.program();

            var datum = auctionDatum(SELLER, EMPTY_BYTES, BigInteger.ZERO);
            var redeemer = PlutusData.constr(2);

            var spentRef = TestDataBuilder.randomTxOutRef_typed();
            var spentInput = new TxInInfo(spentRef,
                    new TxOut(scriptAddress(),
                            Value.lovelace(BigInteger.valueOf(2_000_000)),
                            new OutputDatum.OutputDatumInline(datum),
                            Optional.empty()));

            var ctx = spendingContext(spentRef, datum)
                    .redeemer(redeemer)
                    .input(spentInput)
                    .signer(SELLER)
                    .validRange(Interval.before(EXPIRATION))
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertFailure(result);
        }

        @Test
        void end_withBids_sellerPaidWinnerGetsAsset_passes() throws Exception {
            var compiled = compileValidator(CfAuctionValidator.class);
            var program = compiled.program();

            BigInteger highBid = BigInteger.valueOf(10_000_000);
            var datum = auctionDatum(SELLER, BIDDER1, highBid);
            var redeemer = PlutusData.constr(2);

            var spentRef = TestDataBuilder.randomTxOutRef_typed();
            var spentInput = new TxInInfo(spentRef,
                    new TxOut(scriptAddress(),
                            Value.lovelace(highBid),
                            new OutputDatum.OutputDatumInline(datum),
                            Optional.empty()));

            // Seller gets the ADA
            var sellerOutput = new TxOut(pubKeyAddress(SELLER),
                    Value.lovelace(highBid),
                    new OutputDatum.NoOutputDatum(), Optional.empty());

            // Winner gets the asset
            var winnerValue = Value.lovelace(BigInteger.valueOf(2_000_000))
                    .merge(Value.singleton(new PolicyId(ASSET_POLICY),
                            new TokenName(ASSET_NAME), BigInteger.ONE));
            var winnerOutput = new TxOut(pubKeyAddress(BIDDER1),
                    winnerValue,
                    new OutputDatum.NoOutputDatum(), Optional.empty());

            var ctx = spendingContext(spentRef, datum)
                    .redeemer(redeemer)
                    .input(spentInput)
                    .output(sellerOutput)
                    .output(winnerOutput)
                    .validRange(Interval.after(EXPIRATION))
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertSuccess(result);
            logBudget("end_withBids_sellerPaidWinnerGetsAsset_passes", result);
        }
    }

    @Nested
    class MintTests {

        @Test
        void mint_sellerSigns_noBidder_passes() throws Exception {
            var compiled = compileValidator(CfAuctionValidator.class);
            var program = compiled.program();

            var redeemer = PlutusData.constr(0);

            // AuctionDatum with empty bidder, bid=0
            var datum = auctionDatum(SELLER, EMPTY_BYTES, BigInteger.ZERO);

            // The mint entrypoint finds outputs to the script address (keyed by policyId bytes)
            // Use SCRIPT_HASH as policyId so findOutputToScript matches
            var policyId = new PolicyId(SCRIPT_HASH);

            // Output to script address carrying the asset and inline datum
            var outputValue = Value.lovelace(BigInteger.valueOf(2_000_000))
                    .merge(Value.singleton(new PolicyId(ASSET_POLICY),
                            new TokenName(ASSET_NAME), BigInteger.ONE));
            var auctionOutput = new TxOut(scriptAddress(),
                    outputValue,
                    new OutputDatum.OutputDatumInline(datum),
                    Optional.empty());

            var ctx = mintingContext(policyId)
                    .redeemer(redeemer)
                    .signer(SELLER)
                    .output(auctionOutput)
                    .validRange(Interval.before(EXPIRATION))
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertSuccess(result);
            logBudget("mint_sellerSigns_noBidder_passes", result);
        }

        @Test
        void mint_withExistingBidder_fails() throws Exception {
            var compiled = compileValidator(CfAuctionValidator.class);
            var program = compiled.program();

            var redeemer = PlutusData.constr(0);
            var datum = auctionDatum(SELLER, BIDDER1, BigInteger.valueOf(5_000_000));

            var policyId = new PolicyId(SCRIPT_HASH);

            var outputValue = Value.lovelace(BigInteger.valueOf(2_000_000))
                    .merge(Value.singleton(new PolicyId(ASSET_POLICY),
                            new TokenName(ASSET_NAME), BigInteger.ONE));
            var auctionOutput = new TxOut(scriptAddress(),
                    outputValue,
                    new OutputDatum.OutputDatumInline(datum),
                    Optional.empty());

            var ctx = mintingContext(policyId)
                    .redeemer(redeemer)
                    .signer(SELLER)
                    .output(auctionOutput)
                    .validRange(Interval.before(EXPIRATION))
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertFailure(result);
        }
    }

    private static void logBudget(String testName, com.bloxbean.cardano.julc.vm.EvalResult result) {
        var budget = result.budgetConsumed();
        System.out.println("[" + testName + "] CPU: " + budget.cpuSteps() + ", Mem: " + budget.memoryUnits());
    }
}
