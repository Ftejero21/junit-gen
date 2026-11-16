package com.junitgen.escaneo;

import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.junitgen.proyecto.VersionesProyecto;
import com.junitgen.spec.EstiloSpec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Deduce la estructura común de los tests existentes (porcentajes de estilo y extractos). */
public final class AnalizadorEstiloTests {

    private static final Pattern GWT = Pattern.compile("(?im)^\\s*//\\s*(given|when|then|arrange|act|assert)\\b");

    public static class Extracto {
        public String clase;
        public String archivo;
        public String codigo;
    }

    public static class EstiloTests {
        public int totalTests;
        public int unitarios;
        public int springBoot;
        public Map<String, Integer> porcentajes = new LinkedHashMap<>();
        public String patronNombre;
        public String aserciones;
        public String visibilidad;
        public String nombreCampoSut;
        public boolean displayName;
        public boolean comentariosGwt;
        public String anotacionClase;
        public List<Extracto> extractos = new ArrayList<>();
        public EstiloSpec sugerencia;
    }

    private AnalizadorEstiloTests() {
    }

    public static EstiloTests analizar(IndiceTipos idx, VersionesProyecto v) {
        EstiloTests e = new EstiloTests();
        List<InfoClase> tests = new ArrayList<>();
        for (InfoClase t : idx.clasesTest()) {
            if (t.tipo == InfoClase.Tipo.CLASE && t.decl.findAll(MethodDeclaration.class).stream().anyMatch(EmparejadorTests::esMetodoTest)) {
                tests.add(t);
            }
        }
        e.totalTests = tests.size();
        int j5 = 0, j4 = 0, ext = 0, runner = 0, open = 0, inject = 0, display = 0, gwt = 0, assertj = 0,
                junitAs = 0, pub = 0, guion = 0, should = 0, prefTest = 0, metodos = 0;
        Map<String, Integer> nombresSut = new LinkedHashMap<>();
        List<InfoClase> unitarios = new ArrayList<>();
        for (InfoClase t : tests) {
            String texto = leer(t);
            boolean spring = EmparejadorTests.esSpringTest(t);
            if (spring) {
                e.springBoot++;
            } else {
                e.unitarios++;
                unitarios.add(t);
            }
            List<String> imps = new ArrayList<>(t.imports);
            imps.addAll(t.importsComodin);
            imps.addAll(t.importsEstaticos);
            if (imps.stream().anyMatch(i -> i.startsWith("org.junit.jupiter"))) {
                j5++;
            } else if (imps.stream().anyMatch(i -> i.startsWith("org.junit"))) {
                j4++;
            }
            if (texto.contains("MockitoExtension")) {
                ext++;
            }
            if (texto.contains("MockitoJUnitRunner")) {
                runner++;
            }
            if (texto.contains("openMocks") || texto.contains("initMocks")) {
                open++;
            }
            boolean tieneInject = false;
            for (FieldDeclaration f : t.decl.findAll(FieldDeclaration.class)) {
                if (InfoClase.nombresAnotaciones(f.getAnnotations()).contains("InjectMocks")) {
                    tieneInject = true;
                    f.getVariables().forEach(var -> nombresSut.merge(var.getNameAsString(), 1, Integer::sum));
                }
            }
            if (tieneInject) {
                inject++;
            }
            if (texto.contains("@DisplayName")) {
                display++;
            }
            if (GWT.matcher(texto).find()) {
                gwt++;
            }
            if (imps.stream().anyMatch(i -> i.startsWith("org.assertj"))) {
                assertj++;
            } else if (imps.stream().anyMatch(i -> i.startsWith("org.junit.jupiter.api.Assertions") || i.startsWith("org.junit.Assert"))) {
                junitAs++;
            }
            if (t.decl.isPublic()) {
                pub++;
            }
            for (MethodDeclaration md : t.decl.findAll(MethodDeclaration.class)) {
                if (!EmparejadorTests.esMetodoTest(md)) {
                    continue;
                }
                metodos++;
                String n = md.getNameAsString();
                if (n.startsWith("should")) {
                    should++;
                } else if (n.startsWith("test")) {
                    prefTest++;
                } else if (n.contains("_")) {
                    guion++;
                }
            }
        }
        int n = Math.max(1, tests.size());
        e.porcentajes.put("junit5", pct(j5, n));
        e.porcentajes.put("junit4", pct(j4, n));
        e.porcentajes.put("mockitoExtension", pct(ext, n));
        e.porcentajes.put("mockitoRunner", pct(runner, n));
        e.porcentajes.put("openMocks/initMocks", pct(open, n));
        e.porcentajes.put("injectMocks", pct(inject, n));
        e.porcentajes.put("displayName", pct(display, n));
        e.porcentajes.put("comentariosGwt", pct(gwt, n));
        e.porcentajes.put("asercionesAssertj", pct(assertj, n));
        e.porcentajes.put("asercionesJunit", pct(junitAs, n));
        e.porcentajes.put("clasePublica", pct(pub, n));
        int nm = Math.max(1, metodos);
        e.porcentajes.put("nombre_metodo_escenario", pct(guion, nm));
        e.porcentajes.put("nombre_should", pct(should, nm));
        e.porcentajes.put("nombre_test", pct(prefTest, nm));

        if (tests.isEmpty()) {
            e.patronNombre = "{metodo}_{escenario}";
        } else if (should > guion && should > prefTest) {
            e.patronNombre = "should{escenario}";
        } else if (prefTest > guion && prefTest > should) {
            e.patronNombre = "test_{metodo}_{escenario}";
        } else {
            e.patronNombre = "{metodo}_{escenario}";
        }
        if (assertj > junitAs) {
            e.aserciones = "assertj";
        } else if (junitAs > 0) {
            e.aserciones = "junit";
        } else {
            e.aserciones = v.assertj ? "assertj" : "junit";
        }
        int junit = v.junitElegido;
        e.visibilidad = tests.isEmpty() ? (junit == 4 ? "public" : "package") : (pub * 2 > tests.size() ? "public" : "package");
        if (junit == 4) {
            e.visibilidad = "public";
        }
        int maximo = nombresSut.values().stream().max(Integer::compare).orElse(0);
        if (nombresSut.getOrDefault("service", 0) == maximo && maximo > 0) {
            e.nombreCampoSut = "service";
        } else {
            e.nombreCampoSut = nombresSut.entrySet().stream().filter(x -> x.getValue() == maximo).map(Map.Entry::getKey)
                    .findFirst().orElse("service");
        }
        e.displayName = display * 2 > tests.size() && !tests.isEmpty();
        e.comentariosGwt = gwt * 2 > tests.size() && !tests.isEmpty();
        e.anotacionClase = ext >= runner && ext >= open && ext > 0 ? "@ExtendWith(MockitoExtension.class)"
                : runner > 0 && runner >= open ? "@RunWith(MockitoJUnitRunner.class)"
                : open > 0 ? "MockitoAnnotations.openMocks(this)" : "(sin tests de ejemplo)";
        unitarios.sort((a, b) -> {
            boolean ia = a.decl.findAll(FieldDeclaration.class).stream().anyMatch(f -> InfoClase.nombresAnotaciones(f.getAnnotations()).contains("InjectMocks"));
            boolean ib = b.decl.findAll(FieldDeclaration.class).stream().anyMatch(f -> InfoClase.nombresAnotaciones(f.getAnnotations()).contains("InjectMocks"));
            if (ia != ib) {
                return ia ? -1 : 1;
            }
            boolean sa = a.simple.matches(".*(Service|ServiceImpl|Manager|UseCase|Facade|Handler)Test");
            boolean sb = b.simple.matches(".*(Service|ServiceImpl|Manager|UseCase|Facade|Handler)Test");
            if (sa != sb) {
                return sa ? -1 : 1;
            }
            return a.fqn.compareTo(b.fqn);
        });
        for (InfoClase t : unitarios) {
            if (e.extractos.size() >= 2) {
                break;
            }
            Extracto x = new Extracto();
            x.clase = t.fqn;
            x.archivo = t.archivo.toString();
            String[] lineas = leer(t).split("\\R", -1);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.min(60, lineas.length); i++) {
                sb.append(lineas[i]).append('\n');
            }
            if (lineas.length > 60) {
                sb.append("// … (").append(lineas.length - 60).append(" líneas más)\n");
            }
            x.codigo = sb.toString();
            e.extractos.add(x);
        }
        EstiloSpec s = new EstiloSpec();
        s.patronNombre = e.patronNombre;
        s.displayName = e.displayName && junit == 5;
        s.comentariosGwt = e.comentariosGwt;
        s.aserciones = e.aserciones;
        s.visibilidadClase = e.visibilidad;
        s.nombreCampoSut = e.nombreCampoSut;
        e.sugerencia = s;
        return e;
    }

    private static int pct(int a, int n) {
        return Math.round(a * 100f / n);
    }

    static String leer(InfoClase t) {
        try {
            return Files.readString(t.archivo, StandardCharsets.UTF_8);
        } catch (IOException e) {
            try {
                return new String(Files.readAllBytes(t.archivo), StandardCharsets.ISO_8859_1);
            } catch (IOException e2) {
                return "";
            }
        }
    }
}
