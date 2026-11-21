package com.example.sdlc.workflow;

import com.example.sdlc.model.StageDef;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Static checks before anything runs (and again after every dynamic re-plan). A workflow that fails validation
 * never starts: the "entry gate" for the whole run.
 */
public final class WorkflowValidator {

    private final Set<String> knownAgents;
    private final Set<String> knownGates;

    public WorkflowValidator(Set<String> knownAgents, Set<String> knownGates) {
        this.knownAgents = knownAgents;
        this.knownGates = knownGates;
    }

    public List<String> validate(List<StageDef> stages) {
        List<String> errors = new ArrayList<>();
        Map<String, StageDef> byId = new LinkedHashMap<>();
        for (StageDef s : stages) {
            if (s.id == null || !s.id.matches("[a-z][a-z0-9-]*")) {
                errors.add("invalid stage id: " + s.id);
                continue;
            }
            if (byId.put(s.id, s) != null) {
                errors.add("duplicate stage id: " + s.id);
            }
        }
        for (StageDef s : byId.values()) {
            if (!knownAgents.contains(s.agent)) {
                errors.add(s.id + ": unknown agent '" + s.agent + "'");
            }
            for (String d : s.dependsOn) {
                if (!byId.containsKey(d)) {
                    errors.add(s.id + ": depends on unknown stage '" + d + "'");
                }
            }
            for (String g : concat(s.entryGates, s.exitGates)) {
                if (!knownGates.contains(g)) {
                    errors.add(s.id + ": unknown gate '" + g + "'");
                }
            }
            if (s.maxAttempts < 1 || s.maxAttempts > 5) {
                errors.add(s.id + ": maxAttempts must be 1..5 (bounded retries)");
            }
            if (s.mutatesWorkspace && s.writeScope.isEmpty()) {
                errors.add(s.id + ": mutating stage must declare a writeScope");
            }
            if (s.requiresSignoff() && (s.approvalRole == null || s.approvalRole.isBlank())) {
                errors.add(s.id + ": sign-off stage must declare approvalRole");
            }
            if (s.onReject != null && !s.onReject.equals(s.id) && !ancestors(s.id, byId).contains(s.onReject)) {
                errors.add(s.id + ": onReject must be the stage itself or an upstream stage");
            }
        }
        if (errors.isEmpty()) {
            List<String> cycle = findCycle(byId);
            if (!cycle.isEmpty()) {
                errors.add("cycle detected: " + String.join(" -> ", cycle));
            }
        }
        return errors;
    }

    /** Topological layers: stages within one wave can run in parallel. */
    public static List<List<String>> waves(List<StageDef> stages) {
        Map<String, Integer> indegree = new HashMap<>();
        Map<String, List<String>> children = new HashMap<>();
        for (StageDef s : stages) {
            indegree.putIfAbsent(s.id, 0);
            for (String d : s.dependsOn) {
                indegree.merge(s.id, 1, Integer::sum);
                children.computeIfAbsent(d, k -> new ArrayList<>()).add(s.id);
            }
        }
        List<List<String>> waves = new ArrayList<>();
        List<String> current = new ArrayList<>();
        for (StageDef s : stages) {
            if (indegree.get(s.id) == 0) {
                current.add(s.id);
            }
        }
        while (!current.isEmpty()) {
            waves.add(current);
            List<String> next = new ArrayList<>();
            for (String id : current) {
                for (String c : children.getOrDefault(id, List.of())) {
                    if (indegree.merge(c, -1, Integer::sum) == 0) {
                        next.add(c);
                    }
                }
            }
            current = next;
        }
        return waves;
    }

    /** All transitive downstream stages of {@code id}. */
    public static Set<String> descendants(String id, List<StageDef> stages) {
        Set<String> out = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>(List.of(id));
        while (!queue.isEmpty()) {
            String cur = queue.poll();
            for (StageDef s : stages) {
                if (s.dependsOn.contains(cur) && out.add(s.id)) {
                    queue.add(s.id);
                }
            }
        }
        return out;
    }

    private static Set<String> ancestors(String id, Map<String, StageDef> byId) {
        Set<String> out = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>(List.of(id));
        while (!queue.isEmpty()) {
            StageDef s = byId.get(queue.poll());
            if (s == null) {
                continue;
            }
            for (String d : s.dependsOn) {
                if (out.add(d)) {
                    queue.add(d);
                }
            }
        }
        return out;
    }

    private static List<String> findCycle(Map<String, StageDef> byId) {
        Map<String, Integer> color = new HashMap<>(); // 0 white, 1 grey, 2 black
        Deque<String> path = new ArrayDeque<>();
        for (String id : byId.keySet()) {
            List<String> c = dfs(id, byId, color, path);
            if (!c.isEmpty()) {
                return c;
            }
        }
        return List.of();
    }

    private static List<String> dfs(String id, Map<String, StageDef> byId, Map<String, Integer> color, Deque<String> path) {
        int c = color.getOrDefault(id, 0);
        if (c == 2) {
            return List.of();
        }
        if (c == 1) {
            List<String> cycle = new ArrayList<>();
            boolean on = false;
            for (String p : path) {
                if (p.equals(id)) {
                    on = true;
                }
                if (on) {
                    cycle.add(p);
                }
            }
            cycle.add(id);
            return cycle;
        }
        color.put(id, 1);
        path.addLast(id);
        for (String d : byId.get(id).dependsOn) {
            List<String> r = dfs(d, byId, color, path);
            if (!r.isEmpty()) {
                return r;
            }
        }
        path.removeLast();
        color.put(id, 2);
        return List.of();
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }
}
