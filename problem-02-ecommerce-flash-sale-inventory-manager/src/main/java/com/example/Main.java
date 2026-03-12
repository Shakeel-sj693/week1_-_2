package com.example;

import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

public class Main {

    public static void main(String[] args) throws InterruptedException {
        FlashSaleInventoryManager manager = new FlashSaleInventoryManager();
        manager.addProduct("IPHONE15_256GB", 100);

        System.out.println("checkStock(\"IPHONE15_256GB\") -> " + manager.checkStock("IPHONE15_256GB") + " units available");
        System.out.println("purchaseItem(\"IPHONE15_256GB\", userId=12345) -> " + manager.purchaseItem("IPHONE15_256GB", 12345));
        System.out.println("purchaseItem(\"IPHONE15_256GB\", userId=67890) -> " + manager.purchaseItem("IPHONE15_256GB", 67890));

        int customers = 50_000;
        ExecutorService pool = Executors.newFixedThreadPool(64);
        CountDownLatch latch = new CountDownLatch(customers);

        long start = System.nanoTime();
        for (int i = 0; i < customers; i++) {
            final long userId = 10_000_000L + i;
            pool.submit(() -> {
                try {
                    manager.purchaseItem("IPHONE15_256GB", userId);
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        long end = System.nanoTime();
        pool.shutdown();

        System.out.println();
        System.out.println("Final stock -> " + manager.checkStock("IPHONE15_256GB"));
        System.out.println("Successful purchases -> " + manager.getSuccessfulPurchases("IPHONE15_256GB"));
        System.out.println("Waiting list size -> " + manager.getWaitingListSize("IPHONE15_256GB"));
        System.out.printf("Time for %%,d purchase attempts: %%.2f ms%n", customers, (end - start) / 1_000_000.0);

        System.out.println("purchaseItem(\"IPHONE15_256GB\", userId=99999) -> " + manager.purchaseItem("IPHONE15_256GB", 99999));
    }

    static final class FlashSaleInventoryManager {

        private final ConcurrentHashMap<String, AtomicInteger> stockByProduct = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, ConcurrentLinkedQueue<Long>> waitingListByProduct = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, LongAdder> successfulPurchasesByProduct = new ConcurrentHashMap<>();

        public void addProduct(String productId, int initialStock) {
            requireProductId(productId);
            requireNonNegative(initialStock);

            stockByProduct.put(productId, new AtomicInteger(initialStock));
            waitingListByProduct.putIfAbsent(productId, new ConcurrentLinkedQueue<>());
            successfulPurchasesByProduct.putIfAbsent(productId, new LongAdder());
        }

        public int checkStock(String productId) {
            AtomicInteger stock = stockByProduct.get(productId);
            return stock == null ? 0 : Math.max(0, stock.get());
        }

        public String purchaseItem(String productId, long userId) {
            AtomicInteger stock = stockByProduct.get(productId);
            if (stock == null) {
                return "Product not found";
            }

            while (true) {
                int current = stock.get();
                if (current <= 0) {
                    Queue<Long> q = waitingListByProduct.computeIfAbsent(productId, k -> new ConcurrentLinkedQueue<>());
                    q.add(userId);
                    return "Added to waiting list, position #" + q.size();
                }

                if (stock.compareAndSet(current, current - 1)) {
                    successfulPurchasesByProduct.computeIfAbsent(productId, k -> new LongAdder()).increment();
                    return "Success, " + (current - 1) + " units remaining";
                }
            }
        }

        public void restock(String productId, int units) {
            if (units <= 0) return;

            AtomicInteger stock = stockByProduct.get(productId);
            if (stock == null) return;

            ConcurrentLinkedQueue<Long> q = waitingListByProduct.computeIfAbsent(productId, k -> new ConcurrentLinkedQueue<>());

            int remaining = units;
            while (remaining > 0) {
                Long next = q.poll();
                if (next == null) break;
                successfulPurchasesByProduct.computeIfAbsent(productId, k -> new LongAdder()).increment();
                remaining--;
            }

            if (remaining > 0) {
                stock.addAndGet(remaining);
            }
        }

        public int getWaitingListSize(String productId) {
            Queue<Long> q = waitingListByProduct.get(productId);
            return q == null ? 0 : q.size();
        }

        public long getSuccessfulPurchases(String productId) {
            LongAdder adder = successfulPurchasesByProduct.get(productId);
            return adder == null ? 0L : adder.sum();
        }

        private static void requireProductId(String productId) {
            if (productId == null || productId.isBlank()) {
                throw new IllegalArgumentException("productId is required");
            }
        }

        private static void requireNonNegative(int value) {
            if (value < 0) {
                throw new IllegalArgumentException("value must be >= 0");
            }
        }
    }
}