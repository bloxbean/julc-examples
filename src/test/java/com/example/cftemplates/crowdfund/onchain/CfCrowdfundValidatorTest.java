package com.example.cftemplates.crowdfund.onchain;

import org.julclang.core.PlutusData;
import org.julclang.testkit.ContractTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CfCrowdfundValidatorTest extends ContractTest {

    @BeforeAll
    static void setup() {
        initCrypto();
    }

    @Test
    void compilesSuccessfully() throws Exception {
        var compiled = compileValidator(CfCrowdfundValidator.class);
        assertFalse(compiled.hasErrors(), "Should compile: " + compiled);
        System.out.println("Script size: " + compiled.scriptSizeFormatted());
    }
}
