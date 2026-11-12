package com.guoxin.assistant.application;

import com.guoxin.assistant.domain.*;
import java.util.Map;

public interface BusinessPort {
    ToolResult invoke(String tool, Map<String, Object> arguments, Actor actor, String operationId);
}
