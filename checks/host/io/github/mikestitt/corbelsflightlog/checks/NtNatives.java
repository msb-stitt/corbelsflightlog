package io.github.mikestitt.corbelsflightlog.checks;

import edu.wpi.first.networktables.NetworkTablesJNI;
import edu.wpi.first.util.WPIUtilJNI;

import org.lwjgl.system.linux.DynamicLinkLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Puts WPILib's NetworkTables native libraries where the JVM can load them.
 *
 * <p>The published {@code -jni} jars carry one library each and nothing that
 * finds it. WPILib's own loaders do not work outside a GradleRIO build:
 * {@code RuntimeLoader} looks only along {@code java.library.path} and fails
 * with {@code no ntcorejni in java.library.path}, and
 * {@code CombinedRuntimeLoader} reads a {@code ResourceInformation} json that
 * GradleRIO generates and these jars do not contain, failing with
 * {@code argument "src" is null}. So the library is copied out of the jar to a
 * temporary libraryFilePath and loaded by name, which is all either of those would have
 * done.
 *
 * <p>Order matters: ntcore's library needs wpiutil's to be loaded already.
 * On Linux that is not enough. {@code libntcorejni.so} carries
 * {@code wpi::detail::PromiseFactoryBase::CreateRequest} as an undefined symbol
 * and names no wpiutil library among its dependencies, so the loader is never
 * told where to find it; {@code libwpiutiljni.so} exports it, and
 * {@code System.load} is {@code dlopen} without {@code RTLD_GLOBAL}, which
 * leaves those symbols out of reach of the next library. The symptom is
 * {@code java: symbol lookup error: .../libntcorejni.so: undefined symbol:
 * _ZN3wpi6detail18PromiseFactoryBase13CreateRequestEv} and a test JVM that
 * exits 127 before any test reports. So wpiutil is opened globally first, and
 * then loaded again the ordinary way so its own native methods bind.
 *
 * <p>Copied from ftc31459-26-27-biobuzz, where SimPublisherTest runs it.
 */
final class NtNatives {

    private static boolean loaded;

    private NtNatives() {
    }

    /** Loads both libraries, once per JVM. */
    static synchronized void load() {
        if (loaded) return;
        // Stop the JNI classes trying to find the libraries themselves.
        WPIUtilJNI.Helper.setExtractOnStaticLoad(false);
        NetworkTablesJNI.Helper.setExtractOnStaticLoad(false);
        Path directory = tempDirectory();
        Path wpiutil = unpack("wpiutiljni", directory);
        openGlobally(wpiutil);
        System.load(wpiutil.toString());
        System.load(unpack("ntcorejni", directory).toString());
        loaded = true;
    }

    /**
     * Opens a library so that the next one can see its symbols, on the platform
     * where that is not automatic.
     *
     * <p>Linux only, and a no-op everywhere else: macOS and Windows resolve
     * ntcore's reference into wpiutil without help, by a global namespace and by
     * an import table naming the module. The class javadoc says what breaks
     * without it.
     *
     * <p>The gamepad library is what has a {@code dlopen} binding to hand.
     * Nothing else about the two is related, and both are test-scope only.
     */
    private static void openGlobally(Path library) {
        if (!System.getProperty("os.name").toLowerCase().contains("linux")) {
            return;
        }
        long handle = DynamicLinkLoader.dlopen(library.toString(),
                DynamicLinkLoader.RTLD_NOW | DynamicLinkLoader.RTLD_GLOBAL);
        if (handle == 0L) {
            throw new IllegalStateException("could not open " + library
                    + " with global symbols: " + DynamicLinkLoader.dlerror());
        }
    }

    /** One directory for both libraries, so a dependency on a sibling resolves. */
    private static Path tempDirectory() {
        try {
            Path directory = Files.createTempDirectory("ntjni");
            directory.toFile().deleteOnExit();
            return directory;
        } catch (IOException e) {
            throw new UncheckedIOException("could not make a directory for the JNI libraries", e);
        }
    }

    /** Copies one library out of its jar, and says where it landed. */
    private static Path unpack(String name, Path directory) {
        String resource = directory() + fileName(name);
        try (InputStream in = NtNatives.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("not on the test classpath: " + resource
                        + " -- check the classifier in checks/build.gradle matches this machine");
            }
            Path libraryFilePath = directory.resolve(fileName(name));
            libraryFilePath.toFile().deleteOnExit();
            Files.copy(in, libraryFilePath, StandardCopyOption.REPLACE_EXISTING);
            return libraryFilePath.toAbsolutePath();
        } catch (IOException e) {
            throw new UncheckedIOException("could not unpack " + resource, e);
        }
    }

    /** Where WPILib puts the library inside the jar, by platform. */
    private static String directory() {
        String os = System.getProperty("os.name").toLowerCase();
        String arch = System.getProperty("os.arch").toLowerCase();
        boolean arm = arch.contains("aarch64") || arch.contains("arm64");
        if (os.contains("mac")) return "/osx/universal/";
        if (os.contains("win")) return arm ? "/windows/arm64/" : "/windows/x86-64/";
        return arm ? "/linux/arm64/" : "/linux/x86-64/";
    }

    private static String fileName(String name) {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("mac")) return "lib" + name + ".dylib";
        if (os.contains("win")) return name + ".dll";
        return "lib" + name + ".so";
    }
}
