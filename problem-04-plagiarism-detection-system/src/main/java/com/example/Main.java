package com.example;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class Main {

    public static void main(String[] args) {
        PlagiarismDetector detector = new PlagiarismDetector(5);

        detector.addDocument("essay_089.txt",
                "Data structures and algorithms are fundamental in computer science. " +
                        "Hash tables provide average constant time operations. " +
                        "This essay explains hashing and collision resolution.");

        detector.addDocument("essay_092.txt",
                "Data structures and algorithms are fundamental in computer science. " +
                        "Hash tables provide average constant time operations. " +
                        "This essay explains hashing and collision resolution. " +
                        "Additionally, it covers n gram based similarity detection used in plagiarism detection systems.");

        detector.addDocument("essay_200.txt",
                "Cloud computing enables scalable systems. DNS caches reduce lookup latency. " +
                        "Load balancers distribute traffic across servers. This essay is unrelated to hashing basics.");

        String newEssay = "Data structures and algorithms are fundamental in computer science. " +
                "Hash tables provide average constant time operations. " +
                "This essay explains hashing and collision resolution. " +
                "It also describes n gram similarity detection for plagiarism detection.";

        AnalysisReport report = detector.analyzeDocument("essay_123.txt", newEssay, 3);

        System.out.println("analyzeDocument(\"essay_123.txt\")");
        System.out.println("-> Extracted " + report.ngramsExtracted + " n-grams");
        for (SimilarityMatch m : report.topMatches) {
            System.out.println("-> Found " + m.matchingNgrams + " matching n-grams with \"" + m.documentId + "\"");
            System.out.println("-> Similarity: " + String.format(Locale.US, "%.1f", m.similarityPercent) + "% " + m.verdict);
        }
    }

    static final class AnalysisReport {
        final String documentId;
        final int ngramsExtracted;
        final List<SimilarityMatch> topMatches;

        AnalysisReport(String documentId, int ngramsExtracted, List<SimilarityMatch> topMatches) {
            this.documentId = documentId;
            this.ngramsExtracted = ngramsExtracted;
            this.topMatches = topMatches;
        }
    }

    static final class SimilarityMatch {
        final String documentId;
        final int matchingNgrams;
        final int totalNgramsInTarget;
        final double similarityPercent;
        final String verdict;

        SimilarityMatch(String documentId, int matchingNgrams, int totalNgramsInTarget, double similarityPercent, String verdict) {
            this.documentId = documentId;
            this.matchingNgrams = matchingNgrams;
            this.totalNgramsInTarget = totalNgramsInTarget;
            this.similarityPercent = similarityPercent;
            this.verdict = verdict;
        }
    }

    static final class PlagiarismDetector {

        private final int n;
        private final Map<String, Set<String>> ngramToDocuments = new HashMap<>();
        private final Map<String, Set<String>> documentToNgrams = new HashMap<>();

        PlagiarismDetector(int n) {
            if (n <= 0) throw new IllegalArgumentException("n must be > 0");
            this.n = n;
        }

        public void addDocument(String documentId, String text) {
            if (documentId == null || documentId.isBlank()) {
                throw new IllegalArgumentException("documentId is required");
            }

            Set<String> ngrams = extractNgrams(text, n);
            documentToNgrams.put(documentId, ngrams);

            for (String ng : ngrams) {
                ngramToDocuments.computeIfAbsent(ng, k -> new HashSet<>()).add(documentId);
            }
        }

        public AnalysisReport analyzeDocument(String documentId, String text, int topK) {
            Set<String> queryNgrams = extractNgrams(text, n);
            int extracted = queryNgrams.size();

            Map<String, Integer> matchCounts = new HashMap<>();

            for (String ng : queryNgrams) {
                Set<String> docs = ngramToDocuments.get(ng);
                if (docs == null) continue;
                for (String doc : docs) {
                    matchCounts.merge(doc, 1, Integer::sum);
                }
            }

            List<SimilarityMatch> matches = new ArrayList<>();
            for (Map.Entry<String, Integer> e : matchCounts.entrySet()) {
                String otherDocId = e.getKey();
                int matching = e.getValue();

                Set<String> otherNgrams = documentToNgrams.get(otherDocId);
                int otherTotal = otherNgrams == null ? 0 : otherNgrams.size();

                double similarity = extracted == 0 ? 0.0 : (100.0 * matching / extracted);
                String verdict = verdict(similarity);

                matches.add(new SimilarityMatch(otherDocId, matching, otherTotal, similarity, verdict));
            }

            matches.sort(Comparator
                    .comparingDouble((SimilarityMatch m) -> m.similarityPercent).reversed()
                    .thenComparingInt(m -> -m.matchingNgrams)
                    .thenComparing(m -> m.documentId));

            if (topK > 0 && matches.size() > topK) {
                matches = matches.subList(0, topK);
            }

            return new AnalysisReport(documentId, extracted, matches);
        }

        private static String verdict(double similarityPercent) {
            if (similarityPercent >= 60.0) return "(PLAGIARISM DETECTED)";
            if (similarityPercent >= 15.0) return "(suspicious)";
            return "";
        }

        private static Set<String> extractNgrams(String text, int n) {
            String[] tokens = tokenize(text);
            if (tokens.length < n) return Set.of();

            Set<String> ngrams = new HashSet<>();
            for (int i = 0; i <= tokens.length - n; i++) {
                StringBuilder sb = new StringBuilder();
                for (int j = 0; j < n; j++) {
                    if (j > 0) sb.append(' ');
                    sb.append(tokens[i + j]);
                }
                ngrams.add(sb.toString());
            }
            return ngrams;
        }

        private static String[] tokenize(String text) {
            if (text == null) return new String[0];
            String cleaned = text
                    .toLowerCase(Locale.US)
                    .replaceAll("[^a-z0-9\\s]+", " ")
                    .trim();
            if (cleaned.isBlank()) return new String[0];
            return cleaned.split("\\s+");
        }
    }
}
