package com.redhat.demo.cache.controller;

import com.redhat.demo.cache.model.MedicalSpecialty;
import com.redhat.demo.cache.repository.SpecialtyRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/specialties")
public class SpecialtyController {

    private final SpecialtyRepository repository;

    public SpecialtyController(SpecialtyRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/{code}")
    public ResponseEntity<MedicalSpecialty> get(@PathVariable String code) {
        MedicalSpecialty specialty = repository.findByCode(code);
        if (specialty == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(specialty);
    }

    @PutMapping
    public MedicalSpecialty save(@RequestBody MedicalSpecialty specialty) {
        return repository.save(specialty);
    }

    @DeleteMapping("/{code}/cache")
    public ResponseEntity<Void> evict(@PathVariable String code) {
        repository.evict(code);
        return ResponseEntity.noContent().build();
    }

    @GetMapping
    public Map<String, MedicalSpecialty> listAll() {
        return repository.findAll();
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return Map.of(
                "lookupCount", repository.getLookupCount(),
                "description", "lookupCount increments only on cache MISS (actual data lookup)"
        );
    }

    @DeleteMapping("/stats")
    public ResponseEntity<Void> resetStats() {
        repository.resetLookupCount();
        return ResponseEntity.noContent().build();
    }
}
