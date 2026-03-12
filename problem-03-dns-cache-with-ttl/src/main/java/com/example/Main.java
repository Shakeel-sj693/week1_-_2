package com.example;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

public class Main {

    public static void main(String[] args) throws Exception {
        UpstreamDNS upstream = new SimulatedUpstreamDNS(100, 180, 300);
        DNSCache cache = new DNSCache(3, upstream, 1);

        System.out.println(cache.resolve("google.com"));
        System.out.println(cache.resolve("google.com"));

        System.out.println("Sleeping 4 seconds to force TTL expiry...");
        Thread.sleep(4000);

        System.out.println(cache.resolve("google.com"));
        System.out.println(cache.resolve("openai.com"));
        System.out.println(cache.resolve("github.com"));
        System.out.println(cache.resolve("example.com"));
        System.out.println(cache.resolve("openai.com"));

        System.out.println(cache.getCacheStats());
        cache.shutdown();
    }

    interface UpstreamDNS {
        ResolveResult query(String domain);
    }

    static final class ResolveResult {
        final String domain;
        final String ipAddress;
        final int ttlSeconds;
        final long upstreamMillis;

        ResolveResult(String domain, String ipAddress, int ttlSeconds, long upstreamMillis) {
            this.domain = domain;
            this.ipAddress = ipAddress;
            this.ttlSeconds = ttlSeconds;
            this.upstreamMillis = upstreamMillis;
        }
    }

    static final class DNSCache {

        static final class DNSEntry {
            final String domain;
            final String ipAddress;
            final long createdAtNanos;
            final long expiresAtNanos;
            final int ttlSeconds;

            DNSEntry(String domain, String ipAddress, long nowNanos, int ttlSeconds) {
                this.domain = domain;
                this.ipAddress = ipAddress;
                this.createdAtNanos = nowNanos;
                this.ttlSeconds = ttlSeconds;
                this.expiresAtNanos = nowNanos + TimeUnit.SECONDS.toNanos(ttlSeconds);
            }

            boolean isExpired(long nowNanos) {
                return nowNanos >= expiresAtNanos;
            }
        }

        private final int maxSize;
        private final UpstreamDNS upstream;

        private final Object lock = new Object();

        private final LinkedHashMap<String, DNSEntry> lruMap =
                new LinkedHashMap<>(16, 0.75f, true);

        private final ScheduledExecutorService cleaner;

        private final LongAdder hits = new LongAdder();
        private final LongAdder misses = new LongAdder();
        private final LongAdder expired = new LongAdder();

        private final LongAdder lookups = new LongAdder();
        private final LongAdder totalLookupNanos = new LongAdder();

