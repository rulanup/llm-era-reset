package com.example.llmera.crypto;

public interface EnvelopeCodec {
    String encrypt(String plain);
    String decrypt(String blob);
    boolean isLocked();
    String statusLine();
}
