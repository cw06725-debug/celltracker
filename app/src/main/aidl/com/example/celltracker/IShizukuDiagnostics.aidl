package com.example.celltracker;

/** Only a small, allowlisted set of diagnostic operations can run as shell. */
interface IShizukuDiagnostics {
    // 0: getprop persist.log.tag; 1: setprop persist.log.tag value;
    // 2: probe whether /data/debuglogger can be listed. Never accept arbitrary shell.
    String perform(int operation, String value) = 1;
    void destroy() = 16777114;
}
