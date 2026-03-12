package com.example;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

public class Main {

    public static void main(String[] args) {
        AutocompleteSystem ac = new AutocompleteSystem(10);

        ac.updateFrequency("java tutorial");
        ac.updateFrequency("java tutorial");
        ac.updateFrequency("java tutorial");
        ac.updateFrequency("javascript");
        ac.updateFrequency("javascript");
        ac.updateFrequency("java download");
        ac.updateFrequency("java 21 features");
        ac.updateFrequency("java 21 features");
        ac.updateFrequency("java 21 features");

        System.out.println("search(\"jav\") ->");
        List<Suggestion> s1 = ac.search("jav");
        for (int i = 0; i < s1.size(); i++) {
            Suggestion s = s1.get(i);
            System.out.println((i + 1) + ". \"" + s.query + "\" (" + String.format(Locale.US, "%d", s.frequency) + " searches)");
        }

        System.out.println();
        System.out.println("updateFrequency(\"java 21 features\") -> " + ac.updateFrequency("java 21 features"));

        System.out.println();
        System.out.println("search(\"java 21 feautres\") (typo) ->");
        List<Suggestion> s2 = ac.search("java 21 feautres");
        for (int i = 0; i < s2.size(); i++) {
            Suggestion s = s2.get(i);
            System.out.println((i + 1) + ". \"" + s.query + "\" (" + String.format(Locale.US, "%d", s.frequency) + " searches)");
        }
    }

    static final class Suggestion {
        final String query;
        final long frequency;

        Suggestion(String query, long frequency) {
            this.query = query;
            this.frequency = frequency;
        }
    }

    static final class AutocompleteSystem {

        private final int topK;

        private final ConcurrentHashMap<String, LongAdder> globalFrequency = new ConcurrentHashMap<>();
        private final TrieNode root = new TrieNode();

        private final ConcurrentHashMap<String, CacheEntry> prefixCache = new ConcurrentHashMap<>();
        private final int cacheMaxEntries = 50_000;

        AutocompleteSystem(int topK) {
            this.topK = Math.max(1, topK);
        }

        public long updateFrequency(String query) {
            String q = normalizeQuery(query);
            if (q.isBlank()) return 0L;

            LongAdder adder = globalFrequency.computeIfAbsent(q, k -> new LongAdder());
            adder.increment();

            long freq = adder.sum();

            addToTrie(q);

            invalidateCachedPrefixes(q);

            return freq;
        }

        public List<Suggestion> search(String userInput) {
            long start = System.nanoTime();
            String prefix = normalizeQuery(userInput);

            if (prefix.isBlank()) return List.of();

            CacheEntry cached = prefixCache.get(prefix);
            if (cached != null && cached.isFresh()) {
                return cached.suggestions;
            }

            List<String> candidates = collectCandidates(prefix);

            if (candidates.isEmpty()) {
                String corrected = bestCorrection(prefix);
                if (!corrected.equals(prefix)) {
                    candidates = collectCandidates(corrected);
                }
            }

            List<Suggestion> top = topKSuggestions(candidates, topK);

            putCache(prefix, top);

            double ms = (System.nanoTime() - start) / 1_000_000.0;
            if (ms > 50.0) {
                System.out.printf(Locale.US, "Warning: search(\"%s\") took %.2fms%n", userInput, ms);
            }

            return top;
        }

        private List<String> collectCandidates(String prefix) {
            TrieNode node = findNode(prefix);
            if (node == null) return List.of();

            ArrayList<String> out = new ArrayList<>(Math.min(1000, node.subtreeQueryCount));
            StringBuilder sb = new StringBuilder(prefix);
            dfsCollect(node, sb, out, 5000);
            return out;
        }

