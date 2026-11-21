package com.example.sdlc.agents;

import com.example.sdlc.json.Json;
import com.example.sdlc.model.FileChange;

import java.util.List;
import java.util.Map;

/** Writes a change note into {@code docs/changes/} and a CHANGELOG entry, derived from spec, design and test evidence. */
public final class DocsAgent implements Agent {

    @Override
    public String name() {
        return "docs";
    }

    @Override
    public AgentResult run(AgentContext ctx) {
        AgentResult result = new AgentResult();
        Map<String, Object> spec = ctx.inputJson("spec");
        Map<String, Object> report = ctx.inputJson("test-report");
        String id = Json.str(spec, "id");
        StringBuilder note = new StringBuilder();
        note.append("# ").append(id).append(": ").append(Json.str(spec, "summary")).append("\n\n");
        note.append("## Acceptance criteria and evidence\n\n| AC | Criterion | Verified by |\n|---|---|---|\n");
        for (Object o : Json.list(spec.get("acceptanceCriteria"))) {
            Map<String, Object> ac = Json.obj(o);
            note.append("| ").append(Json.str(ac, "id")).append(" | ").append(Json.str(ac, "text")).append(" | ")
                    .append(String.join(", ", Json.strings(ac.get("verifiedBy")))).append(" |\n");
        }
        note.append("\nTest run: ").append(report.get("tests")).append(" tests, ")
                .append(((Number) report.get("failures")).longValue() + ((Number) report.get("errors")).longValue())
                .append(" failing (`").append(Json.str(report, "command")).append("`).\n");
        note.append("\n## Assumptions\n");
        for (String a : Json.strings(spec.get("assumptions"))) {
            note.append("- ").append(a).append('\n');
        }
        note.append("\n## Out of scope\n");
        for (String a : Json.strings(spec.get("outOfScope"))) {
            note.append("- ").append(a).append('\n');
        }
        note.append("\n## Design summary\n\nSee the design artifact for decisions and impacted modules.\n");
        String notePath = "docs/changes/" + id + ".md";
        result.output("docs", note.toString());

        String changelogEntry = "## " + id + "\n- " + Json.str(spec, "summary") + "\n- Features: "
                + String.join(", ", Json.strings(spec.get("features"))) + "\n\n";
        String existing = ctx.workspace().read("CHANGELOG.md");
        FileChange changelog = new FileChange();
        changelog.path = "CHANGELOG.md";
        changelog.op = existing == null ? FileChange.Op.CREATE : FileChange.Op.REPLACE;
        changelog.content = existing == null ? "# Changelog\n\n" + changelogEntry
                : existing.replaceFirst("# Changelog\\s*\\n", "# Changelog\n\n" + changelogEntry.replace("$", "\\$"));
        FileChange doc = new FileChange();
        doc.path = notePath;
        doc.op = ctx.workspace().exists(notePath) ? FileChange.Op.REPLACE : FileChange.Op.CREATE;
        doc.content = note.toString();
        result.changes.add(doc);
        result.changes.add(changelog);
        result.decision("Docs: " + notePath + " + CHANGELOG entry", "docs generated from verified evidence, not from intent", List.of());
        return result;
    }
}
