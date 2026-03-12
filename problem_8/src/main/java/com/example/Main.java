package com.example;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Locale;

public class Main {

    public static void main(String[] args) throws Exception {
        ParkingLot lot = new ParkingLot(500, 2.50); // $2.50 per hour

        ParkResult r1 = lot.parkVehicle("ABC-1234");
        System.out.println(r1);

        ParkResult r2 = lot.parkVehicle("ABC-1235");
        System.out.println(r2);

        ParkResult r3 = lot.parkVehicle("XYZ-9999");
        System.out.println(r3);

        System.out.println("Nearest available spot to entrance: #" + lot.findNearestAvailableSpot());

        Thread.sleep(1200);

        ExitResult e1 = lot.exitVehicle("ABC-1234");
        System.out.println(e1);

        System.out.println(lot.getStatistics());
    }

    // ---------- API result types ----------

    static final class ParkResult {
        final boolean success;
        final String licensePlate;
        final int assignedSpot;
        final int probes;

        ParkResult(boolean success, String licensePlate, int assignedSpot, int probes) {
            this.success = success;
            this.licensePlate = licensePlate;
            this.assignedSpot = assignedSpot;
            this.probes = probes;
        }

        @Override
        public String toString() {
            if (!success) return "parkVehicle(\"" + licensePlate + "\") -> FAILED (lot full or already parked)";
            return "parkVehicle(\"" + licensePlate + "\") -> Assigned spot #" + assignedSpot + " (" + probes + " probes)";
        }
    }

    static final class ExitResult {
        final boolean success;
        final String licensePlate;
        final int spot;
        final Duration duration;
        final double fee;

        ExitResult(boolean success, String licensePlate, int spot, Duration duration, double fee) {
            this.success = success;
            this.licensePlate = licensePlate;
            this.spot = spot;
            this.duration = duration;
            this.fee = fee;
        }

        @Override
        public String toString() {
            if (!success) return "exitVehicle(\"" + licensePlate + "\") -> FAILED (not found)";
            return "exitVehicle(\"" + licensePlate + "\") -> Spot #" + spot + " freed, Duration: " +
                    formatDuration(duration) + ", Fee: $" + String.format(Locale.US, "%.2f", fee);
        }

        private static String formatDuration(Duration d) {
            long minutes = d.toMinutes();
            long h = minutes / 60;
            long m = minutes % 60;
            return h + "h " + m + "m";
        }
    }

    static final class ParkingStatistics {
        final int capacity;
        final int occupied;
        final double occupancyPercent;

        final double avgProbes;
        final int totalParks;

        final int peakHourStart;
        final int peakHourEnd;
        final int peakHourEntries;

        ParkingStatistics(int capacity,
                          int occupied,
                          double occupancyPercent,
                          double avgProbes,
                          int totalParks,
                          int peakHourStart,
                          int peakHourEnd,
                          int peakHourEntries) {
            this.capacity = capacity;
            this.occupied = occupied;
            this.occupancyPercent = occupancyPercent;
            this.avgProbes = avgProbes;
            this.totalParks = totalParks;
            this.peakHourStart = peakHourStart;
            this.peakHourEnd = peakHourEnd;
            this.peakHourEntries = peakHourEntries;
        }

        @Override
        public String toString() {
            return "getStatistics() -> Occupancy: " +
                    String.format(Locale.US, "%.1f", occupancyPercent) + "% , " +
                    "Avg Probes: " + String.format(Locale.US, "%.2f", avgProbes) + ", " +
                    "Peak Hour: " + formatHour(peakHourStart) + "-" + formatHour(peakHourEnd) +
                    " (" + peakHourEntries + " entries)";
        }

        private static String formatHour(int hour24) {
            int h = hour24 % 24;
            int hour12 = h % 12;
            if (hour12 == 0) hour12 = 12;
            String ampm = (h < 12) ? "AM" : "PM";
            return hour12 + " " + ampm;
        }
    }

    // ---------- Parking lot implementation (open addressing) ----------

    static final class ParkingLot {

        enum Status {EMPTY, OCCUPIED, DELETED}

        static final class Slot {
            Status status = Status.EMPTY;
            String plate;
            long entryEpochMillis;
        }

        private final int capacity;
        private final double hourlyRate;

        private final Slot[] table;
        private int occupied;

        private long totalProbes;
        private int totalParks;

        private final int[] entriesByHour = new int[24];

        ParkingLot(int capacity, double hourlyRate) {
            if (capacity <= 0) throw new IllegalArgumentException("capacity must be > 0");
            if (hourlyRate < 0) throw new IllegalArgumentException("hourlyRate must be >= 0");
            this.capacity = capacity;
            this.hourlyRate = hourlyRate;

            this.table = new Slot[capacity];
            for (int i = 0; i < capacity; i++) table[i] = new Slot();
        }

