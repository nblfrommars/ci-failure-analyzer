package com.nbl.cfa.classifier;

import java.util.Arrays;
import java.util.List;

public enum ErrorType {
    TEST("test"),
    BUILD("build"),
    DEPENDENCY("dependency"),
    CONFIG_ENV("config_env"),
    LINT("lint"),
    DEPLOY("deploy"),
    OTHER("other");

    private final String value;

    ErrorType(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static ErrorType fromValue(String text) {
        if (text != null) {
            String normalized = text.strip();
            for (ErrorType type : values()) {
                if (type.value.equalsIgnoreCase(normalized)) {
                    return type;
                }
            }
        }
        throw new IllegalArgumentException("error_type không hợp lệ: " + text);
    }

    public static List<String> allValues() {
        return Arrays.stream(values()).map(ErrorType::value).toList();
    }
}