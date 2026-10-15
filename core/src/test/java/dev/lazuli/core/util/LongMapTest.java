package dev.lazuli.core.util;

import dev.lazuli.core.world.Pos;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class LongMapTest {
    @Test
    void behavesLikeAHashMapThroughPutsRemovesAndFilters() {
        LongMap<Integer> m = new LongMap<>();
        Map<Long, Integer> ref = new HashMap<>();
        Random r = new Random(1);
        for (int i = 0; i < 200_000; i++) {
            long k = Pos.pack(r.nextInt(60) - 30, r.nextInt(40), r.nextInt(60) - 30);
            switch (r.nextInt(4)) {
                case 0, 1 -> assertEquals(ref.put(k, i), m.put(k, i));
                case 2 -> assertEquals(ref.remove(k), m.remove(k));
                default -> assertEquals(ref.get(k), m.get(k));
            }
            if (i % 50_000 == 0) {
                int y = r.nextInt(40);
                ref.keySet().removeIf(p -> Pos.y(p) == y);
                m.removeIf(p -> Pos.y(p) == y);
            }
            assertEquals(ref.size(), m.size());
        }
        ref.forEach((k, v) -> assertEquals(v, m.get(k)));
        assertNull(m.get(Long.MIN_VALUE));
        m.put(Long.MIN_VALUE, 7);
        assertEquals(7, m.get(Long.MIN_VALUE));
    }

    @Test
    void edgeMapKeepsPairsApart() {
        EdgeMap m = new EdgeMap();
        Map<String, Double> ref = new HashMap<>();
        Random r = new Random(2);
        for (int i = 0; i < 100_000; i++) {
            long u = Pos.pack(r.nextInt(30), r.nextInt(10), r.nextInt(30)), v = Pos.offset(u, r.nextInt(3) - 1, r.nextInt(3) - 1, r.nextInt(3) - 1);
            double val = r.nextDouble();
            m.put(u, v, val);
            ref.put(u + "," + v, val);
        }
        assertEquals(ref.size(), m.size());
        m.removeIfFrom(u -> Pos.x(u) < 15);
        for (var e : ref.entrySet()) {
            String[] uv = e.getKey().split(",");
            long u = Long.parseLong(uv[0]), v = Long.parseLong(uv[1]);
            assertEquals(Pos.x(u) < 15 ? -1 : e.getValue(), m.get(u, v, -1), 0);
        }
    }
}
