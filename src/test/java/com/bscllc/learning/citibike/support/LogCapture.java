package com.bscllc.learning.citibike.support;

import java.util.logging.*;

public final class LogCapture extends Handler implements AutoCloseable {
    private final StringBuilder content = new StringBuilder();
    private final Logger logger = Logger.getLogger("com.bscllc.learning.citibike");

    public LogCapture() {
        setLevel(Level.ALL);
        logger.addHandler(this);
    }

    @Override public synchronized void publish(LogRecord record) {
        content.append(new SimpleFormatter().format(record));
    }
    @Override public void flush() {}
    @Override public void close() { logger.removeHandler(this); }
    public synchronized String content() { return content.toString(); }
}
