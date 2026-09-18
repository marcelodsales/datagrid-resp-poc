package com.redhat.demo.session.controller;

import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/session")
public class SessionController {

    @GetMapping
    public Map<String, Object> getSession(HttpSession session) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", session.getId());
        result.put("creationTime", Instant.ofEpochMilli(session.getCreationTime()).toString());
        result.put("lastAccessedTime", Instant.ofEpochMilli(session.getLastAccessedTime()).toString());
        result.put("maxInactiveInterval", session.getMaxInactiveInterval());

        Map<String, Object> attributes = new LinkedHashMap<>();
        Enumeration<String> names = session.getAttributeNames();
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            attributes.put(name, session.getAttribute(name));
        }
        result.put("attributes", attributes);
        return result;
    }

    @PutMapping("/attributes/{key}")
    public Map<String, Object> setAttribute(@PathVariable String key,
                                            @RequestBody String value,
                                            HttpSession session) {
        session.setAttribute(key, value);
        return Map.of("sessionId", session.getId(), "key", key, "value", value);
    }

    @GetMapping("/attributes/{key}")
    public Map<String, Object> getAttribute(@PathVariable String key, HttpSession session) {
        Object value = session.getAttribute(key);
        return Map.of("sessionId", session.getId(), "key", key, "value", value != null ? value : "");
    }

    @DeleteMapping
    public Map<String, String> invalidate(HttpSession session) {
        String id = session.getId();
        session.invalidate();
        return Map.of("invalidated", id);
    }
}
