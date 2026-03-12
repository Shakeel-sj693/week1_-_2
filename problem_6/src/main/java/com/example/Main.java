package com.example;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Main {

    public static void main(String[] args) throws Exception {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(
                1000,
                3600
        );

        String clientId = "abc123";

        System.out.println(limiter.checkRateLimit(clientId));
        System.out.println(limiter.checkRateLimit(clientId));

        RateLimitDecision denied = null;
        for (int i = 0; i < 2000; i++) {
            RateLimitDecision d = limiter.checkRateLimit(clientId);
            if (!d.allowed) {
                denied = d;
                break;
            }
        }

        if (denied != null) {
            System.out.println(denied);
        }

        System.out.println(limiter.getRateLimitStatus(clientId));

        int threads = 32;
        int calls = 50_000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(calls);
        long start = System.nanoTime();

        for (int i = 0; i < calls; i++) {
            final String c = "client_" + (i % 5000);
            pool.submit(() -> {
                try {
                    limiter.checkRateLimit(c);
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        pool.shutdown();

        long end = System.nanoTime();
        double ms = (end - start) / 1_000_000.0;
        System.out.printf(Locale.US, "Processed %,d checks in %.2fms (avg %.3fµs/check)%n",
                calls, ms, (ms * 1000.0) / calls);
    }

    static final class TokenBucketRateLimiter {

        private final ConcurrentHashMap<String, TokenBucket> buckets = new ConcurrentHashMap<>();

        private final long capacity;
        private final long refillPeriodSeconds;
        private final double refillTokensPerSecond;

        TokenBucketRateLimiter(long capacityPerHour, long periodSeconds) {
            if (capacityPerHour <= 0) throw new IllegalArgumentException("capacity must be > 0");
            if (periodSeconds <= 0) throw new IllegalArgumentException("periodSeconds must be > 0");

            this.capacity = capacityPerHour;
            this.refillPeriodSeconds = periodSeconds;
            this.refillTokensPerSecond = capacityPerHour / (double) periodSeconds;
        }

        public RateLimitDecision checkRateLimit(String clientId) {
            String id = normalizeClientId(clientId);
            TokenBucket bucket = buckets.computeIfAbsent(id, k -> TokenBucket.newFull(capacity));

            return bucket.tryConsume(1, capacity, refillTokensPerSecond, refillPeriodSeconds);
        }

        public RateLimitStatus getRateLimitStatus(String clientId) {
            String id = normalizeClientId(clientId);
            TokenBucket bucket = buckets.computeIfAbsent(id, k -> TokenBucket.newFull(capacity));
            return bucket.status(capacity, refillTokensPerSecond, refillPeriodSeconds);
        }

        private static String normalizeClientId(String clientId) {
            if (clientId == null) return "unknown";
            String x = clientId.trim();
            return x.isEmpty() ? "unknown" : x;
        }
    }

    static final class TokenBucket {

        private double tokens;
        private long lastRefillEpochSeconds;

        private TokenBucket(double tokens, long lastRefillEpochSeconds) {
            this.tokens = tokens;
            this.lastRefillEpochSeconds = lastRefillEpochSeconds;
        }

        static TokenBucket newFull(long capacity) {
            long now = Instant.now().getEpochSecond();
            return new TokenBucket(capacity, now);
        }

        RateLimitDecision tryConsume(long requested,
                                    long capacity,
                                    double refillRateTokensPerSecond,
                                    long periodSeconds) {
            if (requested <= 0) {
                return new RateLimitDecision(true, remainingTokensRounded(), 0,
                        "Allowed (" + remainingTokensRounded() + " requests remaining)");
            }

            long now = Instant.now().getEpochSecond();

            long retryAfterSeconds;
            long remaining;
            long resetEpochSeconds;

            synchronized (this) {
                refill(now, capacity, refillRateTokensPerSecond);

                if (tokens >= requested) {
                    tokens -= requested;
                    remaining = (long) Math.floor(tokens);
                    resetEpochSeconds = computeResetEpochSeconds(now, periodSeconds);
                    return new RateLimitDecision(true, remaining, 0,
                            "Allowed (" + remaining + " requests remaining)", resetEpochSeconds);
                }

                double missing = requested - tokens;
                double secondsToWait = missing / refillRateTokensPerSecond;
                retryAfterSeconds = (long) Math.ceil(secondsToWait);

                remaining = (long) Math.floor(tokens);
                resetEpochSeconds = computeResetEpochSeconds(now, periodSeconds);
            }

            return new RateLimitDecision(false, remaining, retryAfterSeconds,
                    "Denied (0 requests remaining, retry after " + retryAfterSeconds + "s)",
                    resetEpochSeconds);
        }

        RateLimitStatus status(long capacity, double refillRateTokensPerSecond, long periodSeconds) {
            long now = Instant.now().getEpochSecond();
            synchronized (this) {
                refill(now, capacity, refillRateTokensPerSecond);
                long remaining = (long) Math.floor(tokens);
                long used = capacity - remaining;
                long reset = computeResetEpochSeconds(now, periodSeconds);
                return new RateLimitStatus(used, capacity, reset, remaining);
            }
        }

        private void refill(long nowEpochSeconds, long capacity, double refillRateTokensPerSecond) {
            long elapsed = Math.max(0L, nowEpochSeconds - lastRefillEpochSeconds);
            if (elapsed == 0L) return;

            double add = elapsed * refillRateTokensPerSecond;
            tokens = Math.min(capacity, tokens + add);
            lastRefillEpochSeconds = nowEpochSeconds;
        }

        private long remainingTokensRounded() {
            return (long) Math.floor(tokens);
        }

        private static long computeResetEpochSeconds(long nowEpochSeconds, long periodSeconds) {
            long windowStart = (nowEpochSeconds / periodSeconds) * periodSeconds;
            return windowStart + periodSeconds;
        }
    }

    static final class RateLimitDecision {
        final boolean allowed;
        final long remaining;
        final long retryAfterSeconds;
        final String message;
        final long resetEpochSeconds;

        RateLimitDecision(boolean allowed, long remaining, long retryAfterSeconds, String message) {
            this(allowed, remaining, retryAfterSeconds, message, 0);
        }

        RateLimitDecision(boolean allowed, long remaining, long retryAfterSeconds, String message, long resetEpochSeconds) {
            this.allowed = allowed;
            this.remaining = remaining;
            this.retryAfterSeconds = retryAfterSeconds;
            this.message = Objects.requireNonNull(message, "message");
            this.resetEpochSeconds = resetEpochSeconds;
        }

        @Override
        public String toString() {
            return "checkRateLimit(clientId) -> " + message;
        }
    }

    static final class RateLimitStatus {
        final long used;
        final long limit;
        final long resetEpochSeconds;
        final long remaining;

        RateLimitStatus(long used, long limit, long resetEpochSeconds, long remaining) {
            this.used = used;
            this.limit = limit;
            this.resetEpochSeconds = resetEpochSeconds;
            this.remaining = remaining;
        }

        @Override
        public String toString() {
            return "getRateLimitStatus() -> {used: " + used +
                    ", limit: " + limit +
                    ", remaining: " + remaining +
                    ", reset: " + resetEpochSeconds + "}";
        }
    }
}