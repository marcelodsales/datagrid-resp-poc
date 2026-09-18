package com.redhat.demo.redis.controller;

import com.redhat.demo.redis.service.CacheEventService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class SseController {

    private final CacheEventService eventService;

    public SseController(CacheEventService eventService) {
        this.eventService = eventService;
    }

    @GetMapping(path = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events() {
        return eventService.register();
    }

    @GetMapping("/snapshot")
    public List<Map<String, Object>> snapshot() {
        return eventService.snapshot();
    }
}
