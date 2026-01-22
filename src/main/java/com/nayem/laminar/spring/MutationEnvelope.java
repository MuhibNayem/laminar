package com.nayem.laminar.spring;

public class MutationEnvelope {
    private String payload;
    private String mutationClass;
    private String entityKey;
    private int version = 1; // Schema version for backward compatibility

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public String getMutationClass() {
        return mutationClass;
    }

    public void setMutationClass(String mutationClass) {
        this.mutationClass = mutationClass;
    }

    public String getEntityKey() {
        return entityKey;
    }

    public void setEntityKey(String entityKey) {
        this.entityKey = entityKey;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }
}
