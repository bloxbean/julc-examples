package com.example.util;

import com.bloxbean.cardano.julc.stdlib.annotation.OnchainLibrary;

@OnchainLibrary
public class SumTest {

    public static int sum(int a, int b) {
        return a + b;
    }
}
