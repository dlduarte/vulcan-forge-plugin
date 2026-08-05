package com.example.commons;

/** Codigo compartilhado entre os servicos do reator. */
public final class Greeting {

    private Greeting() {
    }

    public static String forService(String name) {
        return "Hello from " + name;
    }
}
