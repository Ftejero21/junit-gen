package com.junitgen.escaneo;

import com.junitgen.util.Textos;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Cómo crear un objeto de prueba de un tipo de datos del proyecto (record, builder, setters, constructor o enum)
 * con valores constantes de reglas cerradas. Nunca copia texto del proyecto salvo nombres de tipos y miembros.
 */
public final class FabricaObjetos {

    public static final String RECORD = "record";
    public static final String BUILDER = "builder";
    public static final String SETTERS = "setters";
    public static final String CONSTRUCTOR = "constructor";
    public static final String ENUM = "enum";
    public static final String NO_INSTANCIABLE = "no_instanciable";

    /** Máximo de campos del builder y de setters sugeridos. */
    static final int MAX_CAMPOS = 6;

    private static final Set<String> ANOT_ENTIDAD = Set.of("Entity", "Document", "Table", "MappedSuperclass", "Embeddable");
    private static final Set<String> ANOT_COMPONENTE = Set.of("Service", "Component", "Repository", "Controller",
            "RestController", "Configuration", "ControllerAdvice", "RestControllerAdvice");

    /** Fábrica del tipo: modo, expresión de creación (null si no instanciable), setters sugeridos y si es entidad. */
    public record Fabrica(String modo, String crear, List<String> setters, boolean entidad) {
    }

    private FabricaObjetos() {
    }

    /** Fábrica del tipo; null para interfaces y anotaciones. */
    public static Fabrica de(InfoClase t, IndiceTipos idx, int java) {
        if (t == null || t.tipo == null || t.tipo == InfoClase.Tipo.INTERFAZ || t.tipo == InfoClase.Tipo.ANOTACION) {
            return null;
        }
        boolean entidad = t.tieneAnotacion(ANOT_ENTIDAD.toArray(new String[0]));
        String nombre = nombreEscrito(t);
        if (t.tipo == InfoClase.Tipo.ENUM) {
            if (t.constantesEnum.isEmpty()) {
                return new Fabrica(NO_INSTANCIABLE, null, List.of(), entidad);
            }
            return new Fabrica(ENUM, nombre + "." + t.constantesEnum.get(0), List.of(), entidad);
        }
        if (t.tipo == InfoClase.Tipo.RECORD) {
            List<String> args = new ArrayList<>();
            if (!t.constructores.isEmpty()) {
                for (InfoClase.Parametro p : t.constructores.get(0).parametros) {
                    String v = valor(p.tipo(), t, idx, java);
                    args.add(v == null ? "null" : v);
                }
            }
            return new Fabrica(RECORD, "new " + nombre + "(" + String.join(", ", args) + ")", List.of(), entidad);
        }
        if (t.abstracta) {
            return new Fabrica(NO_INSTANCIABLE, null, List.of(), entidad);
        }
        if (t.lombok("Builder") || t.lombok("SuperBuilder") || tieneMetodoBuilder(t)) {
            StringBuilder sb = new StringBuilder(nombre).append(".builder()");
            int n = 0;
            for (InfoClase.InfoCampo f : t.campos) {
                if (n >= MAX_CAMPOS) {
                    break;
                }
                if (f.estatico() || (f.esFinal() && f.inicializador() != null)) {
                    continue;
                }
                String v = valor(f.tipo(), t, idx, java);
                if (v != null) {
                    sb.append('.').append(f.nombre()).append('(').append(v).append(')');
                    n++;
                }
            }
            return new Fabrica(BUILDER, sb.append(".build()").toString(), List.of(), entidad);
        }
        boolean sinArgs = t.constructores.isEmpty()
                || t.constructores.stream().anyMatch(k -> k.parametros.isEmpty() && !k.privado);
        if (sinArgs) {
            List<String[]> candidatos = setters(t);
            if (!candidatos.isEmpty()) {
                List<String> setters = new ArrayList<>();
                for (String[] s : candidatos) {
                    if (setters.size() >= MAX_CAMPOS) {
                        break;
                    }
                    String v = valor(s[1], t, idx, java);
                    if (v != null) {
                        setters.add(s[0] + "(" + v + ")");
                    }
                }
                return new Fabrica(SETTERS, "new " + nombre + "()", List.copyOf(setters), entidad);
            }
        }
        InfoClase.InfoConstructor menor = null;
        for (InfoClase.InfoConstructor k : t.constructores) {
            if (!k.privado && !k.parametros.isEmpty() && (menor == null || k.parametros.size() < menor.parametros.size())) {
                menor = k;
            }
        }
        if (menor != null) {
            List<String> args = new ArrayList<>();
            for (InfoClase.Parametro p : menor.parametros) {
                String v = valor(p.tipo(), t, idx, java);
                args.add(v == null ? "null" : v);
            }
            return new Fabrica(CONSTRUCTOR, "new " + nombre + "(" + String.join(", ", args) + ")", List.of(), entidad);
        }
        if (sinArgs) {
            // Solo constructor sin argumentos y sin setters: se instancia tal cual.
            return new Fabrica(CONSTRUCTOR, "new " + nombre + "()", List.of(), entidad);
        }
        return new Fabrica(NO_INSTANCIABLE, null, List.of(), entidad);
    }

