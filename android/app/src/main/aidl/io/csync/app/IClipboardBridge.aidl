package io.csync.app;
import io.csync.app.IClipListener;
interface IClipboardBridge {
    void start(IClipListener listener) = 0;
    boolean writeText(String text, String eventId, String hash) = 1;
    String capability() = 2;
    void destroy() = 16777114;
}
