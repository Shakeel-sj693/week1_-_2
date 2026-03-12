package com.example;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.LongAdder;

public class Main {

    public static void main(String[] args) throws InterruptedException {
        UsernameAvailabilityService service = new UsernameAvailabilityService(
                List.of("john_doe", "admin", "root", "jane.doe")
        );

        System.out.println("checkAvailability(\"john_doe\") -> " + service.checkAvailability("john_doe")); // false
        System.out.println("checkAvailability(\"jane_smith\") -> " + service.checkAvailability("jane_smith")); // true

        System.out.println("suggestAlternatives(\"john_doe\") -> " +
                service.suggestAlternatives("john_doe", 3));

 
        for (int i = 0; i < 10; i++) service.checkAvailability("admin");

      
        int checks = 1000;
        ExecutorService pool = Executors.newFixedThreadPool(32);
        CountDownLatch latch = new CountDownLatch(checks);

        for (int i = 0; i < checks; i++) {
            final int n = i;
            pool.submit(() -> {
                try {
                    String u = (n % 3 == 0) ? "admin" : ("user_" + (n % 200));
                    service.checkAvailability(u);
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        pool.shutdown();

        System.out.println("getMostAttempted() -> " + service.getMostAttempted()
                + " (" + service.getMostAttemptedCount() + " attempts)");
        System.out.println("Attempts for 'admin' -> " + service.getAttempts("admin"));
    }

    
    static class UsernameAvailabilityService {

 
        private final ConcurrentHashMap<String, Long> usernameToUserId = new ConcurrentHashMap<>();

        private final AttemptTracker attemptTracker = new AttemptTracker();
        private final UsernameSuggestionEngine suggestionEngine = new UsernameSuggestionEngine();

        UsernameAvailabilityService() {}

        UsernameAvailabilityService(List<String> initialTakenUsernames) {
            if (initialTakenUsernames != null) {
                long id = 1L;
                for (String u : initialTakenUsernames) {
                    if (u != null && !u.isBlank()) {
                        usernameToUserId.put(normalize(u), id++);
                    }
                }
            }
        }

  
        boolean exists(String username) {
            if (username == null) return false;
            return usernameToUserId.containsKey(normalize(username));
        }

        boolean isAvailable(String username) {
            if (username == null) return false;
            String u = normalize(username);
            if (u.isBlank()) return false;
            return !usernameToUserId.containsKey(u);
        }

        
        boolean checkAvailability(String username) {
            String u = normalize(username);
            attemptTracker.recordAttempt(u);
            return isAvailable(u);
        }

       
        boolean register(String username, long userId) {
            if (username == null) return false;
            String u = normalize(username);
            attemptTracker.recordAttempt(u);

            if (u.isBlank()) return false;
            return usernameToUserId.putIfAbsent(u, userId) == null;
        }

        List<String> suggestAlternatives(String requested, int maxSuggestions) {
            String u = normalize(requested);
            attemptTracker.recordAttempt(u);
            return suggestionEngine.suggest(u, this, maxSuggestions);
        }

        String getMostAttempted() {
            return attemptTracker.getMostAttemptedUsername();
        }

        long getMostAttemptedCount() {
            return attemptTracker.getMostAttemptedCount();
        }

        long getAttempts(String username) {
            return attemptTracker.getAttempts(normalize(username));
        }

        private String normalize(String username) {
            return username == null ? "" : username.trim().toLowerCase();
        }
    }

    
    static class AttemptTracker {
        private final ConcurrentHashMap<String, LongAdder> attempts = new ConcurrentHashMap<>();

        void recordAttempt(String username) {
            if (username == null) return;
            attempts.computeIfAbsent(username, u -> new LongAdder()).increment();
        }

        long getAttempts(String username) {
            LongAdder adder = attempts.get(username);
            return adder == null ? 0L : adder.sum();
        }

        over tracked usernames. Fine for occasional analytics query.
        
        String getMostAttemptedUsername() {
            String best = null;
            long bestCount = -1L;

            for (Map.Entry<String, LongAdder> e : attempts.entrySet()) {
                long c = e.getValue().sum();
                if (c > bestCount) {
                    bestCount = c;
                    best = e.getKey();
                }
            }
            return best;
        }

        long getMostAttemptedCount() {
            long bestCount = 0L;
            for (LongAdder adder : attempts.values()) {
                bestCount = Math.max(bestCount, adder.sum());
            }
            return bestCount;
        }
    }

    
    static class UsernameSuggestionEngine {

        List<String> suggest(String requested, UsernameAvailabilityService service, int maxSuggestions) {
            if (requested == null) return List.of();
            String base = normalize(requested);
            if (base.isBlank() || maxSuggestions <= 0) return List.of();

            Set<String> candidates = new LinkedHashSet<>();

           
            candidates.addAll(separatorVariants(base));

            
            candidates.add(base + "official");
            candidates.add(base + "real");

           
            for (int i = 1; i <= 999 && candidates.size() < maxSuggestions * 10; i++) {
                candidates.add(base + i);
            }

           
            List<String> result = new ArrayList<>(maxSuggestions);
            for (String c : candidates) {
                if (result.size() >= maxSuggestions) break;
                if (service.isAvailable(c)) result.add(c);
            }
            return result;
        }

        private Set<String> separatorVariants(String s) {
            Set<String> v = new LinkedHashSet<>();
            v.add(s);

            if (s.contains("_")) v.add(s.replace('_', '.'));
            if (s.contains(".")) v.add(s.replace('.', '_'));

            v.add(s.replace("_", "").replace(".", "")); 
            v.add(s.replace("__", "_").replace("..", ".")); 

            return v;
        }

        private String normalize(String username) {
            return username.trim().toLowerCase();
        }
    }
}
