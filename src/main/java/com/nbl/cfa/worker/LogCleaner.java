package com.nbl.cfa.worker;

import java.util.regex.Pattern;

/**
 * Làm sạch log GitHub Actions: bỏ mã màu ANSI, tiền tố timestamp, ký tự NUL, chuẩn hoá xuống dòng.
 */
public final class LogCleaner {

    private static final Pattern ANSI = Pattern.compile("\\u001B\\[[0-9;?]*[ -/]*[@-~]");
    private static final Pattern TIMESTAMP =
            Pattern.compile("(?m)^\\uFEFF?\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d+Z ");
    private static final int MAX_CHARS = 20_000;

    private LogCleaner() {
    }

    /** Làm sạch nhưng không cắt bớt, để các bước sau còn thấy đủ các mốc ##[group] / ##[error]. */
    public static String normalize(String raw) {
        String s = raw.replace("\u0000", "").replace("\r\n", "\n");
        s = ANSI.matcher(s).replaceAll("");
        s = TIMESTAMP.matcher(s).replaceAll("");
        return s.strip();
    }

    /** Chỉ giữ phần cuối nếu quá dài (lỗi thường nằm ở cuối). */
    public static String truncateTail(String s, int maxChars) {
        if (s.length() <= maxChars) {
            return s;
        }
        return "...[đã cắt bớt phần đầu log]...\n" + s.substring(s.length() - maxChars);
    }

    public static String clean(String raw) {
        return truncateTail(normalize(raw), MAX_CHARS);
    }
}