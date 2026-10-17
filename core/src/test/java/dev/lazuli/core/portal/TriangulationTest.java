package dev.lazuli.core.portal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TriangulationTest {
    private static Triangulation.Throw toward(double x, double z, double tx, double tz) {
        return new Triangulation.Throw(x, z, tx - x, tz - z);
    }

    @Test
    void twoThrowsMeetAtTheStronghold() {
        var a = toward(0, 0, 1200, -900);
        var b = toward(150, 200, 1200, -900);
        double[] at = Triangulation.meet(a, b);
        assertNotNull(at);
        assertEquals(1200, at[0], 1e-6);
        assertEquals(-900, at[1], 1e-6);
    }

    @Test
    void parallelThrowsTellNothing() {
        var a = toward(0, 0, 1000, 0);
        var b = toward(0, 5, 1000, 5);
        assertNull(Triangulation.meet(a, b));
    }

    @Test
    void linesMeetingBehindAreNoAnswer() {
        // lines cross, but behind where both eyes flew
        var a = new Triangulation.Throw(0, 0, 1, 0);
        var b = new Triangulation.Throw(10, 10, 1, 1);
        assertNull(Triangulation.meet(a, b));
    }

    @Test
    void sideStepIsSquareToTheThrow() {
        var a = new Triangulation.Throw(10, 20, 0, 5);
        double[] s = Triangulation.sideStep(a, 100);
        assertEquals(-90, s[0], 1e-6);
        assertEquals(20, s[1], 1e-6);
    }

    @Test
    void flightGivesTheDirection() {
        var t = Triangulation.Throw.of(5, 5, 8, 9);
        assertEquals(0.6, t.dx(), 1e-9);
        assertEquals(0.8, t.dz(), 1e-9);
        assertNull(Triangulation.Throw.of(5, 5, 5, 5.001));
    }
}
