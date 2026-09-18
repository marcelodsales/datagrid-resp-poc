package com.redhat.demo.cache;

import java.util.Map;
import java.util.Set;

public interface CacheService {

    void put(String key, String value);

    String get(String key);

    void delete(String key);

    Set<String> keys();

    Map<String, String> info();
}
