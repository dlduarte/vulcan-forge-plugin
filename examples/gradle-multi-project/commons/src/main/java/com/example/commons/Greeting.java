package com.example.commons;

/** Codigo compartilhado entre os servicos do build multi-projeto. */
public final class Greeting {

    private Greeting() {
    }

    public static String forService(String name) {
        return "Hello from " + name;
    }
}
