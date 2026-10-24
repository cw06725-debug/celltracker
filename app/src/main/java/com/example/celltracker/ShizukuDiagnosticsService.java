package com.example.celltracker;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

/** Runs in the Shizuku UserService process, not the normal Android app process. */
public final class ShizukuDiagnosticsService extends IShizukuDiagnostics.Stub {
    public ShizukuDiagnosticsService() {}

    @Override public String perform(int operation, String value) {
        try {
            switch (operation) {
                case 0: return runCommand("/system/bin/getprop", "persist.log.tag");
                case 1:
                    if (value == null || !value.matches("[A-Za-z0-9_.-]{0,64}"))
                        return "ERROR\nInvalid property value";
                    return runCommand("/system/bin/setprop", "persist.log.tag", value);
                case 2:
                    // Only check if the protected directory is readable by this UID.
                    String output = runCommand("/system/bin/ls", "-A", "/data/debuglogger/");
                    return output.startsWith("OK\n") ? "OK\nReadable by Shizuku UID" : output;
                default: return "ERROR\nUnsupported operation";
            }
        } catch (Exception e) {
            return "ERROR\n" + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    private String runCommand(String... args) throws Exception {
        Process process = new ProcessBuilder(args).redirectErrorStream(true).start();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try (InputStream stream = process.getInputStream()) {
                byte[] buf = new byte[4096]; int n;
                while ((n = stream.read(buf)) != -1) {
                    int remaining = 8192 - out.size();
                    if (remaining > 0) out.write(buf, 0, Math.min(n, remaining));
                }
            } catch (Exception ignored) { }
        }, "shizuku-diagnostic-reader");
        reader.setDaemon(true);
        reader.start();
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            return "ERROR\nCommand timed out";
        }
        reader.join(1500);
        String result = out.toString("UTF-8").trim();
        return process.exitValue() == 0 ? "OK\n" + result :
                "ERROR\nexit=" + process.exitValue() + " " + result;
    }

    @Override public void destroy() { System.exit(0); }
}
