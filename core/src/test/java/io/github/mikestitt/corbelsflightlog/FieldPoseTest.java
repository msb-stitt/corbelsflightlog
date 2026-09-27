package io.github.mikestitt.corbelsflightlog;

import static org.junit.Assert.assertEquals;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * What {@link FlightLog#fieldPose} returns, at every quarter turn.
 *
 * <p>One Pedro pose, 24 inches right of the centre of the field and 12 inches
 * above it, facing 90 degrees. Each turn moves it a quarter turn round the
 * centre and adds a quarter turn to the heading.
 */
public class FieldPoseTest {

    /** 96 in and 84 in from the corner: 24 in and 12 in from the centre. */
    private static final double X_IN = 96;
    private static final double Y_IN = 84;
    private static final double HEADING = Math.PI / 2;

    /** Those two offsets in metres. */
    private static final double RIGHT = 0.6096;
    private static final double UP = 0.3048;

    private int savedTurns;

    @Before
    public void setUp() {
        savedTurns = FlightLog.fieldQuarterTurns;
    }

    @After
    public void tearDown() {
        FlightLog.fieldQuarterTurns = savedTurns;
    }

    private static void check(int turns, double x, double y, double heading) {
        FlightLog.fieldQuarterTurns = turns;
        double[] p = FlightLog.fieldPose(X_IN, Y_IN, HEADING);
        String where = turns + " quarter turns:";
        assertEquals(where + " x", x, p[0], 1e-12);
        assertEquals(where + " y", y, p[1], 1e-12);
        assertEquals(where + " heading", heading, p[2], 1e-12);
    }

    @Test
    public void noTurnIsJustMetresFromTheCentre() {
        check(0, RIGHT, UP, HEADING);
    }

    @Test
    public void oneQuarterTurnPutsRightAboveAndUpLeft() {
        check(1, -UP, RIGHT, Math.PI);
    }

    @Test
    public void twoQuarterTurnsIsThroughTheCentre() {
        check(2, -RIGHT, -UP, 3 * Math.PI / 2);
    }

    @Test
    public void threeQuarterTurnsBringsTheHeadingBackToZero() {
        check(3, UP, -RIGHT, 0);
    }

    @Test
    public void fourQuarterTurnsIsNoTurn() {
        check(4, RIGHT, UP, HEADING);
    }

    @Test
    public void aNegativeTurnCountCountsBackwards() {
        check(-1, UP, -RIGHT, 0);
    }
}
