package com.example.util;

import org.julclang.stdlib.annotation.OnchainLibrary;

@OnchainLibrary
public class SumTest {

    public static int sum(int a, int b) {
        return a + b;
    }
}
