package com.redhat.demo.cache.repository;

import com.redhat.demo.cache.model.MedicalSpecialty;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.Map;

@Repository
public class SpecialtyRepository {

    private static final Map<String, MedicalSpecialty> DATA = new LinkedHashMap<>();

    static {
        DATA.put("CAR", new MedicalSpecialty("CAR", "Cardiology", "Internal Medicine"));
        DATA.put("NEU", new MedicalSpecialty("NEU", "Neurology", "Neuroscience"));
        DATA.put("PED", new MedicalSpecialty("PED", "Pediatrics", "Maternal Care"));
        DATA.put("TRA", new MedicalSpecialty("TRA", "Orthopedics", "Surgery"));
        DATA.put("ONC", new MedicalSpecialty("ONC", "Oncology", "Internal Medicine"));
    }

    private int lookupCount = 0;

    @Cacheable(value = "app-config", key = "#code")
    public MedicalSpecialty findByCode(String code) {
        lookupCount++;
        simulateSlowLookup();
        return DATA.get(code);
    }

    @CachePut(value = "app-config", key = "#specialty.code")
    public MedicalSpecialty save(MedicalSpecialty specialty) {
        DATA.put(specialty.code(), specialty);
        return specialty;
    }

    @CacheEvict(value = "app-config", key = "#code")
    public void evict(String code) {
        // cache entry removed; data stays in "database"
    }

    public Map<String, MedicalSpecialty> findAll() {
        return Map.copyOf(DATA);
    }

    public int getLookupCount() {
        return lookupCount;
    }

    public void resetLookupCount() {
        lookupCount = 0;
    }

    private void simulateSlowLookup() {
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
