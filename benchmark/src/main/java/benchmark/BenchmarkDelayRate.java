package benchmark;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

public class BenchmarkDelayRate extends BenchmarkBase implements Benchmark<RateInputParameters> {

    private final String baseFolder = "results/" + getClassName() + "-" + DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").format(LocalDateTime.now());
    protected final boolean writeResults;

    public BenchmarkDelayRate(String host, boolean writeAllResults) {
        super(host);
        this.writeResults = writeAllResults;
    }

    public BenchmarkDelayRate() {
        this("localhost", false);
    }

    @Override
    public File getBaseFolder() {
        return new File(baseFolder);
    }

    public Map<String, Results> runAll(Duration testDuration, int numberOfClients, double requestPerSecond) throws Exception {
        measureBaseline();
        Map<String, Results> results = new HashMap<>();
        for (var proc : super.variations) {
            Results r = run(proc, testDuration, numberOfClients, requestPerSecond);
            System.out.println(r);
            results.put(proc.getName(), r);
        }
        return results;
    }

    public Results run(String procName, Duration testDuration, int numberOfClients, double requestPerSecond) throws IOException, InterruptedException {
        for (var proc : variations) {
            if (proc.getName().equals(procName)) {
                return run(proc, testDuration, numberOfClients, requestPerSecond);
            }
        }
        return null;
    }


    public Results run(ServerProcess process, Duration testDuration, int numberOfClients, double requestPerSecond) throws IOException, InterruptedException {
        if (super.baselineEnergyUJoules == 0) {
            measureBaseline();
        }
        double expected = numberOfClients * testDuration.toSeconds() * requestPerSecond;
        System.out.printf("Starting %s with %d clients and %.2f reqs/sec for %ds. Expected requests: %.2f %n", process.getName(), numberOfClients, requestPerSecond, testDuration.toSeconds(), expected);
        int maxThreads = 32;
        int minThreads = (int) Math.min(numberOfClients, numberOfClients * requestPerSecond);
        ScheduledExecutorService pool = Executors.newScheduledThreadPool(Math.min(maxThreads, minThreads));
        FileWriter writer;
        FileWriter toClose = null;
        try (var ignored = getDatabaseProcess().start(); var ignored2 = process.start()) {
            if (writeResults) {
                File f = getBaseFolder();
                if (!f.exists() && !f.mkdirs()) {
                    throw new RuntimeException("could not create folder: " + f);
                }
                writer = new FileWriter(new File(f, "%s-%ds-%d-%.2f.txt".formatted(process.getName(), testDuration.toSeconds(), numberOfClients, requestPerSecond)));
                toClose = writer;
            } else {
                writer = null;
            }
            List<ScheduledFuture<?>> futures = new ArrayList<>();
            RequestMaker maker = newRequestMaker(process);
            CPUSnapshot snap = cpuSnapshot();
            long startJoules = maker.energyMeasureMicroJoules();

            long period = (long) (1000 / requestPerSecond);
            long t0 = System.nanoTime();
            List<Duration> latencies = new CopyOnWriteArrayList<>();
            long deadline = System.nanoTime() + testDuration.toNanos();
            for (int i = 0; i < numberOfClients; i++) {
                ScheduledFuture<?> fut = scheduleTask(pool, writer, maker, period, latencies, deadline);
                futures.add(fut);
            }
            TimeUnit.SECONDS.sleep(testDuration.toSeconds());
            for (var fut : futures) {
                fut.cancel(false);
//                latencies.addAll(fut.get());
            }
            for (var fut : futures) {
                try {
                    fut.get();
                } catch (CancellationException ignored1) {
                }
            }
            long totalEnergy = maker.energyMeasureMicroJoules() - startJoules;
            long totalTime = System.nanoTime() - t0;
            Results results = new Results(process.getName(), latencies, totalEnergy, Duration.ofNanos(totalTime), cpuSnapshot().diffFrom(snap)).subtractBaseline(baselineEnergyUJoules, baselineMeasureDuration);
            System.out.printf("Finished %s with %d requests and results: %s%n", process.getName(), latencies.size(), results);

            return results;
        } catch (ExecutionException e) {
            throw new RuntimeException(e.getCause());
        } finally {
            pool.shutdownNow();
            if (toClose != null) toClose.close();
        }
    }


    protected ScheduledFuture<?> scheduleTask(ScheduledExecutorService pool, FileWriter writer, RequestMaker maker, long period, List<Duration> latencies, long deadline) {
        return pool.scheduleWithFixedDelay(() -> {
            if (System.nanoTime() <= deadline) {
                try {
                    Duration e = maker.makeRequest();
                    if (System.nanoTime() <= deadline) {
                        latencies.add(e);
                        if (writer != null) writer.write(e.toMillis() + "\n");
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        }, 0, period, TimeUnit.MILLISECONDS);
    }


    protected List<Duration> requestLoop(RequestMaker maker, long deadlineMillis, long periodMillis) throws IOException, InterruptedException {
        List<Duration> list = new ArrayList<>();
        while (System.currentTimeMillis() < deadlineMillis) {
            long nextDeadline = System.currentTimeMillis() + periodMillis;
            Duration e = maker.makeRequest();
            list.add(e);
            long pause = System.currentTimeMillis() - nextDeadline;
            if (pause > 0) {
                Thread.sleep(pause);
            }
        }
        return list;
    }

    @Override
    public Results run(RateInputParameters input) {
        try {
            return run(input.imageName(), input.testDuration(), input.clients(), input.requestsPerSecond());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        BenchmarkDelayRate rate = new BenchmarkDelayRate();
//        rate.disableBaseline();
//        rate.redirectOutputs();
        String[] procNames = {"pythonjude", "javaquarkus"};
        Duration testDuration = Duration.ofSeconds(120);
        List<RateInputParameters> list = new ArrayList<>();
        for (var proc : procNames) {
            int numberOfClients = 8;
            double reqsPerSecond = 1;
            while (numberOfClients > 0) {
                list.add(new RateInputParameters(proc, testDuration, numberOfClients, reqsPerSecond));
                numberOfClients /= 2;
                reqsPerSecond *= 2.0;
            }
        }
        Collections.reverse(list);
        String output = rate.runAllWriteResults(list);
        System.out.println(output);
    }

}
