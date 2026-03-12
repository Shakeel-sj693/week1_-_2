package com.example;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.LongAdder;

public class Main {

    public static void main(String[] args) {
        VideoDatabase db = new VideoDatabase();
        // Seed database
        for (int i = 1; i <= 200_000; i++) {
            String id = "video_" + i;
            db.put(new VideoData(id, ("payload-for-" + id).getBytes()));
        }

        MultiLevelCache cache = new MultiLevelCache(
                10_000,   // L1 capacity
                100_000,  // L2 capacity
                3         // promote threshold: accesses in L2 before promoting to L1
        );

        // Preload some popular content into L1
        for (int i = 1; i <= 10_000; i++) {
            cache.warmL1(db.get("video_" + i));
        }

        // Demo a few sample calls matching the prompt shape
        System.out.println(cache.getVideo("video_123", db).format());
        System.out.println(cache.getVideo("video_123", db).format());
        System.out.println(cache.getVideo("video_99999", db).format());

        // Benchmark-ish simulation
        for (int i = 0; i < 50_000; i++) {
            String vid = "video_" + (1 + skewedZipfLike(200_000));
            cache.getVideo(vid, db);
            if (i % 10_000 == 0 && i > 0) {
                // simulate some invalidations
                String updated = "video_" + (1 + ThreadLocalRandom.current().nextInt(200_000));
                db.put(new VideoData(updated, ("updated-" + updated).getBytes()));
                cache.invalidate(updated);
            }
        }

        System.out.println(cache.getStatistics());
    }

    // crude skew generator: small ids are more likely (popular)
    static int skewedZipfLike(int n) {
        double r = ThreadLocalRandom.current().nextDouble();
        double x = Math.pow(r, 3.5); // heavier skew
        return (int) Math.min(n - 1, Math.floor(x * n));
    }

    // -------------------- Models --------------------

    static final class VideoData {
        final String videoId;
        final byte[] bytes;
        final long version; // changes when content updates

        VideoData(String videoId, byte[] bytes) {
            this(videoId, bytes, System.nanoTime());
        }

        VideoData(String videoId, byte[] bytes, long version) {
            this.videoId = Objects.requireNonNull(videoId);
            this.bytes = Objects.requireNonNull(bytes);
            this.version = version;
        }
    }

    static final class VideoDatabase {
        private final ConcurrentHashMap<String, VideoData> store = new ConcurrentHashMap<>();

        public VideoData get(String videoId) {
            // simulate slow DB read (150ms typical per prompt)
            sleepMs(150);
            return store.get(videoId);
        }

        public void put(VideoData v) {
            store.put(v.videoId, v);
        }
    }

    // -------------------- Cache tiers --------------------

    interface CacheTier {
        VideoData get(String videoId);

        void put(VideoData data);

        void invalidate(String videoId);

        int size();
    }

    /**
     * LRU cache implemented with LinkedHashMap(accessOrder=true).
     * Not thread-safe by itself; we synchronize externally for simplicity.
     */
    static final class LruCache implements CacheTier {
        private final int capacity;
        private final LinkedHashMap<String, VideoData> map;

        LruCache(int capacity) {
            this.capacity = capacity;
            this.map = new LinkedHashMap<>(Math.max(16, capacity), 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, VideoData> eldest) {
                    return size() > LruCache.this.capacity;
                }
            };
        }

        @Override
        public VideoData get(String videoId) {
            return map.get(videoId);
        }

        @Override
        public void put(VideoData data) {
            map.put(data.videoId, data);
        }

        @Override
        public void invalidate(String videoId) {
            map.remove(videoId);
        }

