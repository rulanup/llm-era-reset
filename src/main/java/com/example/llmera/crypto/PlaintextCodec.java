package com.example.llmera.crypto;

import com.example.llmera.LLMEra;

/** v1 dev codec. Stores apiKey as the envelope unchanged. NOT for production. */
public final class PlaintextCodec implements EnvelopeCodec {
    @Override
    public String encrypt(String plain) {
        return plain == null ? "" : "v1:" + plain;
    }

    @Override
    public String decrypt(String blob) {
        if (blob == null) return "";
        if (blob.startsWith("v1:")) return blob.substring(3);
        return blob;
    }

    @Override
    public boolean isLocked() {
        return false;
    }

    @Override
    public String statusLine() {
        return "v1 plaintext (dev only)";
    }
}
