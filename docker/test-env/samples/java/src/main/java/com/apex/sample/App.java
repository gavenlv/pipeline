package com.apex.sample;

/**
 * Sample application for integration tests.
 * Intentionally free of hardcoded secrets.
 */
public class App {
    public static void main(String[] args) {
        String name = System.getenv().getOrDefault("APP_NAME", "apex-treasury-svc");
        System.out.println("Hello from " + name);
    }

    public static int add(int a, int b) {
        return a + b;
    }
}
