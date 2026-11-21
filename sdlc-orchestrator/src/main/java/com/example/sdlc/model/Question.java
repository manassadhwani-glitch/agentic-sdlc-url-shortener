package com.example.sdlc.model;

import com.example.sdlc.json.Json;
import com.example.sdlc.json.JsonWritable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A clarifying question raised by an agent because the requirement is ambiguous. */
public final class Question implements JsonWritable {

    public String id;
    public String stageId;
    public String text;
    public List<String> options = new ArrayList<>();
    public String answer;
    public String answeredBy;
    public String answeredAt;

    public boolean isOpen() {
        return answer == null;
    }

    @Override
    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("stageId", stageId);
        m.put("text", text);
        m.put("options", options);
        m.put("answer", answer);
        m.put("answeredBy", answeredBy);
        m.put("answeredAt", answeredAt);
        return m;
    }

    public static Question fromJson(Map<String, Object> m) {
        Question q = new Question();
        q.id = Json.str(m, "id");
        q.stageId = Json.str(m, "stageId");
        q.text = Json.str(m, "text");
        q.options = Json.strings(m.get("options"));
        q.answer = Json.str(m, "answer");
        q.answeredBy = Json.str(m, "answeredBy");
        q.answeredAt = Json.str(m, "answeredAt");
        return q;
    }
}
