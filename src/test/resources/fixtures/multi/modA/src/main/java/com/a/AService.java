package com.a;

public class AService {

    public int doble(int x) {
        if (x < 0) {
            throw new IllegalArgumentException("negativo");
        }
        return x * 2;
    }
}