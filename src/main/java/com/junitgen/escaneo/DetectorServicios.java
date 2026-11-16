package com.junitgen.escaneo;

import com.junitgen.config.ConfigProyecto;
import com.junitgen.util.Textos;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Detección de servicios por puntuación (con o sin anotaciones). Umbral por defecto 40; entre
 * umbral-10 y umbral-1 se listan como dudosos. {@code incluir} del yml fuerza la inclusión.
 */
public final class DetectorServicios {

    public static final Set<String> ANOT_SERVICIO = Set.of("Service", "Component", "Stateless", "Named", "Stateful",
            "Singleton", "ApplicationScoped");
    public static final List<String> SUFIJOS_NOMBRE = List.of("ServiceImpl", "Service", "Manager", "UseCase", "Facade",
            "Business", "Bo", "Handler");
    public static final Set<String> SEGMENTOS_PAQUETE = Set.of("service", "services", "business", "application",
            "usecase", "core", "logic");
    public static final List<String> SUFIJOS_DEPENDENCIA = List.of("Repository", "Repo", "Dao", "DAO", "Client", "Gateway");
    private static final Set<String> ANOT_ENTIDAD = Set.of("Entity", "Embeddable", "MappedSuperclass", "Document", "Table");
    private static final Set<String> ANOT_CONTROLADOR = Set.of("Controller", "RestController", "ControllerAdvice",
            "RestControllerAdvice", "Path", "WebServlet");
    private static final Set<String> ANOT_CONFIG = Set.of("Configuration", "ConfigurationProperties", "SpringBootApplication");

    public static class Candidato {
        public InfoClase clase;
        public int puntuacion;
        public String motivo;
        public boolean forzado;
    }

    public static class Resultado {
        public final List<Candidato> servicios = new ArrayList<>();
        public final List<Candidato> dudosos = new ArrayList<>();
        public final Map<String, Integer> descartes = new LinkedHashMap<>();
    }

    private DetectorServicios() {
    }

    public static Resultado detectar(IndiceTipos idx, RegistroBeans beans, ConfigProyecto cfg) {
        Resultado r = new Resultado();
        int umbral = cfg.umbral();
        for (InfoClase c : idx.clasesMain()) {
            boolean forzado = Textos.coincideAlguno(cfg.incluirClases, c.fqn) || Textos.coincideAlguno(cfg.incluirClases, c.simple)
                    || Textos.coincideAlguno(cfg.incluirPaquetes, c.paquete);
            String causa = causaDescarte(c, idx, beans, cfg, forzado);
            if (causa != null) {
                r.descartes.merge(causa, 1, Integer::sum);
                continue;
            }
            Candidato cand = puntuar(c, idx, beans);
            cand.forzado = forzado;
            if (forzado) {
                cand.motivo = cand.motivo + " [incluida por junit-gen.yml]";
                r.servicios.add(cand);
            } else if (cand.puntuacion >= umbral) {
                r.servicios.add(cand);
            } else if (cand.puntuacion >= umbral - 10) {
                r.dudosos.add(cand);
            } else {
                r.descartes.merge("puntuación baja (< " + (umbral - 10) + ")", 1, Integer::sum);
            }
        }
        r.servicios.sort((a, b) -> b.puntuacion != a.puntuacion ? Integer.compare(b.puntuacion, a.puntuacion)
                : a.clase.fqn.compareTo(b.clase.fqn));
        r.dudosos.sort((a, b) -> Integer.compare(b.puntuacion, a.puntuacion));
        return r;
    }

