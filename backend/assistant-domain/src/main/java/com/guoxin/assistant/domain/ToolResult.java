package com.guoxin.assistant.domain;
import java.util.Map;
public record ToolResult(boolean success, String code, String category, String message, Map<String,String> fieldErrors, Map<String,Object> data) {
 public static ToolResult ok(Map<String,Object> data) {return new ToolResult(true,"OK","NONE","成功",Map.of(),data);}
 public static ToolResult error(String code,String category,String message) {return new ToolResult(false,code,category,message,Map.of(),Map.of());}
}
