package com.lucklotter.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/** Liveness/wiring check used during scaffolding. */
@RestController
@RequestMapping("/api")
public class PingController {

    @GetMapping("/ping")
    public Map<String, Object> ping() {
        return Map.of(
            "service", "lucklotter-backend",
            "status", "ok",
            "time", Instant.now().toString()
        );
    }
}