        public synchronized ParkResult parkVehicle(String licensePlate) {
            String plate = normalizePlate(licensePlate);
            if (plate.isBlank()) return new ParkResult(false, licensePlate, -1, 0);

            if (occupied >= capacity) return new ParkResult(false, licensePlate, -1, 0);

            // prevent duplicates
            int existing = findSpotOf(plate);
            if (existing != -1) return new ParkResult(false, licensePlate, existing, 0);

            int preferred = hashPlate(plate) % capacity;

            int firstDeleted = -1;
            int probes = 0;

            for (int i = 0; i < capacity; i++) {
                int idx = (preferred + i) % capacity;
                probes++;

                Slot s = table[idx];
                if (s.status == Status.OCCUPIED) {
                    continue;
                }
                if (s.status == Status.DELETED && firstDeleted == -1) {
                    firstDeleted = idx;
                    continue;
                }
                if (s.status == Status.EMPTY) {
                    int use = (firstDeleted != -1) ? firstDeleted : idx;
                    occupy(use, plate);
                    recordEntry();
                    totalProbes += probes;
                    totalParks++;
                    return new ParkResult(true, licensePlate, use, probes - 1);
                }
            }

            // table might be full of OCCUPIED/DELETED, use a DELETED if we saw one
            if (firstDeleted != -1) {
                occupy(firstDeleted, plate);
                recordEntry();
                totalProbes += probes;
                totalParks++;
                return new ParkResult(true, licensePlate, firstDeleted, probes - 1);
            }

            return new ParkResult(false, licensePlate, -1, probes - 1);
        }

        public synchronized ExitResult exitVehicle(String licensePlate) {
            String plate = normalizePlate(licensePlate);
            int spot = findSpotOf(plate);
            if (spot == -1) return new ExitResult(false, licensePlate, -1, Duration.ZERO, 0.0);

            Slot s = table[spot];
            long exitMillis = System.currentTimeMillis();
            long entryMillis = s.entryEpochMillis;

            s.status = Status.DELETED;
            s.plate = null;
            s.entryEpochMillis = 0;

            occupied--;

            Duration dur = Duration.ofMillis(Math.max(0L, exitMillis - entryMillis));
            double fee = computeFee(dur);

            return new ExitResult(true, licensePlate, spot, dur, fee);
        }

        public synchronized int findNearestAvailableSpot() {
            for (int i = 0; i < capacity; i++) {
                Slot s = table[i];
                if (s.status == Status.EMPTY || s.status == Status.DELETED) return i;
            }
            return -1;
        }

        public synchronized ParkingStatistics getStatistics() {
            double occupancyPct = capacity == 0 ? 0.0 : (100.0 * occupied / capacity);
            double avgProbes = totalParks == 0 ? 0.0 : (totalProbes / (double) totalParks);

            int peakHour = 0;
            int peakCount = 0;
            for (int h = 0; h < 24; h++) {
                if (entriesByHour[h] > peakCount) {
                    peakCount = entriesByHour[h];
                    peakHour = h;
                }
            }

            return new ParkingStatistics(
                    capacity,
                    occupied,
                    occupancyPct,
                    avgProbes,
                    totalParks,
                    peakHour,
                    (peakHour + 1) % 24,
                    peakCount
            );
        }

        // ---------- internals ----------

        private void occupy(int idx, String plate) {
            Slot s = table[idx];
            s.status = Status.OCCUPIED;
            s.plate = plate;
            s.entryEpochMillis = System.currentTimeMillis();
            occupied++;
        }

        private int findSpotOf(String plate) {
            int preferred = hashPlate(plate) % capacity;

            for (int i = 0; i < capacity; i++) {
                int idx = (preferred + i) % capacity;
                Slot s = table[idx];

                if (s.status == Status.EMPTY) {
                    return -1;
                }
                if (s.status == Status.OCCUPIED && plate.equals(s.plate)) {
                    return idx;
                }
            }
            return -1;
        }

        private void recordEntry() {
            ZonedDateTime now = ZonedDateTime.ofInstant(Instant.ofEpochMilli(System.currentTimeMillis()), ZoneId.systemDefault());
            entriesByHour[now.getHour()]++;
        }

        private double computeFee(Duration d) {
            // bill per started hour (ceil)
            double hours = d.toMillis() / 3600000.0;
            long billableHours = (long) Math.ceil(Math.max(0.0, hours));
            if (billableHours == 0) billableHours = 1;
            return billableHours * hourlyRate;
        }

        private static String normalizePlate(String p) {
            if (p == null) return "";
            return p.trim().toUpperCase(Locale.US);
        }

        private static int hashPlate(String plate) {
            // simple but decent hash (polynomial rolling)
            int h = 0;
            for (int i = 0; i < plate.length(); i++) {
                h = 31 * h + plate.charAt(i);
            }
            return h & 0x7fffffff;
        }
    }
}