package io.github.mikestitt.corbelsflightlog.checks;

import com.qualcomm.robotcore.eventloop.opmode.OpMode;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;

import io.github.mikestitt.corbelsflightlog.FlightLog;
import io.github.mikestitt.corbelsflightlog.ftc.FtcFlightLog;

/**
 * Check 5: the geometry types, for looking at in AdvantageScope.
 *
 * <p>Drives an imaginary robot in a circle -- no hardware needed -- and logs it
 * as a Pose2d, a Pose3d, ChassisSpeeds and wheel speeds. No hardware at all:
 * whether a real IMU reports what you expect is a robot configuration matter,
 * not something this library can check.
 *
 * <p>Afterwards, in AdvantageScope: drag Circle/Pose onto the 2D field (it
 * should go round in a circle), Circle/Pose3d onto the 3D field, and look at
 * Circle/Speeds and Circle/Wheels in a table.
 */
@TeleOp(name = "Log 5: geometry", group = "LogCheck")
public class LogCheck5Geometry extends OpMode {

    private static final double METRE_IN_INCHES = 39.3700787401575;

    private FlightLog log;
    private long startNs;

    @Override
    public void init() {
        telemetry.addLine("Press start; the log opens then.");
        telemetry.update();
    }

    @Override
    public void start() {
        log = FtcFlightLog.open(this);
        startNs = System.nanoTime();
    }

    @Override
    public void loop() {
        double t = (System.nanoTime() - startNs) / 1e9;

        // An imaginary robot going round a 24 inch circle in the middle of the
        // field, facing the way it travels. Pedro units: inches, corner origin.
        double xIn = 72 + 24 * Math.cos(t);
        double yIn = 72 + 24 * Math.sin(t);
        double heading = t + Math.PI / 2;
        log.pose("Circle/Pose", xIn, yIn, heading);

        // The same pose, a metre off the floor. Same Pedro coordinates, so the
        // library applies the same field rotation -- converting by hand here
        // left the 3D robot 90 degrees away from the 2D one.
        log.pose("Circle/Pose3d", xIn, yIn, METRE_IN_INCHES, heading);

        log.chassisSpeeds("Circle/Speeds", 24 * 0.0254, 0, 1.0);
        log.mecanumWheelSpeeds("Circle/Wheels", 1.0, 1.0, 0.9, 0.9);
        log.twist2d("Circle/Twist", 0.6, 0, 1.0);
        log.recordOutput("Circle/t", t);

        log.endLoop();

        telemetry.addData("Log", log.status());
        telemetry.addData("t", "%.1f s", t);
        telemetry.addLine("AdvantageScope: Circle/Pose on 2D field, Circle/Pose3d on 3D.");
        telemetry.update();
    }

    @Override
    public void stop() {
        log.close();
    }
}
