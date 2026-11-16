package dev.lazuli.core.schematic;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Writes the small vanilla structure NBT format understood by {@link Schematic}. */
public final class StructureWriter {
    private StructureWriter() {}

    public static void write(Schematic schematic, OutputStream output) throws IOException {
        DataOutputStream out = new DataOutputStream(output);
        out.writeByte(10); // root compound
        out.writeUTF("");
        compound(out, root(schematic));
        out.flush();
    }

    private static Map<String, Object> root(Schematic s) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("size", List.of(s.sizeX(), s.sizeY(), s.sizeZ()));
        Map<String, Integer> ids = new LinkedHashMap<>();
        List<Map<String, Object>> palette = new ArrayList<>();
        for (Schematic.Block block : s.blocks()) {
            if (ids.containsKey(block.state())) continue;
            ids.put(block.state(), palette.size());
            Map<String, Object> state = new LinkedHashMap<>();
            int bracket = block.state().indexOf('[');
            state.put("Name", bracket < 0 ? block.state() : block.state().substring(0, bracket));
            if (bracket >= 0 && block.state().endsWith("]")) {
                Map<String, String> props = new LinkedHashMap<>();
                for (String part : block.state().substring(bracket + 1, block.state().length() - 1).split(",")) {
                    int equals = part.indexOf('=');
                    if (equals > 0) props.put(part.substring(0, equals), part.substring(equals + 1));
                }
                if (!props.isEmpty()) state.put("Properties", props);
            }
            palette.add(state);
        }
        root.put("palette", palette);
        List<Map<String, Object>> blocks = new ArrayList<>();
        for (Schematic.Block block : s.blocks()) {
            blocks.add(new LinkedHashMap<>(Map.of("pos", List.of(block.x(), block.y(), block.z()), "state", ids.get(block.state()))));
        }
        root.put("blocks", blocks);
        root.put("entities", List.of());
        root.put("DataVersion", 4325);
        return root;
    }

    private static void compound(DataOutputStream out, Map<String, Object> values) throws IOException {
        for (var entry : values.entrySet()) {
            Object value = entry.getValue();
            int type = type(value);
            out.writeByte(type);
            out.writeUTF(entry.getKey());
            payload(out, type, value);
        }
        out.writeByte(0);
    }

    private static int type(Object value) {
        if (value instanceof String) return 8;
        if (value instanceof Integer) return 3;
        if (value instanceof List<?>) return 9;
        if (value instanceof Map<?, ?>) return 10;
        throw new IllegalArgumentException("Unsupported structure value: " + value);
    }

    private static void payload(DataOutputStream out, int type, Object value) throws IOException {
        switch (type) {
            case 3 -> out.writeInt((Integer) value);
            case 8 -> out.writeUTF((String) value);
            case 9 -> {
                List<?> list = (List<?>) value;
                int elementType = list.isEmpty() ? 3 : type(list.get(0));
                out.writeByte(elementType);
                out.writeInt(list.size());
                for (Object element : list) payload(out, elementType, element);
            }
            case 10 -> compound(out, castMap(value));
            default -> throw new IllegalArgumentException("Unsupported structure tag: " + type);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }
}
