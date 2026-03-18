package com.meshchat;

public class Main {
    public static void main(String[] args) {
        String mode = System.getenv().getOrDefault("APP_MODE", "server");
        if ("server".equalsIgnoreCase(mode)) {
            new JavaServer().run();
        } else if ("client".equalsIgnoreCase(mode)) {
            new JavaClient().run();
        } else {
            throw new IllegalArgumentException("APP_MODE must be 'server' or 'client'");
        }
    }
}
