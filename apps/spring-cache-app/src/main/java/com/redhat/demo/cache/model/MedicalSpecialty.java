package com.redhat.demo.cache.model;

import org.infinispan.protostream.annotations.Proto;

@Proto
public record MedicalSpecialty(String code, String name, String department) {
}