    static String causaDescarte(InfoClase c, IndiceTipos idx, RegistroBeans beans, ConfigProyecto cfg, boolean forzado) {
        if (c.tipo == InfoClase.Tipo.INTERFAZ) {
            return "interfaz";
        }
        if (c.tipo == InfoClase.Tipo.ANOTACION) {
            return "anotación";
        }
        if (c.tipo == InfoClase.Tipo.ENUM) {
            return "enum";
        }
        if (c.abstracta) {
            return "clase abstracta";
        }
        if (c.tipo == InfoClase.Tipo.RECORD && c.metodos.stream().allMatch(InfoMetodo::esAccessor)) {
            return "record de datos";
        }
        if (Textos.coincideAlguno(cfg.excluirClases, c.fqn) || Textos.coincideAlguno(cfg.excluirClases, c.simple)
                || Textos.coincideAlguno(cfg.excluirPaquetes, c.paquete)) {
            return "excluida por junit-gen.yml";
        }
        if (forzado) {
            return null;
        }
        if (esExcepcion(c)) {
            return "excepción";
        }
        if (esEntidad(c)) {
            return "entidad";
        }
        if (esControlador(c)) {
            return "controller";
        }
        if (esRepositorio(c)) {
            return "repositorio/DAO";
        }
        if (esConfiguracion(c)) {
            return "configuración";
        }
        if (c.tieneMain()) {
            return "clase main";
        }
        boolean anotada = tieneAnotacionServicio(c);
        if (!anotada && beans.origen(c.fqn) == null && esDto(c)) {
            return "DTO / datos";
        }
        return null;
    }

    public static boolean esExcepcion(InfoClase c) {
        if (c.simple.endsWith("Exception") || c.simple.endsWith("Error")) {
            return true;
        }
        for (String e : c.extiende) {
            if (e.endsWith("Exception") || e.equals("Throwable") || e.endsWith("Error")) {
                return true;
            }
        }
        return false;
    }

    public static boolean esEntidad(InfoClase c) {
        for (String a : c.anotaciones) {
            if (ANOT_ENTIDAD.contains(a)) {
                return true;
            }
        }
        return false;
    }

    public static boolean esControlador(InfoClase c) {
        for (String a : c.anotaciones) {
            if (ANOT_CONTROLADOR.contains(a)) {
                return true;
            }
        }
        return c.simple.endsWith("Controller");
    }

    public static boolean esRepositorio(InfoClase c) {
        if (c.anotaciones.contains("Repository")) {
            return true;
        }
        for (String s : new String[]{"Repository", "Repo", "Dao", "DAO", "DaoImpl", "RepositoryImpl"}) {
            if (c.simple.endsWith(s)) {
                return true;
            }
        }
        for (String e : c.extiende) {
            if (e.endsWith("Repository")) {
                return true;
            }
        }
        for (String i : c.implementa) {
            if (i.endsWith("Repository")) {
                return true;
            }
        }
        return false;
    }

    public static boolean esConfiguracion(InfoClase c) {
        for (String a : c.anotaciones) {
            if (ANOT_CONFIG.contains(a) || (a.startsWith("Enable") && a.length() > 6 && Character.isUpperCase(a.charAt(6)))) {
                return true;
            }
        }
        return false;
    }

    public static boolean tieneAnotacionServicio(InfoClase c) {
        for (String a : c.anotaciones) {
            if (ANOT_SERVICIO.contains(a)) {
                return true;
            }
        }
        return c.anotaciones.contains("Transactional")
                || c.metodosPublicos().stream().anyMatch(m -> m.anotaciones.contains("Transactional"));
    }

    /** Solo campos y accessors, o Lombok @Data/@Value/@Builder sin lógica. */
    public static boolean esDto(InfoClase c) {
        if (c.tipo == InfoClase.Tipo.RECORD) {
            return c.metodos.stream().allMatch(InfoMetodo::esAccessor);
        }
        boolean sinLogica = c.metodos.stream().allMatch(m -> m.esAccessor() || m.estatico && m.nombre.equals("builder"));
        if (sinLogica) {
            return true;
        }
        if (c.lombok("Data") || c.lombok("Value") || c.lombok("Builder")) {
            Set<String> campos = new LinkedHashSet<>();
            c.campos.forEach(f -> campos.add(f.nombre()));
            return c.metodos.stream().noneMatch(m -> !m.esAccessor() && m.tieneLogica(campos));
        }
        return false;
    }

