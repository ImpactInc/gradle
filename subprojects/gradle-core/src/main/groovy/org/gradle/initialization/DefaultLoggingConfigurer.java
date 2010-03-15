/*
 * Copyright 2010 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.initialization;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.filter.LevelFilter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import ch.qos.logback.core.Layout;
import ch.qos.logback.core.filter.Filter;
import ch.qos.logback.core.spi.FilterReply;
import org.fusesource.jansi.AnsiConsole;
import org.gradle.api.logging.LogLevel;
import org.gradle.api.logging.Logging;
import org.gradle.api.logging.StandardOutputListener;
import org.gradle.api.logging.StandardOutputLogging;
import org.gradle.listener.ListenerBroadcast;
import org.gradle.logging.*;
import org.jruby.ext.posix.POSIX;
import org.jruby.ext.posix.POSIXFactory;
import org.jruby.ext.posix.POSIXHandler;
import org.slf4j.LoggerFactory;
import org.slf4j.bridge.SLF4JBridgeHandler;

import java.io.*;
import java.util.logging.LogManager;

/**
 * @author Hans Dockter
 */
public class DefaultLoggingConfigurer implements LoggingConfigurer {
    private final Appender stderrConsoleAppender = new Appender();
    private final Appender stdoutConsoleAppender = new Appender();
    private boolean applied;
    private boolean isTerminal;

    public void addStandardErrorListener(StandardOutputListener listener) {
        stderrConsoleAppender.addListener(listener);
    }

    public void removeStandardErrorListener(StandardOutputListener listener) {
        stderrConsoleAppender.removeListener(listener);
    }

    public void addStandardOutputListener(StandardOutputListener listener) {
        stdoutConsoleAppender.addListener(listener);
    }

    public void removeStandardOutputListener(StandardOutputListener listener) {
        stdoutConsoleAppender.removeListener(listener);
    }

    public void configure(LogLevel logLevel) {
        LoggerContext lc = (LoggerContext) LoggerFactory.getILoggerFactory();
        ch.qos.logback.classic.Logger rootLogger;
        if (!applied) {
            lc.reset();
            LogManager.getLogManager().reset();
            SLF4JBridgeHandler.install();

            isTerminal = POSIXFactory.getPOSIX(new POSIXHandlerImpl(), true).isatty(FileDescriptor.out);
            stderrConsoleAppender.setTarget(isTerminal ? AnsiConsole.err() : System.err);
            stdoutConsoleAppender.setTarget(isTerminal ? AnsiConsole.out() : System.out);
            stderrConsoleAppender.setContext(lc);
            stdoutConsoleAppender.setContext(lc);
            rootLogger = lc.getLogger("ROOT");
            rootLogger.addAppender(stdoutConsoleAppender);
            rootLogger.addAppender(stderrConsoleAppender);
        } else {
            rootLogger = lc.getLogger("ROOT");
        }

        applied = true;
        stderrConsoleAppender.stop();
        stdoutConsoleAppender.stop();
        stderrConsoleAppender.clearAllFilters();
        stdoutConsoleAppender.clearAllFilters();

        stderrConsoleAppender.addFilter(createLevelFilter(lc, Level.ERROR, FilterReply.ACCEPT, FilterReply.DENY));
        Level level = Level.INFO;

        setLayouts(logLevel, stderrConsoleAppender, stdoutConsoleAppender, lc);

        MarkerFilter quietFilter = new MarkerFilter(FilterReply.DENY, Logging.QUIET);
        stdoutConsoleAppender.addFilter(quietFilter);
        if (!(logLevel == LogLevel.QUIET)) {
            quietFilter.setOnMismatch(FilterReply.NEUTRAL);
            if (logLevel == LogLevel.DEBUG) {
                level = Level.DEBUG;
                stdoutConsoleAppender.addFilter(createLevelFilter(lc, Level.INFO, FilterReply.ACCEPT,
                        FilterReply.NEUTRAL));
                stdoutConsoleAppender.addFilter(createLevelFilter(lc, Level.DEBUG, FilterReply.ACCEPT,
                        FilterReply.NEUTRAL));
            } else {
                if (logLevel == LogLevel.INFO) {
                    level = Level.INFO;
                    stdoutConsoleAppender.addFilter(createLevelFilter(lc, Level.INFO, FilterReply.ACCEPT,
                            FilterReply.NEUTRAL));
                } else {
                    stdoutConsoleAppender.addFilter(new MarkerFilter(Logging.LIFECYCLE, Logging.PROGRESS));
                }
            }
            stdoutConsoleAppender.addFilter(createLevelFilter(lc, Level.WARN, FilterReply.ACCEPT, FilterReply.DENY));
        }
        rootLogger.setLevel(level);
        stdoutConsoleAppender.start();
        stderrConsoleAppender.start();
    }