    /** Nombre con el que se escribe el tipo en un test: {@code Contenedora.Simple} si es anidado. */
    static String nombreEscrito(InfoClase t) {
        return t.contenedora != null ? t.contenedora.simple + "." + t.simple : t.simple;
    }

    private static boolean tieneMetodoBuilder(InfoClase t) {
        for (InfoMetodo m : t.metodos) {
            if (m.estatico && m.nombre.equals("builder") && m.parametros.isEmpty() && !m.privado) {
                return true;
            }
        }
        return false;
    }

    /** Setters disponibles como {nombre del setter, tipo del parámetro}, en orden de declaración. */
    private static List<String[]> setters(InfoClase t) {
        List<String[]> r = new ArrayList<>();
        if (t.lombok("Data") || t.lombok("Setter")) {
            for (InfoClase.InfoCampo f : t.campos) {
                if (!f.estatico() && !f.esFinal() && !f.nombre().isEmpty()) {
                    r.add(new String[]{"set" + Character.toUpperCase(f.nombre().charAt(0)) + f.nombre().substring(1), f.tipo()});
                }
            }
        }
        for (InfoMetodo m : t.metodos) {
            if (m.publico && !m.estatico && m.parametros.size() == 1 && m.nombre.length() > 3 && m.nombre.startsWith("set")
                    && Character.isUpperCase(m.nombre.charAt(3)) && r.stream().noneMatch(s -> s[0].equals(m.nombre))) {
                r.add(new String[]{m.nombre, m.parametros.get(0).tipo()});
            }
        }
        return r;
    }

    /** Valor constante para un tipo (reglas cerradas); null si no se conoce. */
    public static String valor(String tipo, InfoClase ctx, IndiceTipos idx, int java) {
        String simple = valorSimple(tipo, java);
        if (simple != null || tipo == null) {
            return simple;
        }
        String t = tipo.trim();
        if (t.contains("[") || t.endsWith("...")) {
            return null;
        }
        String fqn = idx == null ? null : idx.resolverFqn(t, ctx);
        InfoClase delProyecto = idx == null ? null : idx.resolverClase(t, ctx);
        if (delProyecto == null && idx != null) {
            delProyecto = idx.resolverAnidada(t, ctx);
        }
        RecetasMontaje.Real real = RecetasMontaje.real(t, fqn != null && fqn.contains(".") ? fqn : null, delProyecto != null);
        if (real != null) {
            return real.expresion();
        }
        if (delProyecto != null && delProyecto.tipo == InfoClase.Tipo.ENUM && !delProyecto.constantesEnum.isEmpty()) {
            return nombreEscrito(delProyecto) + "." + delProyecto.constantesEnum.get(0);
        }
        return null;
    }

    /** Valores de primitivos, String, números grandes, colecciones vacías y Optional; null si no aplica. */
    static String valorSimple(String tipo, int java) {
        if (tipo == null) {
            return null;
        }
        String t = tipo.trim();
        if (t.contains("[") || t.endsWith("...")) {
            return null;
        }
        String base = Textos.quitarGenericos(t).trim();
        String s = Textos.nombreSimple(base);
        if (base.contains(".") && !base.startsWith("java.")) {
            return null;
        }
        boolean j9 = java >= 9;
        return switch (s) {
            case "String" -> "\"texto\"";
            case "long", "Long" -> "1L";
            case "int", "Integer", "short", "Short", "byte", "Byte" -> "1";
            case "double", "Double" -> "1.0";
            case "float", "Float" -> "1.0f";
            case "boolean", "Boolean" -> "true";
            case "char", "Character" -> "'a'";
            case "BigDecimal" -> "BigDecimal.ONE";
            case "BigInteger" -> "BigInteger.ONE";
            case "List", "Collection", "Iterable" -> j9 ? "List.of()" : "Collections.emptyList()";
            case "Set" -> j9 ? "Set.of()" : "Collections.emptySet()";
            case "Map" -> j9 ? "Map.of()" : "Collections.emptyMap()";
            case "Optional" -> "Optional.empty()";
            default -> null;
        };
    }

    /**
     * true si es un tipo de datos (DTO, entidad, record o enum) de main: no es test, ni servicio detectado, ni
     * componente Spring; RECORD, ENUM o CLASE con algún campo de instancia.
     */
    public static boolean esTipoDeDatos(InfoClase t, ModuloEscaneado me) {
        if (t == null || t.esTest || t.tipo == null) {
            return false;
        }
        boolean forma = switch (t.tipo) {
            case RECORD, ENUM -> true;
            case CLASE -> t.campos.stream().anyMatch(f -> !f.estatico());
            default -> false;
        };
        if (!forma || t.tieneAnotacion(ANOT_COMPONENTE.toArray(new String[0]))) {
            return false;
        }
        return me == null || me.deteccion == null || me.candidato(t.fqn) == null;
    }
}
