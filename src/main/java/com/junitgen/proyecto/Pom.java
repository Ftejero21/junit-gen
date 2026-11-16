package com.junitgen.proyecto;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Modelo mínimo de un pom.xml (sin perfiles). */
public class Pom {

    public record Dep(String groupId, String artifactId, String version, String scope, String type) {
        public String clave() {
            return groupId + ":" + artifactId;
        }
    }

    public Path archivo;
    public String groupId;
    public String artifactId;
    public String version;
    public String packaging;
    public String parentGroupId;
    public String parentArtifactId;
    public String parentVersion;
    /** null = valor por defecto (../pom.xml); "" = sin relativePath. */
    public String parentRelativePath;
    public final Map<String, String> propiedades = new LinkedHashMap<>();
    public final List<Dep> dependencias = new ArrayList<>();
    public final List<Dep> gestion = new ArrayList<>();
    public final List<String> modulos = new ArrayList<>();
    public String sourceDirectory;
    public String testSourceDirectory;
    public String compilerRelease;
    public String compilerSource;
    public String compilerTarget;

    public boolean tieneParent() {
        return parentArtifactId != null;
    }

    public String groupIdEfectivo() {
        return groupId != null ? groupId : parentGroupId;
    }

    public String versionEfectiva() {
        return version != null ? version : parentVersion;
    }
}