    /** Clase de datos o tipo no colaborador (DTO, entidad, excepción, enum). */
    public static boolean esDatos(InfoClase c) {
        return c.tipo == InfoClase.Tipo.ENUM || c.tipo == InfoClase.Tipo.RECORD || esEntidad(c) || esExcepcion(c)
                || (!tieneAnotacionServicio(c) && esDto(c));
    }

    static Candidato puntuar(InfoClase c, IndiceTipos idx, RegistroBeans beans) {
        Candidato cand = new Candidato();
        cand.clase = c;
        List<String> motivos = new ArrayList<>();
        int total = 0;
        String anot = null;
        for (String a : c.anotaciones) {
            if (ANOT_SERVICIO.contains(a) || a.equals("Transactional")) {
                anot = "@" + a;
                break;
            }
        }
        if (anot == null && c.metodosPublicos().stream().anyMatch(m -> m.anotaciones.contains("Transactional"))) {
            anot = "@Transactional en método";
        }
        if (anot != null) {
            total += 50;
            motivos.add(anot + " (+50)");
        } else if (beans.origen(c.fqn) != null) {
            total += 50;
            motivos.add("bean " + beans.origen(c.fqn) + " (+50)");
        }
        String base = c.simple;
        for (String suf : SUFIJOS_NOMBRE) {
            if (base.endsWith(suf) || (base.endsWith("Impl") && base.substring(0, base.length() - 4).endsWith(suf))) {
                total += 20;
                motivos.add("nombre *" + (base.endsWith("Impl") && !suf.endsWith("Impl") ? suf + "Impl" : suf) + " (+20)");
                break;
            }
        }
        for (String seg : c.paquete.split("\\.")) {
            if (SEGMENTOS_PAQUETE.contains(seg.toLowerCase(Locale.ROOT))) {
                total += 20;
                motivos.add("paquete " + seg + " (+20)");
                break;
            }
        }
        String sinImpl = c.simple.endsWith("Impl") ? c.simple.substring(0, c.simple.length() - 4) : c.simple;
        for (String i : c.implementa) {
            boolean relacionada = i.equals(sinImpl);
            if (!relacionada) {
                for (String suf : SUFIJOS_NOMBRE) {
                    if (i.endsWith(suf) && sinImpl.endsWith(suf)) {
                        relacionada = true;
                        break;
                    }
                }
            }
            if (relacionada) {
                total += 10;
                motivos.add("implementa " + i + " (+10)");
                break;
            }
        }
        String dep = dependenciaColaboradora(c);
        if (dep != null) {
            total += 10;
            motivos.add("depende de " + dep + " (+10)");
        }
        Set<String> camposDep = new LinkedHashSet<>();
        for (InfoClase.InfoCampo f : c.campos) {
            if (!f.estatico()) {
                camposDep.add(f.nombre());
            }
        }
        boolean logica = c.metodosPublicos().stream().anyMatch(m -> !m.esAccessor() && m.tieneLogica(camposDep));
        if (logica) {
            total += 20;
            motivos.add("lógica real (+20)");
        }
        cand.puntuacion = total;
        cand.motivo = (motivos.isEmpty() ? "sin señales" : String.join(", ", motivos)) + " = " + total;
        return cand;
    }

    private static String dependenciaColaboradora(InfoClase c) {
        List<String> tipos = new ArrayList<>();
        c.campos.stream().filter(f -> !f.estatico()).forEach(f -> tipos.add(Textos.nombreSimple(f.tipo())));
        c.constructores.forEach(k -> k.parametros.forEach(p -> tipos.add(Textos.nombreSimple(p.tipo()))));
        for (String t : tipos) {
            for (String suf : SUFIJOS_DEPENDENCIA) {
                if (t.endsWith(suf)) {
                    return t;
                }
            }
        }
        return null;
    }
}
