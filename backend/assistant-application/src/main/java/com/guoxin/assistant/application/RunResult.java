package com.guoxin.assistant.application;
import java.util.*;
public record RunResult(Map<String,Object> state,String reply,String status,List<Map<String,Object>> components) {}
