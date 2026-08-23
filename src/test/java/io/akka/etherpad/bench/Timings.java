package io.akka.etherpad.bench;

import io.akka.etherpad.domain.Changeset;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.IntSupplier;

/**
 * The port's half of the benchmark's timing and same-answers check — mirrors
 * {@code bench/run_source.mjs}'s two workloads (bench/workloads.json) exactly, so the
 * two sides answer the same question. Measuring instrument, not a check: it asserts
 * nothing and writes files. {@code main}, not {@code @Test}, for the same reason
 * glance-akka's Timings is — a class that only runs when named on the command line
 * should not read as a passed test in a build's totals.
 *
 * <pre>
 * mvn -o test-compile
 * mvn -o dependency:build-classpath -Dmdep.outputFile=target/test-cp.txt -Dmdep.includeScope=test
 * java -cp "target/test-classes;target/classes;$(cat target/test-cp.txt)" io.akka.etherpad.bench.Timings
 * </pre>
 */
public final class Timings {

  private static final int WINDOWS = 7;
  private static final long TARGET_WINDOW_NANOS = 20_000_000L;

  private record Edit(int start, int ndel, String ins) {}

  private record Case(String name, String base, Edit editA, Edit editB) {}

  private static String converge(Case c) {
    String a = Changeset.makeSplice(c.base(), c.editA().start(), c.editA().ndel(), c.editA().ins());
    String b = Changeset.makeSplice(c.base(), c.editB().start(), c.editB().ndel(), c.editB().ins());
    String afb = Changeset.follow(a, b, false);
    String bfa = Changeset.follow(b, a, true);
    String mergedFromA = Changeset.applyToText(Changeset.compose(a, afb), c.base());
    String mergedFromB = Changeset.applyToText(Changeset.compose(b, bfa), c.base());
    if (!mergedFromA.equals(mergedFromB)) {
      throw new IllegalStateException("did not converge: " + mergedFromA + " / " + mergedFromB);
    }
    return mergedFromA;
  }

  public static void main(String[] args) throws IOException {
    new Timings().run();
  }

  void run() throws IOException {
    var cases =
        List.of(
            new Case(
                "non-overlapping-concurrent-inserts",
                "hello world\n",
                new Edit(5, 0, "!!"),
                new Edit(6, 0, "big ")),
            new Case(
                "same-span-conflict",
                "hello world\n",
                new Edit(6, 5, "earth"),
                new Edit(6, 5, "moon!")));

    var answers = new LinkedHashMap<String, String>();
    for (var c : cases) {
      answers.put(c.name(), converge(c));
    }
    writeAnswers(answers);
    writeTimings(cases);
  }

  private void writeAnswers(LinkedHashMap<String, String> answers) throws IOException {
    var json = new StringBuilder("{\n");
    var first = true;
    for (var entry : answers.entrySet()) {
      if (!first) json.append(",\n");
      first = false;
      json.append("  \"").append(entry.getKey()).append("\": ")
          .append(quote(entry.getValue()));
    }
    json.append("\n}\n");
    var out = Path.of("..", "etherpad-port", "bench", "port-answers.json");
    Files.writeString(out, json.toString());
    System.out.println("wrote " + out.toAbsolutePath().normalize() + "\n" + json);
  }

  private static String quote(String s) {
    return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
  }

  private void writeTimings(List<Case> cases) throws IOException {
    // Cycle over both real workloads so no call is loop-invariant (a JIT can hoist and
    // fold a call whose arguments never change) and the result is read into a sink so
    // neither call can be proven dead and deleted.
    IntSupplier run =
        () -> {
          var c = cases.get((int) (System.nanoTime() % cases.size()));
          return converge(c).length();
        };

    for (var i = 0; i < 20_000; i++) run.getAsInt();

    var pilotRuns = 2_000;
    var pilotNanos = 0L;
    for (var attempt = 0; attempt < 8 && pilotNanos <= 0; attempt++) {
      var pilotStart = System.nanoTime();
      for (var i = 0; i < pilotRuns; i++) run.getAsInt();
      pilotNanos = System.nanoTime() - pilotStart;
      if (pilotNanos <= 0) pilotRuns *= 10;
    }
    if (pilotNanos <= 0) {
      throw new IllegalStateException("the pilot measured nothing");
    }
    var perRun = (double) pilotNanos / pilotRuns;
    var repetitions = (int) Math.max(1000, Math.ceil(TARGET_WINDOW_NANOS / perRun));

    var readings = new ArrayList<Long>();
    for (var window = 0; window < WINDOWS; window++) {
      var started = System.nanoTime();
      var sink = 0;
      for (var i = 0; i < repetitions; i++) sink += run.getAsInt();
      var elapsed = System.nanoTime() - started;
      if (sink == Integer.MIN_VALUE) throw new IllegalStateException("unreachable");
      readings.add(elapsed);
    }
    readings.sort(Long::compare);
    var median = readings.get(readings.size() / 2);
    var nsPerOp = (double) median / repetitions;

    var json = String.format(
        "{\n  \"runtime\": \"java %s\",\n  \"timing\": {\n    "
            + "\"convergence-cycling-both-workloads\": {\"repetitions\": %d, \"windows\": %d, "
            + "\"windowNanos\": %d, \"nanosPerRun\": %.1f}\n  }\n}\n",
        System.getProperty("java.version"), repetitions, WINDOWS, median, nsPerOp);
    var out = Path.of("..", "etherpad-port", "bench", "port-timings.json");
    Files.writeString(out, json);
    System.out.println("wrote " + out.toAbsolutePath().normalize() + "\n" + json);
  }
}
