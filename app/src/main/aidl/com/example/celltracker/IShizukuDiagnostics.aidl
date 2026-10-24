package com.example.celltracker;

/** Fixed allowlist only: never pass arbitrary commands or paths outside /data/debuglogger. */
interface IShizukuDiagnostics {
    String perform(int operation, String value) = 1;
    String listLogFiles() = 2;
    byte[] readLogChunk(String relativePath, long offset, int maxBytes) = 3;
    void destroy() = 16777114;
}
