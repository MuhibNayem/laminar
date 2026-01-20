package com.nayem.laminar.spring;

public class MutationEnvelope {
    private String payload;
    private String mutationClass;
    private String entityKey;

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
}