    private void setLayouts(LogLevel logLevel, Appender errorAppender, Appender nonErrorAppender, LoggerContext lc) {
        nonErrorAppender.setFormatter(createFormatter(lc, logLevel));
        errorAppender.setFormatter(createFormatter(lc, logLevel));
    }

    private Filter createLevelFilter(LoggerContext lc, Level level, FilterReply onMatch, FilterReply onMismatch) {
        LevelFilter levelFilter = new LevelFilter();
        levelFilter.setContext(lc);
        levelFilter.setOnMatch(onMatch);
        levelFilter.setOnMismatch(onMismatch);
        levelFilter.setLevel(level.toString());
        levelFilter.start();
        return levelFilter;
    }

    private LogEventFormatter createFormatter(LoggerContext loggerContext, LogLevel logLevel) {
        if (logLevel == LogLevel.DEBUG) {
            Layout<ILoggingEvent> layout = new DebugLayout();
            layout.setContext(loggerContext);
            layout.start();
            return new LayoutBasedFormatter(layout);
        } else if (isTerminal) {
            return new ConsoleBackedFormatter(loggerContext);
        } else {
            return new BasicProgressLoggingAwareFormatter(loggerContext);
        }
    }

    private static class DebugLayout extends PatternLayout {
        private DebugLayout() {
            setPattern("%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n%ex");
        }
    }

    private static class Appender extends AppenderBase<ILoggingEvent> {
        private final ListenerBroadcast<StandardOutputListener> listeners
                = new ListenerBroadcast<StandardOutputListener>(StandardOutputListener.class);
        private LogEventFormatter formatter;
        private PrintStream target;

        private void setTarget(final PrintStream target) {
            this.target = target;
        }

        public void setFormatter(LogEventFormatter formatter) {
            this.formatter = formatter;
            formatter.setTarget(new ListenerAdapter(listeners.getSource(), target));
        }

        public void removeListener(StandardOutputListener listener) {
            listeners.remove(listener);
        }

        public void addListener(StandardOutputListener listener) {
            listeners.add(listener);
        }

        @Override
        protected void append(ILoggingEvent event) {
            try {
                formatter.format(event);
                target.flush();
            } catch (Throwable t) {
                // Give up and try stdout
                t.printStackTrace(StandardOutputLogging.DEFAULT_ERR);
            }
        }
    }

    private static class ListenerAdapter implements Appendable {
        private final StandardOutputListener listener;
        private final Appendable next;

        private ListenerAdapter(StandardOutputListener listener, Appendable next) {
            this.listener = listener;
            this.next = next;
        }

        public Appendable append(char c) throws IOException {
            next.append(c);
            listener.onOutput(String.valueOf(c));
            return this;
        }

        public Appendable append(CharSequence sequence) throws IOException {
            next.append(sequence);
            if (sequence != null) {
                listener.onOutput(sequence);
            } else {
                listener.onOutput("null"); // This is the contract of Appendable.append()
            }
            return this;
        }

        public Appendable append(CharSequence sequence, int start, int end) throws IOException {
            next.append(sequence, start, end);
            if (sequence != null) {
                listener.onOutput(sequence.subSequence(start, end));
            } else {
                listener.onOutput("null"); // This is the contract of Appendable.append() 
            }
            return this;
        }
    }

    private class POSIXHandlerImpl implements POSIXHandler {
        public void error(POSIX.ERRORS errors, String message) {
            throw new UnsupportedOperationException();
        }

        public void unimplementedError(String message) {
            throw new UnsupportedOperationException();
        }

        public void warn(WARNING_ID warningId, String message, Object... objects) {
        }

        public boolean isVerbose() {
            return false;
        }

        public File getCurrentWorkingDirectory() {
            throw new UnsupportedOperationException();
        }

        public String[] getEnv() {
            throw new UnsupportedOperationException();
        }

        public InputStream getInputStream() {
            return System.in;
        }

        public PrintStream getOutputStream() {
            return System.out;
        }

        public int getPID() {
            throw new UnsupportedOperationException();
        }

        public PrintStream getErrorStream() {
            return System.err;
        }
    }
}
