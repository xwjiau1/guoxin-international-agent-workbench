package com.guoxin.assistant.application;

import java.util.Map;

public interface ExecutionEvents {
    void emit(String type, Map<String, Object> payload);

    void checkpoint(Map<String, Object> state);

    default boolean isCancellationRequested() {
        return false;
    }
}
