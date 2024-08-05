package kg.smile;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

public class CrptApi {
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Semaphore requestSemaphore;
    private final ReentrantLock requestLock;
    private final Queue<Long> requestTimestamps;
    private final long timeIntervalMillis;

    public CrptApi(TimeUnit timeUnit, int requestLimit) {
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = new ObjectMapper();
        this.requestSemaphore = new Semaphore(requestLimit);
        this.requestLock = new ReentrantLock();
        this.requestTimestamps = new LinkedList<>();
        this.timeIntervalMillis = timeUnit.toMillis(1);
        Executors.newScheduledThreadPool(1).scheduleAtFixedRate(this::cleanupOldRequests, 1, 1, timeUnit);
    }

    public void createDocument(Object document, String signature) throws IOException, InterruptedException {
        if (!isRequestAllowed()) {
            Thread.sleep(timeIntervalMillis);
        }

        addRequestTimestamp();

        var jsonDocument = objectMapper.writeValueAsString(document);
        var request = HttpRequest.newBuilder()
                .uri(URI.create("https://ismp.crpt.ru/api/v3/lk/documents/create"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonDocument))
                .build();

        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        System.out.println(response.toString());
    }

    private void cleanupOldRequests() {
        long currentTimeMillis = System.currentTimeMillis();
        requestLock.lock();
        try {
            while (!requestTimestamps.isEmpty() && currentTimeMillis - requestTimestamps.peek() >= timeIntervalMillis) {
                requestTimestamps.poll();
            }
        } finally {
            requestLock.unlock();
        }
    }

    private void addRequestTimestamp() {
        requestLock.lock();
        try {
            requestTimestamps.add(System.currentTimeMillis());
        } finally {
            requestLock.unlock();
        }
    }

    private boolean isRequestAllowed() {
        cleanupOldRequests();
        requestLock.lock();
        try {
            return requestTimestamps.size() < requestSemaphore.availablePermits();
        } finally {
            requestLock.unlock();
        }
    }
}
