package com.example.sdlc.workflow;

import com.example.sdlc.json.Json;
import com.example.sdlc.model.StageDef;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Loads a workflow definition (JSON) from a file path or from the classpath ({@code /workflows/<name>.json}). */
public final class WorkflowLoader {

    private WorkflowLoader() {
    }

    public static List<StageDef> load(String nameOrPath) {
        String text;
        Path p = Path.of(nameOrPath);
        try {
            if (Files.isRegularFile(p)) {
                text = Files.readString(p, StandardCharsets.UTF_8);
            } else {
                try (InputStream in = WorkflowLoader.class.getResourceAsStream("/workflows/" + nameOrPath + ".json")) {
                    if (in == null) {
                        throw new IllegalArgumentException("workflow not found: " + nameOrPath);
                    }
                    text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Map<String, Object> doc = Json.parseObject(text);
        List<StageDef> stages = new ArrayList<>();
        for (Object o : Json.list(doc.get("stages"))) {
            stages.add(StageDef.fromJson(Json.obj(o)));
        }
        return stages;
    }
}
