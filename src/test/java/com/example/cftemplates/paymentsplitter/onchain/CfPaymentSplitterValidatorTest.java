package com.example.cftemplates.paymentsplitter.onchain;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.testkit.ContractTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CfPaymentSplitterValidatorTest extends ContractTest {

    @BeforeAll
    static void setup() {
        initCrypto();
    }

    @Test
    void compilesSuccessfully() throws Exception {
        var compiled = compileValidator(CfPaymentSplitterValidator.class);
        assertFalse(compiled.hasErrors(), "Should compile: " + compiled);
        System.out.println("Script size: " + compiled.scriptSizeFormatted());
    }
}
