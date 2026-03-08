package com.example.cftemplates.tokentransfer.onchain;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.testkit.ContractTest;
import com.bloxbean.cardano.julc.testkit.TestDataBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CfTokenTransferValidatorTest extends ContractTest {

    static final byte[] RECEIVER = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10,
            11, 12, 13, 14, 15, 16, 17, 18, 19, 20,
            21, 22, 23, 24, 25, 26, 27, 28};
    static final byte[] POLICY = new byte[]{51, 52, 53, 54, 55, 56, 57, 58, 59, 60,
            61, 62, 63, 64, 65, 66, 67, 68, 69, 70,
            71, 72, 73, 74, 75, 76, 77, 78};
    static final byte[] ASSET_NAME = "TestToken".getBytes();

    @BeforeAll
    static void setup() {
        initCrypto();
    }

    @Test
    void compilesSuccessfully() throws Exception {
        var compiled = compileValidator(CfTokenTransferValidator.class);
        assertFalse(compiled.hasErrors(), "Should compile: " + compiled);
        System.out.println("Script size: " + compiled.scriptSizeFormatted());
    }

    private static void logBudget(String testName, com.bloxbean.cardano.julc.vm.EvalResult result) {
        var budget = result.budgetConsumed();
        System.out.println("[" + testName + "] CPU: " + budget.cpuSteps() + ", Mem: " + budget.memoryUnits());
    }
}
