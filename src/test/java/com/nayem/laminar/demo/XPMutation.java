package com.nayem.laminar.demo;

import com.nayem.laminar.core.Mutation;

import java.util.concurrent.atomic.AtomicLong;

public class XPMutation implements Mutation<AtomicLong> {
    private final String userId;
    private final long xpToAdd;

    public XPMutation(String userId, long xpToAdd) {
        this.userId = userId;
        this.xpToAdd = xpToAdd;
    }

    @Override
    public String getEntityKey() {
        return userId;
    }

    @Override
    public Mutation<AtomicLong> coalesce(Mutation<AtomicLong> other) {
        if (other instanceof XPMutation) {
            XPMutation o = (XPMutation) other;
            return new XPMutation(userId, this.xpToAdd + o.xpToAdd);
        }
        return other;
    }

    @Override
    public void apply(AtomicLong entity) {
        entity.addAndGet(xpToAdd);
    }

    public long getAmount() {
        return xpToAdd;
    }
}
