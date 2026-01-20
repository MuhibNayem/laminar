package com.nayem.laminar.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

@ConfigurationProperties(prefix = "laminar")
public class LaminarProperties {

    private int maxWaiters = 1000;

    @DurationUnit(ChronoUnit.SECONDS)
    private Duration timeout = Duration.ofSeconds(30);

    @DurationUnit(ChronoUnit.MINUTES)
    private Duration workerEvictionTime = Duration.ofMinutes(10);

    private long maxCachedWorkers = 0;

    @DurationUnit(ChronoUnit.SECONDS)
    private Duration shutdownTimeout = Duration.ofSeconds(10);

    @DurationUnit(ChronoUnit.MILLIS)
    private Duration shutdownPollingInterval = Duration.ofMillis(100);

    private String threadNamePrefix = "laminar-worker-";

    public int getMaxWaiters() {
        return maxWaiters;
    }

    public void setMaxWaiters(int maxWaiters) {
        this.maxWaiters = maxWaiters;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public Duration getWorkerEvictionTime() {
        return workerEvictionTime;
    }

    public void setWorkerEvictionTime(Duration workerEvictionTime) {
        this.workerEvictionTime = workerEvictionTime;
    }

    public long getMaxCachedWorkers() {
        return maxCachedWorkers;
    }

    public void setMaxCachedWorkers(long maxCachedWorkers) {
        this.maxCachedWorkers = maxCachedWorkers;
    }

    public Duration getShutdownTimeout() {
        return shutdownTimeout;
    }

    public void setShutdownTimeout(Duration shutdownTimeout) {
        this.shutdownTimeout = shutdownTimeout;
    }

    public Duration getShutdownPollingInterval() {
        return shutdownPollingInterval;
    }

    public void setShutdownPollingInterval(Duration shutdownPollingInterval) {
        this.shutdownPollingInterval = shutdownPollingInterval;
    }

    public String getThreadNamePrefix() {
        return threadNamePrefix;
    }

    public void setThreadNamePrefix(String threadNamePrefix) {
        this.threadNamePrefix = threadNamePrefix;
    }
}
