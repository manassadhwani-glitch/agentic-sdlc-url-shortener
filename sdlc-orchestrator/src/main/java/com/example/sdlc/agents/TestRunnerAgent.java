package com.example.sdlc.agents;

import com.example.sdlc.json.Json;
import com.example.sdlc.tools.TestRunner;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs the real build and test suite in the workspace and reports the results as data. Pass/fail is decided by
 * the {@code tests-pass} and {@code ac-traceability} gates, not by this agent.
 */
public final class TestRunnerAgent implements Agent {

    @Override
    public String name() {
        return "test-runner";
    }

    @Override
    public AgentResult run(AgentContext ctx) throws Exception {
        AgentResult result = new AgentResult();
        String command = String.valueOf(ctx.config().getOrDefault("testCommand", "mvn -q -B test"));
        long timeoutSec = ((Number) ctx.config().getOrDefault("testTimeoutSeconds", 900L)).longValue();
        TestRunner.Result r = ctx.testRunner().run(ctx.workspace().root(), command, Duration.ofSeconds(timeoutSec));
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("command", r.command());
        report.put("exitCode", r.exitCode());
        report.put("timedOut", r.timedOut());
        report.put("durationMs", r.durationMs());
        report.put("tests", r.tests());
        report.put("failures", r.failures());
        report.put("errors", r.errors());
        report.put("skipped", r.skipped());
        report.put("failed", r.failed());
        report.put("passed", new ArrayList<>(r.passed()));
        report.put("outputTail", r.green() ? "" : r.outputTail());
        result.output("test-report", Json.pretty(report));
        result.decision("Ran test suite: " + r.tests() + " tests, " + (r.failures() + r.errors()) + " failing",
                "real execution in the workspace; results are evidence for the gates", List.<String>of());
        return result;
    }
}
