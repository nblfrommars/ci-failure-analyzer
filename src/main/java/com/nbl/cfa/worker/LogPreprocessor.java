package com.nbl.cfa.worker;

import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tiền xử lý log của một job bằng quy tắc (không dùng LLM):
 * - xác định bước (step) fail đầu tiên và cắt đúng đoạn log của bước đó;
 * - phát hiện flaky qua dòng tóm tắt "N flaky" của Playwright.
 */
public final class LogPreprocessor {

    private static final String STEP_MARK = "##[group]Run ";
    private static final String ERROR_MARK = "##[error]";
    private static final int MAX_CHARS = 20_000;

    private static final Pattern FLAKY_SUMMARY = Pattern.compile("(?m)^\\s*\\d+ flaky\\s*$");
    private static final Pattern PLAYWRIGHT_START = Pattern.compile("(?m)^Running \\d+ tests? using");
    private static final Pattern PLAYWRIGHT_END = Pattern.compile("(?m)^\\s*\\d+ passed\\b.*$");

    private LogPreprocessor() {
    }

    public record Result(String failedStep, String excerpt, boolean flaky) {
    }

    public static Result process(String rawLog) {
        String log = LogCleaner.normalize(rawLog);
        boolean flaky = FLAKY_SUMMARY.matcher(log).find();
        String[] lines = log.split("\n", -1);

        int errorLine = firstLineStartingWith(lines, ERROR_MARK);
        if (errorLine >= 0) {
            int stepLine = lastStepLineBefore(lines, errorLine);
            if (stepLine >= 0) {
                String step = lines[stepLine].substring(STEP_MARK.length()).strip();
                String excerpt = String.join("\n", Arrays.copyOfRange(lines, stepLine, errorLine + 1));
                return new Result(step, LogCleaner.truncateTail(excerpt, MAX_CHARS), flaky);
            }
            return new Result(null, LogCleaner.truncateTail(log, MAX_CHARS), flaky);
        }

        // Không có ##[error]: job xanh. Với flaky, giữ đúng khối kết quả Playwright.
        Matcher start = PLAYWRIGHT_START.matcher(log);
        if (flaky && start.find()) {
            int from = start.start();
            Matcher end = PLAYWRIGHT_END.matcher(log);
            int to = end.find(from) ? end.end() : log.length();
            return new Result(null, LogCleaner.truncateTail(log.substring(from, to), MAX_CHARS), true);
        }
        return new Result(null, LogCleaner.truncateTail(log, MAX_CHARS), flaky);
    }

    private static int firstLineStartingWith(String[] lines, String prefix) {
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].startsWith(prefix)) {
                return i;
            }
        }
        return -1;
    }

    private static int lastStepLineBefore(String[] lines, int before) {
        for (int i = before; i >= 0; i--) {
            if (lines[i].startsWith(STEP_MARK)) {
                return i;
            }
        }
        return -1;
    }
}