package com.example.sdlc.model;

import com.example.sdlc.json.Json;
import com.example.sdlc.json.JsonWritable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One proposed file change. Agents never write files directly. They propose changes, the policy engine reviews
 * them, and GuardedWorkspace applies them with a journal.
 *
 * <p>Ops: CREATE (new file, must not exist), REPLACE (whole content), EDIT (exact find→replace pairs, each find
 * must match exactly once, so edits are unambiguous and reviewable), DELETE.
 */
public final class FileChange implements JsonWritable {

    public enum Op { CREATE, REPLACE, EDIT, DELETE }

    public String path;
    public Op op;
    public String content;
    public List<String[]> edits = new ArrayList<>();

    public static FileChange create(String path, String content) {
        FileChange c = new FileChange();
        c.path = path;
        c.op = Op.CREATE;
        c.content = content;
        return c;
    }

    public static FileChange edit(String path, String find, String replace) {
        FileChange c = new FileChange();
        c.path = path;
        c.op = Op.EDIT;
        c.edits.add(new String[]{find, replace});
        return c;
    }

    /** Lines added (approximate diff size for change-control policy). */
    public int addedLines() {
        if (op == Op.CREATE || op == Op.REPLACE) {
            return content == null ? 0 : (int) content.lines().count();
        }
        int n = 0;
        for (String[] e : edits) {
            n += (int) e[1].lines().count();
        }
        return n;
    }

    /** All new text this change introduces (what policies scan). */
    public String introducedText() {
        if (op == Op.CREATE || op == Op.REPLACE) {
            return content == null ? "" : content;
        }
        StringBuilder sb = new StringBuilder();
        for (String[] e : edits) {
            sb.append(e[1]).append('\n');
        }
        return sb.toString();
    }

    @Override
    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", path);
        m.put("op", op);
        if (content != null) {
            m.put("content", content);
        }
        if (!edits.isEmpty()) {
            List<Object> list = new ArrayList<>();
            for (String[] e : edits) {
                Map<String, Object> pair = new LinkedHashMap<>();
                pair.put("find", e[0]);
                pair.put("replace", e[1]);
                list.add(pair);
            }
            m.put("edits", list);
        }
        return m;
    }

    public static FileChange fromJson(Map<String, Object> m) {
        FileChange c = new FileChange();
        c.path = Json.str(m, "path");
        c.op = Op.valueOf(Json.str(m, "op", "REPLACE").toUpperCase());
        c.content = Json.str(m, "content");
        for (Object o : Json.list(m.get("edits"))) {
            Map<String, Object> e = Json.obj(o);
            c.edits.add(new String[]{Json.str(e, "find", ""), Json.str(e, "replace", "")});
        }
        return c;
    }

    public static List<FileChange> listFromJson(Object o) {
        List<FileChange> out = new ArrayList<>();
        for (Object x : Json.list(o)) {
            out.add(fromJson(Json.obj(x)));
        }
        return out;
    }
}