        @Override
        public int size() {
            return map.size();
        }
    }

    /**
     * "SSD-backed" cache tier simulation.
     * Stores VideoData in an LRU map, but applies a ~5ms latency on hits.
     */
    static final class SsdBackedCache implements CacheTier {
        private final LruCache lru;

        SsdBackedCache(int capacity) {
            this.lru = new LruCache(capacity);
        }

        @Override
        public VideoData get(String videoId) {
            VideoData v = lru.get(videoId);
            if (v != null) sleepMs(5);
            return v;
        }

        @Override
        public void put(VideoData data) {
            lru.put(data);
        }

        @Override
        public void invalidate(String videoId) {
            lru.invalidate(videoId);
        }

        @Override
        public int size() {
            return lru.size();
        }
    }

    // -------------------- Multi-level orchestrator --------------------

    static final class MultiLevelCache {
        // tiers
        private final LruCache l1;
        private final SsdBackedCache l2;

        // access count used to decide promotions L2 -> L1
        private final ConcurrentHashMap<String, LongAdder> accessCount = new ConcurrentHashMap<>();
        private final int promoteThreshold;

        // stats
        private final TierStats l1Stats = new TierStats("L1", 0.5);
        private final TierStats l2Stats = new TierStats("L2", 5.0);
        private final TierStats l3Stats = new TierStats("L3", 150.0);

        MultiLevelCache(int l1Capacity, int l2Capacity, int promoteThreshold) {
            this.l1 = new LruCache(l1Capacity);
            this.l2 = new SsdBackedCache(l2Capacity);
            this.promoteThreshold = Math.max(1, promoteThreshold);
        }

        public void warmL1(VideoData data) {
            if (data == null) return;
            synchronized (l1) {
                l1.put(data);
            }
        }

        public GetVideoTrace getVideo(String videoId, VideoDatabase db) {
            long start = System.nanoTime();
            GetVideoTrace trace = new GetVideoTrace(videoId);

            // L1
            VideoData v;
            synchronized (l1) {
                v = l1.get(videoId);
            }
            if (v != null) {
                l1Stats.hit();
                trace.l1 = "L1 Cache HIT (0.5ms)";
                trace.totalMs = elapsedMs(start);
                return trace.withData(v);
            }
            l1Stats.miss();
            trace.l1 = "L1 Cache MISS (0.5ms)";

            // L2
            synchronized (l2) {
                v = l2.get(videoId);
            }
            if (v != null) {
                l2Stats.hit();
                trace.l2 = "L2 Cache HIT (5ms)";

                long cnt = accessCount.computeIfAbsent(videoId, k -> new LongAdder()).sum() + 1;
                accessCount.computeIfAbsent(videoId, k -> new LongAdder()).increment();

                if (cnt >= promoteThreshold) {
                    synchronized (l1) {
                        l1.put(v);
                    }
                    trace.promotion = "Promoted to L1";
                }
                trace.totalMs = elapsedMs(start);
                return trace.withData(v);
            }
            l2Stats.miss();
            trace.l2 = "L2 Cache MISS (5ms)";

            // L3 (DB)
            v = db.get(videoId);
            if (v != null) {
                l3Stats.hit();
                trace.l3 = "L3 Database HIT (150ms)";

                // add to L2 and track access count
                synchronized (l2) {
                    l2.put(v);
                }
                accessCount.computeIfAbsent(videoId, k -> new LongAdder()).increment();
                trace.addedToL2 = "Added to L2 (access count: " + accessCount.get(videoId).sum() + ")";
            } else {
                l3Stats.miss();
                trace.l3 = "L3 Database MISS (150ms) (not found)";
            }

            trace.totalMs = elapsedMs(start);
            return trace.withData(v);
        }

        public void invalidate(String videoId) {
            synchronized (l1) {
                l1.invalidate(videoId);
            }
            synchronized (l2) {
                l2.invalidate(videoId);
            }
            accessCount.remove(videoId);
        }

        public String getStatistics() {
            TierStatsSnapshot s1 = l1Stats.snapshot();
            TierStatsSnapshot s2 = l2Stats.snapshot();
            TierStatsSnapshot s3 = l3Stats.snapshot();

            long totalReq = s1.requests + s2.requests + s3.requests;
            long totalHits = s1.hits + s2.hits + s3.hits;

            double overallHitRate = totalReq == 0 ? 0.0 : (100.0 * totalHits / totalReq);

            // Weighted average by tier "time per hit/miss" (simulated constants)
            double avgTime = weightedAvgTime(s1, s2, s3);

            return "getStatistics() ->\n" +
                    "L1: Hit Rate " + fmtPct(s1.hitRatePct) + ", Avg Time: 0.5ms, Size: " + l1.size() + "\n" +
                    "L2: Hit Rate " + fmtPct(s2.hitRatePct) + ", Avg Time: 5ms, Size: " + l2.size() + "\n" +
                    "L3: Hit Rate " + fmtPct(s3.hitRatePct) + ", Avg Time: 150ms\n" +
                    "Overall: Hit Rate " + fmtPct(overallHitRate) + ", Avg Time: " + String.format(Locale.US, "%.2f", avgTime) + "ms";
        }

        private static double weightedAvgTime(TierStatsSnapshot s1, TierStatsSnapshot s2, TierStatsSnapshot s3) {
            // Each request always "touches" L1; only L1 miss touches L2; only L2 miss touches L3.
            // We'll approximate using request counts recorded per tier in this demo.
            long total = s1.requests + s2.requests + s3.requests;
            if (total == 0) return 0.0;
            double sum = s1.requests * s1.assumedAvgMs + s2.requests * s2.assumedAvgMs + s3.requests * s3.assumedAvgMs;
            return sum / total;
        }

        private static String fmtPct(double v) {
            return String.format(Locale.US, "%.1f%%", v);
        }
    }

    static final class GetVideoTrace {
        final String videoId;
        String l1;
        String l2;
        String l3;
        String promotion;
        String addedToL2;
        double totalMs;
        VideoData data;

        GetVideoTrace(String videoId) {
            this.videoId = videoId;
        }

        GetVideoTrace withData(VideoData d) {
            this.data = d;
            return this;
        }

        String format() {
            StringBuilder sb = new StringBuilder();
            sb.append("getVideo(\"").append(videoId).append("\")\n");
            if (l1 != null) sb.append("→ ").append(l1).append("\n");
            if (l2 != null) sb.append("→ ").append(l2).append("\n");
            if (l3 != null) sb.append("→ ").append(l3).append("\n");
            if (promotion != null) sb.append("→ ").append(promotion).append("\n");
            if (addedToL2 != null) sb.append("→ ").append(addedToL2).append("\n");
            sb.append("→ Total: ").append(String.format(Locale.US, "%.1f", totalMs)).append("ms");
            return sb.toString();
        }
    }

    static final class TierStats {
        final String name;
        final double assumedAvgMs;

        private final LongAdder hits = new LongAdder();
        private final LongAdder misses = new LongAdder();

        TierStats(String name, double assumedAvgMs) {
            this.name = name;
            this.assumedAvgMs = assumedAvgMs;
        }

        void hit() {
            hits.increment();
        }

        void miss() {
            misses.increment();
        }

        TierStatsSnapshot snapshot() {
            long h = hits.sum();
            long m = misses.sum();
            long r = h + m;
            double hr = r == 0 ? 0.0 : (100.0 * h / r);
            return new TierStatsSnapshot(h, m, r, hr, assumedAvgMs);
        }
    }

    static final class TierStatsSnapshot {
        final long hits;
        final long misses;
        final long requests;
        final double hitRatePct;
        final double assumedAvgMs;

        TierStatsSnapshot(long hits, long misses, long requests, double hitRatePct, double assumedAvgMs) {
            this.hits = hits;
            this.misses = misses;
            this.requests = requests;
            this.hitRatePct = hitRatePct;
            this.assumedAvgMs = assumedAvgMs;
        }
    }

    // -------------------- utils --------------------

    static void sleepMs(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    static double elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000.0;
    }