        DNSCache(int maxSize, UpstreamDNS upstream, int cleanupIntervalSeconds) {
            if (maxSize <= 0) throw new IllegalArgumentException("maxSize must be > 0");
            this.maxSize = maxSize;
            this.upstream = Objects.requireNonNull(upstream, "upstream required");

            this.cleaner = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "dns-cache-cleaner");
                t.setDaemon(true);
                return t;
            });

            int interval = Math.max(1, cleanupIntervalSeconds);
            cleaner.scheduleAtFixedRate(this::removeExpiredEntriesSafely, interval, interval, TimeUnit.SECONDS);
        }

        public String resolve(String domain) {
            long start = System.nanoTime();
            String d = normalize(domain);

            long now = System.nanoTime();
            DNSEntry cached;

            synchronized (lock) {
                cached = lruMap.get(d);
                if (cached != null && cached.isExpired(now)) {
                    lruMap.remove(d);
                    cached = null;
                    expired.increment();
                }

                if (cached != null) {
                    hits.increment();
                    recordLookup(start);
                    double ms = nanosToMillis(System.nanoTime() - start);
                    return "resolve(\"" + domain + "\") -> Cache HIT -> " + cached.ipAddress +
                            " (retrieved in " + formatMillis(ms) + "ms, TTL remaining: " + ttlRemainingSeconds(cached, now) + "s)";
                }
            }

            misses.increment();
            ResolveResult res = upstream.query(d);

            now = System.nanoTime();
            DNSEntry fresh = new DNSEntry(d, res.ipAddress, now, res.ttlSeconds);

            synchronized (lock) {
                lruMap.put(d, fresh);
                evictIfNeeded();
            }

            recordLookup(start);
            return "resolve(\"" + domain + "\") -> Cache MISS -> Query upstream -> " + res.ipAddress +
                    " (TTL: " + res.ttlSeconds + "s, upstream: " + res.upstreamMillis + "ms)";
        }

        public String getCacheStats() {
            long h = hits.sum();
            long m = misses.sum();
            long e = expired.sum();
            long total = h + m;

            double hitRate = total == 0 ? 0.0 : (100.0 * h / total);

            long lookupCount = lookups.sum();
            double avgMs = lookupCount == 0 ? 0.0 : nanosToMillis(totalLookupNanos.sum()) / lookupCount;

            int size;
            synchronized (lock) {
                size = lruMap.size();
            }

            return "getCacheStats() -> " +
                    "Hit Rate: " + formatPercent(hitRate) + "%, " +
                    "Hits: " + h + ", Misses: " + m + ", Expired: " + e + ", " +
                    "Avg Lookup Time: " + formatMillis(avgMs) + "ms, " +
                    "Cache Size: " + size + "/" + maxSize;
        }

        public void shutdown() {
            cleaner.shutdownNow();
        }

        private void evictIfNeeded() {
            while (lruMap.size() > maxSize) {
                Iterator<Map.Entry<String, DNSEntry>> it = lruMap.entrySet().iterator();
                if (!it.hasNext()) return;
                it.next();
                it.remove();
            }
        }

        private void removeExpiredEntriesSafely() {
            try {
                removeExpiredEntries();
            } catch (RuntimeException ignored) {
            }
        }

        private void removeExpiredEntries() {
            long now = System.nanoTime();
            synchronized (lock) {
                Iterator<Map.Entry<String, DNSEntry>> it = lruMap.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<String, DNSEntry> e = it.next();
                    if (e.getValue().isExpired(now)) {
                        it.remove();
                    }
                }
            }
        }

        private void recordLookup(long startNanos) {
            long dur = System.nanoTime() - startNanos;
            lookups.increment();
            totalLookupNanos.add(dur);
        }

        private static String normalize(String domain) {
            if (domain == null) return "";
            return domain.trim().toLowerCase();
        }

        private static long ttlRemainingSeconds(DNSEntry entry, long nowNanos) {
            long remainingNanos = entry.expiresAtNanos - nowNanos;
            if (remainingNanos <= 0) return 0;
            return TimeUnit.NANOSECONDS.toSeconds(remainingNanos);
        }

        private static double nanosToMillis(long nanos) {
            return nanos / 1_000_000.0;
        }

        private static String formatMillis(double ms) {
            return String.format(java.util.Locale.US, "%.3f", ms);
        }

        private static String formatPercent(double p) {
            return String.format(java.util.Locale.US, "%.1f", p);
        }
    }

    static final class SimulatedUpstreamDNS implements UpstreamDNS {
        private final Random rnd = new Random(1);
        private final int minLatencyMs;
        private final int maxLatencyMs;
        private final int defaultTtlSeconds;

        private final ConcurrentHashMap<String, AtomicInteger> versionByDomain = new ConcurrentHashMap<>();

        SimulatedUpstreamDNS(int minLatencyMs, int maxLatencyMs, int defaultTtlSeconds) {
            this.minLatencyMs = Math.max(0, minLatencyMs);
            this.maxLatencyMs = Math.max(this.minLatencyMs, maxLatencyMs);
            this.defaultTtlSeconds = Math.max(1, defaultTtlSeconds);
        }

        @Override
        public ResolveResult query(String domain) {
            long start = System.nanoTime();
            sleep(simulatedLatencyMs());

            int v = versionByDomain.computeIfAbsent(domain, d -> new AtomicInteger(0)).incrementAndGet();
            String ip = generateIp(domain, v);

            long durMs = Math.max(1L, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
            return new ResolveResult(domain, ip, defaultTtlSeconds, durMs);
        }

        private int simulatedLatencyMs() {
            if (maxLatencyMs == minLatencyMs) return minLatencyMs;
            return minLatencyMs + rnd.nextInt((maxLatencyMs - minLatencyMs) + 1);
        }

        private static void sleep(int ms) {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }

        private static String generateIp(String domain, int version) {
            int h = Math.abs(Objects.hash(domain, version));
            int a = 1 + (h % 223);
            int b = (h / 223) % 255;
            int c = (h / (223 * 255)) % 255;
            int d = (h / (223 * 255 * 255)) % 255;
            return a + "." + b + "." + c + "." + d;
        }
    }
}