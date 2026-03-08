package com.example.cftemplates.simplewallet.onchain;

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

class CfWalletFundsValidatorTest extends ContractTest {

    static final byte[] OWNER = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10,
            11, 12, 13, 14, 15, 16, 17, 18, 19, 20,
            21, 22, 23, 24, 25, 26, 27, 28};
    static final byte[] RECIPIENT = new byte[]{61, 62, 63, 64, 65, 66, 67, 68, 69, 70,
            71, 72, 73, 74, 75, 76, 77, 78, 79, 80,
            81, 82, 83, 84, 85, 86, 87, 88};

    // Wallet policy ID (the CfSimpleWalletValidator's script hash)
    static final byte[] WALLET_POLICY_ID = new byte[]{(byte) 0xC1, (byte) 0xC2, (byte) 0xC3, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0};

    // Funds script hash (this validator's own address)
    static final byte[] FUNDS_SCRIPT_HASH = new byte[]{(byte) 0xD1, (byte) 0xD2, (byte) 0xD3, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0};

    static final BigInteger PAYMENT_AMOUNT = BigInteger.valueOf(5_000_000);

    @BeforeAll
    static void setup() {
        initCrypto();
    }

    static Address fundsScriptAddress() {
        return new Address(
                new Credential.ScriptCredential(new ScriptHash(FUNDS_SCRIPT_HASH)),
                Optional.empty());
    }

    static Address walletScriptAddress() {
        return new Address(
                new Credential.ScriptCredential(new ScriptHash(WALLET_POLICY_ID)),
                Optional.empty());
    }

    static Address pubKeyAddress(byte[] pkh) {
        return new Address(
                new Credential.PubKeyCredential(new PubKeyHash(pkh)),
                Optional.empty());
    }

    // PaymentIntent = Constr(0, [recipient, lovelaceAmt, data])
    static PlutusData paymentIntentDatum(byte[] recipientPkh, BigInteger lovelaceAmt, byte[] data) {
        var recipientAddr = pubKeyAddress(recipientPkh).toPlutusData();
        return PlutusData.constr(0, recipientAddr, PlutusData.integer(lovelaceAmt), PlutusData.bytes(data));
    }

    @Nested
    class ExecuteTxTests {

        @Test
        void executeTx_validPayment_markerBurned_passes() throws Exception {
            var compiled = compileValidator(CfWalletFundsValidator.class);
            var program = compiled.program()
                    .applyParams(PlutusData.bytes(OWNER), PlutusData.bytes(WALLET_POLICY_ID));

            var fundsDatum = PlutusData.constr(0); // arbitrary datum
            // ExecuteTx = tag 0
            var redeemer = PlutusData.constr(0);

            // Funds input at funds script address
            var fundsRef = TestDataBuilder.randomTxOutRef_typed();
            var fundsInput = new TxInInfo(fundsRef,
                    new TxOut(fundsScriptAddress(),
                            Value.lovelace(BigInteger.valueOf(50_000_000)),
                            new OutputDatum.OutputDatumInline(fundsDatum),
                            Optional.empty()));

            // Intent input from wallet script with intent marker token + PaymentIntent datum
            var intentDatum = paymentIntentDatum(RECIPIENT, PAYMENT_AMOUNT, new byte[0]);
            var walletPolicyId = new PolicyId(WALLET_POLICY_ID);
            var tokenName = new TokenName(new byte[]{0x49, 0x4E, 0x54}); // "INT"
            var intentInputValue = Value.lovelace(BigInteger.valueOf(2_000_000))
                    .merge(Value.singleton(walletPolicyId, tokenName, BigInteger.ONE));
            var intentRef = TestDataBuilder.randomTxOutRef_typed();
            var intentInput = new TxInInfo(intentRef,
                    new TxOut(walletScriptAddress(),
                            intentInputValue,
                            new OutputDatum.OutputDatumInline(intentDatum),
                            Optional.empty()));

            // Output pays recipient at least PAYMENT_AMOUNT
            var recipientOutput = new TxOut(pubKeyAddress(RECIPIENT),
                    Value.lovelace(PAYMENT_AMOUNT),
                    new OutputDatum.NoOutputDatum(), Optional.empty());

            // Burn intent marker
            var burnValue = Value.singleton(walletPolicyId, tokenName, BigInteger.ONE.negate());

            var ctx = spendingContext(fundsRef, fundsDatum)
                    .redeemer(redeemer)
                    .input(fundsInput)
                    .input(intentInput)
                    .output(recipientOutput)
                    .signer(OWNER)
                    .mint(burnValue)
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertSuccess(result);
            logBudget("executeTx_validPayment_markerBurned_passes", result);
        }
    }

    @Nested
    class WithdrawTests {

        @Test
        void withdraw_ownerSigns_passes() throws Exception {
            var compiled = compileValidator(CfWalletFundsValidator.class);
            var program = compiled.program()
                    .applyParams(PlutusData.bytes(OWNER), PlutusData.bytes(WALLET_POLICY_ID));

            var fundsDatum = PlutusData.constr(0);
            // WithdrawFunds = tag 1
            var redeemer = PlutusData.constr(1);

            var fundsRef = TestDataBuilder.randomTxOutRef_typed();
            var fundsInput = new TxInInfo(fundsRef,
                    new TxOut(fundsScriptAddress(),
                            Value.lovelace(BigInteger.valueOf(50_000_000)),
                            new OutputDatum.OutputDatumInline(fundsDatum),
                            Optional.empty()));

            var ctx = spendingContext(fundsRef, fundsDatum)
                    .redeemer(redeemer)
                    .input(fundsInput)
                    .signer(OWNER)
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertSuccess(result);
            logBudget("withdraw_ownerSigns_passes", result);
        }
    }

    private static void logBudget(String testName, com.bloxbean.cardano.julc.vm.EvalResult result) {
        var budget = result.budgetConsumed();
        System.out.println("[" + testName + "] CPU: " + budget.cpuSteps() + ", Mem: " + budget.memoryUnits());
    }
}