        private List<Suggestion> topKSuggestions(List<String> queries, int k) {
            PriorityQueue<Suggestion> minHeap = new PriorityQueue<>(
                    Comparator.comparingLong((Suggestion s) -> s.frequency)
                            .thenComparing(s -> s.query, Comparator.reverseOrder())
            );

            for (String q : queries) {
                long f = getFrequency(q);
                Suggestion s = new Suggestion(q, f);

                if (minHeap.size() < k) {
                    minHeap.add(s);
                } else {
                    Suggestion smallest = minHeap.peek();
                    if (smallest == null) continue;

                    if (f > smallest.frequency || (f == smallest.frequency && q.compareTo(smallest.query) < 0)) {
                        minHeap.poll();
                        minHeap.add(s);
                    }
                }
            }

            ArrayList<Suggestion> result = new ArrayList<>(minHeap);
            result.sort(Comparator
                    .comparingLong((Suggestion s) -> s.frequency).reversed()
                    .thenComparing(s -> s.query));
            return result;
        }

        private long getFrequency(String query) {
            LongAdder adder = globalFrequency.get(query);
            return adder == null ? 0L : adder.sum();
        }

        private void addToTrie(String query) {
            TrieNode cur = root;
            cur.subtreeQueryCount++;

            for (int i = 0; i < query.length(); i++) {
                char ch = query.charAt(i);
                cur = cur.children.computeIfAbsent(ch, c -> new TrieNode());
                cur.subtreeQueryCount++;
            }
            cur.isTerminal = true;
        }

        private TrieNode findNode(String prefix) {
            TrieNode cur = root;
            for (int i = 0; i < prefix.length(); i++) {
                cur = cur.children.get(prefix.charAt(i));
                if (cur == null) return null;
            }
            return cur;
        }

        private void dfsCollect(TrieNode node, StringBuilder sb, List<String> out, int limit) {
            if (out.size() >= limit) return;
            if (node.isTerminal) {
                out.add(sb.toString());
                if (out.size() >= limit) return;
            }

            for (Map.Entry<Character, TrieNode> e : node.children.entrySet()) {
                sb.append(e.getKey());
                dfsCollect(e.getValue(), sb, out, limit);
                sb.deleteCharAt(sb.length() - 1);
                if (out.size() >= limit) return;
            }
        }

        private String bestCorrection(String input) {
            List<String> approxPrefixes = generateEditDistance1Prefixes(input);
            String best = input;
            long bestScore = -1;

            for (String p : approxPrefixes) {
                TrieNode node = findNode(p);
                if (node == null) continue;
                long score = node.subtreeQueryCount;
                if (score > bestScore) {
                    bestScore = score;
                    best = p;
                }
            }
            return best;
        }

        private List<String> generateEditDistance1Prefixes(String s) {
            ArrayList<String> out = new ArrayList<>();
            out.add(s);

            for (int i = 0; i < s.length(); i++) {
                out.add(s.substring(0, i) + s.substring(i + 1));
            }

            for (int i = 0; i < s.length(); i++) {
                for (char c = 'a'; c <= 'z'; c++) {
                    out.add(s.substring(0, i) + c + s.substring(i + 1));
                }
            }

            for (int i = 0; i <= s.length(); i++) {
                for (char c = 'a'; c <= 'z'; c++) {
                    out.add(s.substring(0, i) + c + s.substring(i));
                }
            }

            return out;
        }

        private void invalidateCachedPrefixes(String query) {
            int maxPrefix = Math.min(25, query.length());
            for (int i = 1; i <= maxPrefix; i++) {
                prefixCache.remove(query.substring(0, i));
            }
        }

        private void putCache(String prefix, List<Suggestion> suggestions) {
            if (prefixCache.size() > cacheMaxEntries) {
                prefixCache.clear();
            }
            prefixCache.put(prefix, new CacheEntry(suggestions, System.nanoTime()));
        }

        private static String normalizeQuery(String s) {
            if (s == null) return "";
            String cleaned = s.trim().toLowerCase(Locale.US).replaceAll("\\s+", " ");
            return cleaned;
        }

        static final class TrieNode {
            final HashMap<Character, TrieNode> children = new HashMap<>();
            boolean isTerminal;
            int subtreeQueryCount;
        }

        static final class CacheEntry {
            final List<Suggestion> suggestions;
            final long createdAtNanos;
            final long ttlNanos = 2_000_000_000L;

            CacheEntry(List<Suggestion> suggestions, long createdAtNanos) {
                this.suggestions = suggestions;
                this.createdAtNanos = createdAtNanos;
            }

            boolean isFresh() {
                return (System.nanoTime() - createdAtNanos) <= ttlNanos;
            }
        }
    }
}