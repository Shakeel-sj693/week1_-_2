package com.example;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;

public class Main {

    public static void main(String[] args) throws Exception {
        RealTimeAnalytics analytics = new RealTimeAnalytics(10);

        ScheduledExecutorService dashboard = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "dashboard-updater");
            t.setDaemon(true);
            return t;
        });

        dashboard.scheduleAtFixedRate(() -> {
            DashboardSnapshot snap = analytics.getDashboardSnapshot();
            System.out.println(snap.format());
        }, 0, 5, TimeUnit.SECONDS);

        int threads = 64;
        int secondsToRun = 20;
        long eventsPerSecond = 20_000;

        ExecutorService producers = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            final int worker = i;
            producers.submit(() -> {
                try {
                    long endAt = System.nanoTime() + TimeUnit.SECONDS.toNanos(secondsToRun);
                    long perThreadRate = Math.max(1, eventsPerSecond / threads);

                    long seq = worker * 1_000_000_000L;
                    while (System.nanoTime() < endAt) {
                        long batchStart = System.nanoTime();
                        for (int k = 0; k < perThreadRate; k++) {
                            PageViewEvent e = generateEvent(seq++);
                            analytics.processEvent(e);
                        }

                        long tookNanos = System.nanoTime() - batchStart;
                        long targetNanos = TimeUnit.SECONDS.toNanos(1);
                        if (tookNanos < targetNanos) {
                            LockSupport.parkNanos(targetNanos - tookNanos);
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        producers.shutdownNow();

        Thread.sleep(5500);
        dashboard.shutdownNow();
        analytics.shutdown();
    }

    static PageViewEvent generateEvent(long i) {
        String[] pages = {
                "/article/breaking-news",
                "/sports/championship",
                "/politics/election",
                "/tech/ai",
                "/world/market",
                "/health/nutrition",
                "/travel/top-10",
                "/finance/stocks",
                "/culture/movies",
                "/local/weather"
        };

        String[] sources = {"google", "direct", "facebook", "twitter", "newsletter", "other"};
        String[] locations = {"US", "IN", "GB", "CA", "AU", "DE", "BR", "JP", "FR", "OTHER"};

        String url = pages[(int) (Math.abs(hash64(i)) % pages.length)];
        String source = sources[(int) (Math.abs(hash64(i * 31)) % sources.length)];
        String location = locations[(int) (Math.abs(hash64(i * 131)) % locations.length)];

        String userId = "user_" + (Math.abs(hash64(i * 997)) % 200_000);

        return new PageViewEvent(url, userId, source, location, System.currentTimeMillis());
    }

    static long hash64(long x) {
        x ^= (x >>> 33);
        x *= 0xff51afd7ed558ccdL;
        x ^= (x >>> 33);
        x *= 0xc4ceb9fe1a85ec53L;
        x ^= (x >>> 33);
        return x;
    }

    static final class PageViewEvent {
        final String url;
        final String userId;
        final String source;
        final String location;
        final long timestampMs;

        PageViewEvent(String url, String userId, String source, String location, long timestampMs) {
            this.url = Objects.requireNonNull(url, "url");
            this.userId = Objects.requireNonNull(userId, "userId");
            this.source = Objects.requireNonNull(source, "source");
            this.location = Objects.requireNonNull(location, "location");
            this.timestampMs = timestampMs;
        }
    }

    static final class RealTimeAnalytics {
        private final int topN;

        private final ConcurrentHashMap<String, LongAdder> pageViews = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, Set<String>> uniqueVisitorsByPage = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, LongAdder> sourceCounts = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, LongAdder> locationCounts = new ConcurrentHashMap<>();

        private final ScheduledExecutorService maintenance;

        RealTimeAnalytics(int topN) {
            this.topN = Math.max(1, topN);

            this.maintenance = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "analytics-maintenance");
                t.setDaemon(true);
                return t;
            });

            maintenance.scheduleAtFixedRate(this::warmSets, 1, 1, TimeUnit.SECONDS);
        }

        public void processEvent(PageViewEvent e) {
            pageViews.computeIfAbsent(e.url, k -> new LongAdder()).increment();

            uniqueVisitorsByPage
                    .computeIfAbsent(e.url, k -> ConcurrentHashMap.newKeySet())
                    .add(e.userId);

            sourceCounts.computeIfAbsent(normalizeKey(e.source), k -> new LongAdder()).increment();
            locationCounts.computeIfAbsent(normalizeKey(e.location), k -> new LongAdder()).increment();
        }

        public DashboardSnapshot getDashboardSnapshot() {
            Map<String, Long> viewsSnap = snapshotLongAdders(pageViews);
            Map<String, Long> sourcesSnap = snapshotLongAdders(sourceCounts);
            Map<String, Long> locationsSnap = snapshotLongAdders(locationCounts);

            long totalViews = viewsSnap.values().stream().mapToLong(Long::longValue).sum();
            long totalSource = sourcesSnap.values().stream().mapToLong(Long::longValue).sum();

            List<TopPageRow> topPages = computeTopPages(viewsSnap);

            for (TopPageRow row : topPages) {
                Set<String> set = uniqueVisitorsByPage.get(row.url);
                row.uniqueVisitors = (set == null) ? 0 : set.size();
            }

            return new DashboardSnapshot(topPages, sourcesSnap, locationsSnap, totalViews, totalSource);
        }

        public void shutdown() {
            maintenance.shutdownNow();
        }

        private void warmSets() {
            uniqueVisitorsByPage.size();
        }

        private List<TopPageRow> computeTopPages(Map<String, Long> viewsSnap) {
            PriorityQueue<TopPageRow> minHeap = new PriorityQueue<>(Comparator
                    .comparingLong((TopPageRow r) -> r.views)
                    .thenComparing(r -> r.url));

            for (Map.Entry<String, Long> e : viewsSnap.entrySet()) {
                TopPageRow row = new TopPageRow(e.getKey(), e.getValue());
                if (minHeap.size() < topN) {
                    minHeap.add(row);
                } else if (row.views > Objects.requireNonNull(minHeap.peek()).views) {
                    minHeap.poll();
                    minHeap.add(row);
                }
            }

            List<TopPageRow> out = new ArrayList<>(minHeap);
            out.sort(Comparator.comparingLong((TopPageRow r) -> r.views).reversed().thenComparing(r -> r.url));
            return out;
        }

        private static Map<String, Long> snapshotLongAdders(ConcurrentHashMap<String, LongAdder> map) {
            ConcurrentHashMap<String, Long> snap = new ConcurrentHashMap<>();
            for (Map.Entry<String, LongAdder> e : map.entrySet()) {
                snap.put(e.getKey(), e.getValue().sum());
            }
            return snap;
        }

        private static String normalizeKey(String s) {
            if (s == null) return "unknown";
            String x = s.trim().toLowerCase(Locale.US);
            return x.isBlank() ? "unknown" : x;
        }
    }

    static final class TopPageRow {
        final String url;
        final long views;
        volatile int uniqueVisitors;

        TopPageRow(String url, long views) {
            this.url = url;
            this.views = views;
        }
    }

    static final class DashboardSnapshot {
        final List<TopPageRow> topPages;
        final Map<String, Long> sourceCounts;
        final Map<String, Long> locationCounts;
        final long totalViews;
        final long totalSourceEvents;

        DashboardSnapshot(List<TopPageRow> topPages,
                          Map<String, Long> sourceCounts,
                          Map<String, Long> locationCounts,
                          long totalViews,
                          long totalSourceEvents) {
            this.topPages = topPages;
            this.sourceCounts = sourceCounts;
            this.locationCounts = locationCounts;
            this.totalViews = totalViews;
            this.totalSourceEvents = totalSourceEvents;
        }

        String format() {
            StringBuilder sb = new StringBuilder();

            sb.append("\ngetDashboard() ->\n");
            sb.append("Top Pages:\n");
            int rank = 1;
            for (TopPageRow r : topPages) {
                sb.append(rank++).append(". ").append(r.url)
                        .append(" - ").append(String.format(Locale.US, "%,d", r.views))
                        .append(" views (").append(String.format(Locale.US, "%,d", r.uniqueVisitors))
                        .append(" unique)\n");
            }

            sb.append("\nTraffic Sources:\n");
            List<Map.Entry<String, Long>> src = new ArrayList<>(sourceCounts.entrySet());
            src.sort(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry::getKey));

            for (Map.Entry<String, Long> e : src) {
                double pct = totalSourceEvents == 0 ? 0.0 : (100.0 * e.getValue() / totalSourceEvents);
                sb.append(cap(e.getKey())).append(": ")
                        .append(String.format(Locale.US, "%.1f%%", pct))
                        .append(" (").append(String.format(Locale.US, "%,d", e.getValue())).append(")\n");
            }

            sb.append("\nUser Locations:\n");
            List<Map.Entry<String, Long>> loc = new ArrayList<>(locationCounts.entrySet());
            loc.sort(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry::getKey));
            int shown = 0;
            for (Map.Entry<String, Long> e : loc) {
                if (shown++ >= 10) break;
                double pct = totalViews == 0 ? 0.0 : (100.0 * e.getValue() / totalViews);
                sb.append(cap(e.getKey())).append(": ")
                        .append(String.format(Locale.US, "%.1f%%", pct))
                        .append(" (").append(String.format(Locale.US, "%,d", e.getValue())).append(")\n");
            }

            return sb.toString();
        }

        private static String cap(String s) {
            if (s == null || s.isBlank()) return "Unknown";
            if (s.length() == 1) return s.toUpperCase(Locale.US);
            return s.substring(0, 1).toUpperCase(Locale.US) + s.substring(1);
        }
    }
}
