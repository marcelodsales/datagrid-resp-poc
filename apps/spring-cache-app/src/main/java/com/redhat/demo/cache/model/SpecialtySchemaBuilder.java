package com.redhat.demo.cache.model;

import org.infinispan.protostream.GeneratedSchema;
import org.infinispan.protostream.annotations.ProtoSchema;

@ProtoSchema(includeClasses = MedicalSpecialty.class, schemaPackageName = "demo")
public interface SpecialtySchemaBuilder extends GeneratedSchema {
}
