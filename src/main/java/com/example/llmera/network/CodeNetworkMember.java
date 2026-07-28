package com.example.llmera.network;

/** A block or block entity that participates in the lightweight code network. */
public interface CodeNetworkMember {
    default int channelDemand() {
        return 1;
    }
}
