package com.guoxin.assistant.application;
import com.guoxin.assistant.domain.Actor;
import java.util.Map;
public record RunRequest(Actor actor,String conversationId,String runId,String taskId,String text,Map<String,Object> action,Map<String,Object> state) {}
