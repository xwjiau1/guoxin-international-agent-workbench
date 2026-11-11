package com.guoxin.assistant.web;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guoxin.assistant.domain.Actor;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.net.URI;
import java.util.*;
import static com.guoxin.assistant.infrastructure.AgentStore.map;
import static com.guoxin.assistant.web.AuthService.ApiException;
@Component
public class AuthFilter extends OncePerRequestFilter {
 private final AuthService auth;private final ObjectMapper mapper;private final Environment env;
 public AuthFilter(AuthService auth,ObjectMapper mapper,Environment env){this.auth=auth;this.mapper=mapper;this.env=env;}
 protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)throws ServletException,IOException{
  try{String path=req.getRequestURI();if(!path.startsWith("/api/v1/")){chain.doFilter(req,res);return;}
   if(path.equals("/api/v1/auth/demo-login")){if(!Arrays.asList(env.getActiveProfiles()).contains("local-demo"))throw new ApiException(404,"NOT_FOUND","未找到接口");checkOrigin(req);chain.doFilter(req,res);return;}
   var session=auth.session(req);Actor actor=auth.actor(session.get("user_id").toString());req.setAttribute("actor",actor);req.setAttribute("session",session);
   if(!Set.of("GET","HEAD","OPTIONS").contains(req.getMethod())){checkOrigin(req);String csrf=req.getHeader("X-CSRF-Token");if(csrf==null||!java.security.MessageDigest.isEqual(csrf.getBytes(java.nio.charset.StandardCharsets.UTF_8),session.get("csrf_token").toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)))throw new ApiException(403,"CSRF_INVALID","请求验证失效，请刷新页面");}
   chain.doFilter(req,res);
  }catch(ApiException ex){res.setStatus(ex.status);res.setContentType("application/json;charset=UTF-8");mapper.writeValue(res.getWriter(),map("code",ex.code,"category","AUTH","message",ex.getMessage(),"traceId",UUID.randomUUID().toString(),"recoveryAction",ex.status==401?"RELOGIN":"NONE"));}
  catch(org.springframework.dao.DataAccessException ex){if(!res.isCommitted()){String traceId=UUID.randomUUID().toString();org.slf4j.LoggerFactory.getLogger(getClass()).error("Authentication store unavailable trace={}",traceId);res.setStatus(503);res.setContentType("application/json;charset=UTF-8");mapper.writeValue(res.getWriter(),map("code","AUTH_STORE_UNAVAILABLE","category","TRANSIENT","message","服务暂时不可用，请稍后重试","traceId",traceId,"recoveryAction","RETRY_LATER"));}}
 }
 private void checkOrigin(HttpServletRequest req){String origin=req.getHeader("Origin");if(origin==null)return;try{URI u=URI.create(origin);if(!Set.of("localhost","127.0.0.1","[::1]").contains(u.getHost())||!Set.of("http","https").contains(u.getScheme())||!(u.getPort()==5173||u.getPort()==8080))throw new ApiException(403,"ORIGIN_DENIED","请求来源不受支持");}catch(IllegalArgumentException e){throw new ApiException(403,"ORIGIN_DENIED","请求来源不受支持");}}
}
