package com.example.celltracker;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.File;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/** Runs in the Shizuku UserService process, not the normal Android app process. */
public final class ShizukuDiagnosticsService extends IShizukuDiagnostics.Stub {
    public ShizukuDiagnosticsService() {}

    @Override public String perform(int operation, String value) {
        try {
            switch (operation) {
                case 0: return readLogProperty();
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

    // Files are read only from the exact OEM debuglogger tree. No shell supplied by callers.
    private static final File ROOT = new File("/data/debuglogger");
    private static final int MAX_FILES = 1200;
    private static final int MAX_CHUNK = 64 * 1024;
    private static final long MAX_SINGLE_FILE = 256L * 1024 * 1024;
    private static final long MAX_TOTAL_SIZE = 512L * 1024 * 1024;

    private static File checkedFile(String relative) throws Exception {
        if (relative == null || relative.isEmpty() || relative.startsWith("/") || relative.indexOf('\0') >= 0)
            throw new SecurityException("Invalid relative path");
        File root = ROOT.getCanonicalFile();
        File file = new File(root, relative).getCanonicalFile();
        if (!file.getPath().startsWith(root.getPath() + File.separator))
            throw new SecurityException("Path escapes logger directory");
        // Prevent symlinks in the entire submitted path, including intermediate directories.
        File segment = root;
        for (String part : relative.split("/")) {
            if (part.isEmpty() || part.equals(".") || part.equals(".."))
                throw new SecurityException("Invalid path component");
            segment = new File(segment, part);
            if (Files.isSymbolicLink(segment.toPath())) throw new SecurityException("Symlink is not allowed");
        }
        if (!file.isFile() || !file.canRead()) throw new SecurityException("File not readable: " + relative);
        return file;
    }

    @Override public String listLogFiles() {
        try {
            if (!ROOT.isDirectory() || !ROOT.canRead()) return "ERROR\n/data/debuglogger is not readable by Shizuku UID";
            ArrayDeque<File> dirs = new ArrayDeque<>();
            dirs.add(ROOT);
            StringBuilder rows = new StringBuilder("OK\n");
            long total = 0L; int count = 0;
            String prefix = ROOT.getCanonicalPath() + File.separator;
            while (!dirs.isEmpty()) {
                File folder = dirs.removeFirst();
                File[] entries = folder.listFiles();
                if (entries == null) throw new SecurityException("Cannot enumerate " + folder.getName());
                for (File file : entries) {
                    if (Files.isSymbolicLink(file.toPath())) continue;
                    if (file.isDirectory()) { dirs.addLast(file); continue; }
                    if (!file.isFile()) continue;
                    if (!file.canRead()) throw new SecurityException("File is inaccessible: " + file.getName());
                    long size = file.length();
                    if (size < 0 || size > MAX_SINGLE_FILE) throw new IllegalStateException("File too large: " + file.getName());
                    total += size;
                    if (total > MAX_TOTAL_SIZE) throw new IllegalStateException("Logger directory exceeds safe 512 MiB export cap");
                    if (++count > MAX_FILES) throw new IllegalStateException("Logger directory exceeds 1200 file limit");
                    String path = file.getCanonicalPath();
                    if (!path.startsWith(prefix)) throw new SecurityException("Path escaped logger directory");
                    String relative = path.substring(prefix.length()).replace(File.separatorChar, '/');
                    String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(relative.getBytes("UTF-8"));
                    rows.append(encoded).append('\t').append(size).append('\t').append(file.lastModified()).append('\n');
                    if (rows.length() > 250_000) throw new IllegalStateException("File index too large");
                }
            }
            if (count == 0) return "ERROR\nNo readable logger files";
            return rows.toString();
        } catch (Exception e) { return "ERROR\n" + e.getClass().getSimpleName() + ": " + e.getMessage(); }
    }

    @Override public byte[] readLogChunk(String relativePath, long offset, int maxBytes) {
        try {
            if (offset < 0 || maxBytes <= 0 || maxBytes > MAX_CHUNK) throw new SecurityException("Invalid chunk range");
            File file = checkedFile(relativePath);
            if (file.length() > MAX_SINGLE_FILE || offset > file.length())
                throw new SecurityException("Invalid file length or offset");
            try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
                input.seek(offset);
                byte[] buffer = new byte[(int)Math.min((long)maxBytes, file.length() - offset)];
                input.readFully(buffer);
                return buffer;
            }
        } catch (Exception e) { throw new SecurityException("Shizuku logger read failed: " + e.getMessage(), e); }
    }

    /** Read the exact property with two independent permitted methods.
     * A truly absent property is reported as an empty successful value, never guessed as D.
     */
    private String readLogProperty() throws Exception {
        String command = runCommand("/system/bin/getprop", "persist.log.tag");
        if (command.startsWith("OK\n") && !command.substring(3).trim().isEmpty()) return command;
        try {
            Class<?> systemProperties = Class.forName("android.os.SystemProperties");
            String actual = (String) systemProperties.getDeclaredMethod("get", String.class)
                .invoke(null, "persist.log.tag");
            if (actual != null && !actual.isEmpty()) return "OK\n" + actual;
        } catch (Exception ignored) { /* Some ROMs block hidden API reflection. */ }
        return command;
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
