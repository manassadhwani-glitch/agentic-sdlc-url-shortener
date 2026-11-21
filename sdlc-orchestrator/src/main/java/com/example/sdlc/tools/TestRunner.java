package com.example.sdlc.tools;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Runs the build/test command in a workspace and parses JUnit (Surefire) XML reports.
 *
 * <p>The command comes from trusted run configuration (a human-authored scenario or a CLI flag), never from model
 * output. It runs with a hard timeout, and the process tree is killed on expiry.
 */
public final class TestRunner {

    private static final int OUTPUT_TAIL_CHARS = 4000;

    public record Result(String command, int exitCode, long durationMs, int tests, int failures, int errors, int skipped,
                         Set<String> passed, List<String> failed, String outputTail, boolean timedOut) {

        public boolean green() {
            return exitCode == 0 && failures == 0 && errors == 0 && !timedOut;
        }
    }

    public Result run(Path workspace, String command, Duration timeout) throws IOException, InterruptedException {
        long start = System.nanoTime();
        clearReports(workspace);
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        ProcessBuilder pb = windows ? new ProcessBuilder("cmd", "/c", command) : new ProcessBuilder("sh", "-c", command);
        pb.directory(workspace.toFile());
        pb.redirectErrorStream(true);
        Path log = Files.createTempFile("orch-test-", ".log");
        pb.redirectOutput(log.toFile());
        Process p = pb.start();
        boolean finished = p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            p.descendants().forEach(ProcessHandle::destroyForcibly);
            p.destroyForcibly();
        }
        int exit = finished ? p.exitValue() : -1;
        String out = Files.readString(log, StandardCharsets.UTF_8);
        Files.deleteIfExists(log);
        String tail = out.length() > OUTPUT_TAIL_CHARS ? out.substring(out.length() - OUTPUT_TAIL_CHARS) : out;

        Set<String> passed = new LinkedHashSet<>();
        List<String> failed = new ArrayList<>();
        int[] counts = new int[4]; // tests, failures, errors, skipped
        parseReports(workspace, passed, failed, counts);
        return new Result(command, exit, (System.nanoTime() - start) / 1_000_000, counts[0], counts[1], counts[2], counts[3],
                passed, failed, tail, !finished);
    }

    private static void clearReports(Path workspace) throws IOException {
        for (Path dir : reportDirs(workspace)) {
            try (Stream<Path> s = Files.list(dir)) {
                for (Path f : s.toList()) {
                    Files.deleteIfExists(f);
                }
            }
        }
    }

    private static List<Path> reportDirs(Path workspace) throws IOException {
        if (!Files.isDirectory(workspace)) {
            return List.of();
        }
        try (Stream<Path> s = Files.walk(workspace, 4)) {
            return s.filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().equals("surefire-reports")).toList();
        }
    }

    static void parseReports(Path workspace, Set<String> passed, List<String> failed, int[] counts) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            // Hardening against XXE: reports are build output, but treat them as untrusted input anyway.
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setExpandEntityReferences(false);
            DocumentBuilder b = f.newDocumentBuilder();
            for (Path dir : reportDirs(workspace)) {
                try (Stream<Path> s = Files.list(dir)) {
                    for (Path xml : s.filter(x -> x.getFileName().toString().matches("TEST-.*\\.xml")).toList()) {
                        Document doc = b.parse(xml.toFile());
                        NodeList cases = doc.getElementsByTagName("testcase");
                        for (int i = 0; i < cases.getLength(); i++) {
                            Element tc = (Element) cases.item(i);
                            String cls = tc.getAttribute("classname");
                            String simple = cls.substring(cls.lastIndexOf('.') + 1);
                            String name = tc.getAttribute("name").replaceAll("[(\\[].*$", "").trim();
                            String id = simple + "#" + name;
                            counts[0]++;
                            if (tc.getElementsByTagName("failure").getLength() > 0) {
                                counts[1]++;
                                failed.add(id + ": " + firstMessage(tc, "failure"));
                            } else if (tc.getElementsByTagName("error").getLength() > 0) {
                                counts[2]++;
                                failed.add(id + ": " + firstMessage(tc, "error"));
                            } else if (tc.getElementsByTagName("skipped").getLength() > 0) {
                                counts[3]++;
                            } else {
                                passed.add(id);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            failed.add("report-parser: " + e.getMessage());
        }
    }

    private static String firstMessage(Element tc, String tag) {
        Element e = (Element) tc.getElementsByTagName(tag).item(0);
        String msg = e.getAttribute("message");
        if (msg == null || msg.isBlank()) {
            msg = e.getTextContent();
        }
        msg = msg == null ? "" : msg.strip().replace('\n', ' ');
        return msg.length() > 240 ? msg.substring(0, 240) + "…" : msg;
    }
}
