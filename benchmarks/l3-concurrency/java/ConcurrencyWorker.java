import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

public class ConcurrencyWorker {

    private static final String TARGET_URL = "http://127.0.0.1:8085/";
    private static final int TOTAL_REQUESTS = 100;

    public static void main(String[] args) throws Exception {
        System.out.println("==================================================");
        System.out.println("Java 21 Concurrency Benchmark (100 reqs @ 200ms delay)");
        System.out.println("==================================================");

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        // 1. Fixed thread pool (10 OS threads)
        long timeFixed10 = runBenchmark("1. Fixed Thread Pool (10 threads)", () -> Executors.newFixedThreadPool(10), client);

        // 2. Virtual threads (Project Loom)
        long timeVirtual = runBenchmark("2. Virtual Threads (Loom)", Executors::newVirtualThreadPerTaskExecutor, client);

        // 3. Sabotage: Fixed thread pool (1 OS thread)
        long timeFixed1 = runBenchmark("3. Sabotage: Fixed Thread Pool (1 thread)", () -> Executors.newFixedThreadPool(1), client);

        System.out.println("\n--- Java Summary ---");
        System.out.printf("Fixed Pool (10):      %d ms (%.2f s)%n", timeFixed10, timeFixed10 / 1000.0);
        System.out.printf("Virtual Threads:      %d ms (%.2f s)%n", timeVirtual, timeVirtual / 1000.0);
        System.out.printf("Sabotage (Pool of 1): %d ms (%.2f s)%n", timeFixed1, timeFixed1 / 1000.0);
    }

    private static long runBenchmark(String label, ExecutorServiceFactory factory, HttpClient client) throws Exception {
        System.out.println("\nRunning: " + label + "...");
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(TARGET_URL))
                .GET()
                .build();

        long start = System.currentTimeMillis();

        try (ExecutorService executor = factory.create()) {
            List<Callable<Void>> tasks = new ArrayList<>(TOTAL_REQUESTS);
            for (int i = 0; i < TOTAL_REQUESTS; i++) {
                tasks.add(() -> {
                    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode() != 200) {
                        throw new RuntimeException("HTTP " + response.statusCode());
                    }
                    return null;
                });
            }
            List<Future<Void>> futures = executor.invokeAll(tasks);
            for (Future<Void> future : futures) {
                future.get();
            }
        }

        long elapsed = System.currentTimeMillis() - start;
        System.out.printf("  Completed in: %d ms (%.2f s)%n", elapsed, elapsed / 1000.0);
        return elapsed;
    }

    @FunctionalInterface
    interface ExecutorServiceFactory {
        ExecutorService create();
    }
}
