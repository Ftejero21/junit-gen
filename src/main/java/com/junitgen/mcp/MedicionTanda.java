package com.junitgen.mcp;

import com.junitgen.generacion.EscritorInforme;
import com.junitgen.generacion.Tanda;
import com.junitgen.util.Textos;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Medición de la tanda: tiempo (de la IA entre llamadas + ejecución de la herramienta) y número de llamadas por servicio,
 * con contador por herramienta. Se acumula solo en memoria en {@link Tanda#mediciones} (bajo el monitor de la tanda) y se
 * persiste cuando otra herramienta guarda la tanda; al cerrarla se añade a informe.md la sección «Medición de la tanda».
 */
final class MedicionTanda {

    /** Clave de lo que no es de ningún servicio (escaneo, cierre…). */
    static final String TANDA = "(tanda)";
    /** Un hueco mayor entre llamadas no se atribuye (pausa): solo cuenta la duración de la llamada. */
    static final long MAX_HUECO_MS = 600_000;
    static final String SELECCIONAR = "seleccionar_funciones";
    static final String DETALLE = "detalle_servicio";
    static final String GENERAR = "generar_tests";
    static final String VALIDAR = "validar_spec";
    static final List<String> HERRAMIENTAS_TABLA = List.of(SELECCIONAR, DETALLE, GENERAR, VALIDAR);
    static final String ARREGLAR = "arreglar_caso";
    static final String ANALISIS_IA = "registrar_analisis_ia";
    static final String EJECUTAR = "ejecutar_tests";
    /** Herramientas con columna propia en la tabla «Fase de arreglo». */
    static final List<String> HERRAMIENTAS_ARREGLO = List.of(ARREGLAR, ANALISIS_IA, EJECUTAR, DETALLE);
    static final String FILA_TOTAL = "Total";
    static final double BYTES_KB = 1024.0;
    static final long MS_SEGUNDO = 1000;
    static final long SEGUNDOS_MINUTO = 60;

    /** Servicio al que se atribuye una llamada y si es de la fase de arreglo. */
    record Cubo(String clave, boolean arreglo) {
    }

    private MedicionTanda() {
    }

    /** Servicio en curso antes de ejecutar la herramienta: el siguiente del plan, si no el primer caso por arreglar, si no la tanda. */
    static Cubo cubo(SesionMcp sesion) {
        Path proyecto = sesion.fachada().ultimaRuta();
        if (proyecto == null) {
            return new Cubo(TANDA, false);
        }
        if (sesion.bloqueLlamada() == null && BloquesTanda.activo(sesion, proyecto)) {
            // Con la tanda en bloques, lo que no es de ningún bloque (escaneo, cierre) va a la tanda.
            return new Cubo(TANDA, false);
        }
        String s = HerramientaGenerar.siguienteServicio(sesion, proyecto);
        if (s != null) {
            return new Cubo(s, false);
        }
        for (Tanda.ArregloTanda a : sesion.arreglosPendientes(proyecto)) {
            if (a != null && a.servicio != null) {
                return new Cubo(a.servicio, true);
            }
        }
        return new Cubo(TANDA, false);
    }

    /**
     * Suma la llamada al cubo en la tanda en memoria (sin tanda no hace nada). Tiempo atribuido: desde el fin de la llamada
     * anterior de la sesión hasta ahora; sin llamada anterior o con un hueco mayor que {@link #MAX_HUECO_MS}, solo la
     * duración de esta llamada.
     */
    static void registrar(SesionMcp sesion, Cubo cubo, String herramienta, boolean error, int bytes, long inicioNanos) {
        long ahora = System.nanoTime();
        long anterior = sesion.marcarFinLlamada(ahora);
        Tanda t = sesion.tanda(sesion.fachada().ultimaRuta());
        if (t == null || cubo == null) {
            return;
        }
        long duracion = Math.max(0, ahora - inicioNanos);
        long hueco = inicioNanos - anterior;
        long nanos = anterior == 0 || hueco < 0 || hueco > TimeUnit.MILLISECONDS.toNanos(MAX_HUECO_MS) ? duracion : ahora - anterior;
        long ms = TimeUnit.NANOSECONDS.toMillis(nanos);
        String instante = Instant.now().toString();
        synchronized (t) {
            if (t.mediciones == null) {
                t.mediciones = new LinkedHashMap<>();
            }
            Tanda.Medicion m = t.mediciones.computeIfAbsent(cubo.clave(), k -> new Tanda.Medicion());
            if (m.inicio == null) {
                m.inicio = instante;
            }
            m.fin = instante;
            m.ms += ms;
            m.llamadas++;
            if (error) {
                m.rechazadas++;
            }
            m.bytes += Math.max(0, bytes);
            if (cubo.arreglo()) {
                m.msArreglo += ms;
                if (m.enArreglo == null) {
                    m.enArreglo = new LinkedHashMap<>();
                }
                m.enArreglo.merge(herramienta, 1, Integer::sum);
            } else {
                if (m.porHerramienta == null) {
                    m.porHerramienta = new LinkedHashMap<>();
                }
                m.porHerramienta.merge(herramienta, 1, Integer::sum);
            }
        }
    }

    /** Tabla markdown por servicio, fila «Total» y la media por servicio (sin contar «(tanda)»). Llamar bajo el monitor de t. */
    static String cuerpoInforme(Tanda t) {
        StringBuilder sb = new StringBuilder("| Servicio | Tiempo | Llamadas | " + String.join(" | ", HERRAMIENTAS_TABLA)
                + " | Otras | Rechazadas | En arreglo | KB |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|---|---|\n");
        Tanda.Medicion total = new Tanda.Medicion();
        long msServicios = 0;
        int llamadasServicios = 0;
        int servicios = 0;
        Map<String, Tanda.Medicion> ms = t.mediciones == null ? Map.of() : t.mediciones;
        for (Map.Entry<String, Tanda.Medicion> e : ms.entrySet()) {
            Tanda.Medicion m = e.getValue();
            if (m == null) {
                continue;
            }
            String nombre = TANDA.equals(e.getKey()) ? TANDA : Textos.nombreSimple(e.getKey());
            fila(sb, nombre, m);
            total.ms += m.ms;
            total.llamadas += m.llamadas;
            total.rechazadas += m.rechazadas;
            total.bytes += m.bytes;
            if (m.porHerramienta != null) {
                m.porHerramienta.forEach((k, v) -> total.porHerramienta.merge(k, v, Integer::sum));
            }
            if (m.enArreglo != null) {
                m.enArreglo.forEach((k, v) -> total.enArreglo.merge(k, v, Integer::sum));
            }
            if (!TANDA.equals(e.getKey())) {
                servicios++;
                msServicios += m.ms;
                llamadasServicios += m.llamadas;
            }
        }
        fila(sb, "**" + FILA_TOTAL + "**", total);
        if (servicios > 0) {
            sb.append("\nMedia por servicio: ")
                    .append(String.format(Locale.ROOT, "%.1f", (double) llamadasServicios / servicios)).append(" llamadas, ")
                    .append(String.format(Locale.ROOT, "%.1f", (double) msServicios / servicios / MS_SEGUNDO)).append(" s\n");
        }
        sb.append(cuerpoBloques(t, ms));
        sb.append(cuerpoArreglo(t, ms, total.ms));
        return sb.toString();
    }

    /**
     * Con la tanda en bloques: tiempo real (de la primera a la última llamada; con trabajadores en paralelo la fila Total suma
     * el trabajo de todos los bloques) y tabla Bloque | Servicios | Tiempo | Llamadas; vacío sin bloques.
     */
    private static String cuerpoBloques(Tanda t, Map<String, Tanda.Medicion> ms) {
        if (t.bloques == null || t.bloques.isEmpty()) {
            return "";
        }
        Instant primera = null;
        Instant ultima = null;
        for (Tanda.Medicion m : ms.values()) {
            if (m == null) {
                continue;
            }
            Instant i = instante(m.inicio);
            Instant f = instante(m.fin);
            if (i != null && (primera == null || i.isBefore(primera))) {
                primera = i;
            }
            if (f != null && (ultima == null || f.isAfter(ultima))) {
                ultima = f;
            }
        }
        String real = primera == null || ultima == null ? "sin datos" : tiempo(Math.max(0, ultima.toEpochMilli() - primera.toEpochMilli()));
        StringBuilder sb = new StringBuilder("\nTanda en paralelo: ").append(t.bloques.size()).append(" bloques; tiempo real: ")
                .append(real).append(" (de la primera a la última llamada); la fila Total suma el trabajo de todos los bloques\n\n");
        sb.append("| Bloque | Servicios | Tiempo | Llamadas |\n|---|---|---|---|\n");
        for (Tanda.BloqueTanda b : t.bloques) {
            if (b == null) {
                continue;
            }
            long tiempo = 0;
            int llamadas = 0;
            List<String> nombres = new ArrayList<>();
            for (String fqn : b.servicios) {
                nombres.add(Textos.nombreSimple(fqn));
                Tanda.Medicion m = ms.get(fqn);
                if (m != null) {
                    tiempo += m.ms;
                    llamadas += m.llamadas;
                }
            }
            sb.append("| ").append(b.id).append(" | ").append(EscritorInforme.celda(String.join(", ", nombres))).append(" | ")
                    .append(tiempo(tiempo)).append(" | ").append(llamadas).append(" |\n");
        }
        return sb.toString();
    }

    private static Instant instante(String iso) {
        if (iso == null) {
            return null;
        }
        try {
            return Instant.parse(iso);
        } catch (java.time.format.DateTimeParseException e) {
            System.err.println("junit-gen: instante de medición no válido: " + e.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * Tabla «Fase de arreglo» (por servicio: tiempo, llamadas por herramienta, casos, arreglados, automáticos y menos
     * estrictos) y el reparto del tiempo entre generación y arreglo; vacía si no hubo fase de arreglo.
     */
    private static String cuerpoArreglo(Tanda t, Map<String, Tanda.Medicion> ms, long msTotal) {
        boolean conArreglo = t.arreglos != null && !t.arreglos.isEmpty();
        for (Tanda.Medicion m : ms.values()) {
            if (m != null && m.enArreglo != null && !m.enArreglo.isEmpty()) {
                conArreglo = true;
            }
        }
        if (!conArreglo) {
            return "";
        }
        Map<String, int[]> casos = new LinkedHashMap<>();
        if (t.arreglos != null) {
            for (Tanda.ArregloTanda a : t.arreglos) {
                if (a == null) {
                    continue;
                }
                int[] c = casos.computeIfAbsent(a.servicio == null ? TANDA : a.servicio, k -> new int[4]);
                c[0]++;
                if (Tanda.ARREGLO_ARREGLADO.equals(a.estado)) {
                    c[1]++;
                    if (a.automatico) {
                        c[2]++;
                    }
                }
                if (a.menosEstricto) {
                    c[3]++;
                }
            }
        }
        Set<String> claves = new LinkedHashSet<>();
        for (Map.Entry<String, Tanda.Medicion> e : ms.entrySet()) {
            Tanda.Medicion m = e.getValue();
            if (m != null && ((m.enArreglo != null && !m.enArreglo.isEmpty()) || m.msArreglo > 0)) {
                claves.add(e.getKey());
            }
        }
        claves.addAll(casos.keySet());
        StringBuilder sb = new StringBuilder("\n### Fase de arreglo\n\n");
        sb.append("| Servicio | Tiempo en arreglo | Llamadas | ").append(String.join(" | ", HERRAMIENTAS_ARREGLO))
                .append(" | Otras | Casos | Arreglados | Automáticos | Menos estrictos |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        long msArreglo = 0;
        Map<String, Integer> totalPor = new LinkedHashMap<>();
        int[] totalCasos = new int[4];
        for (String clave : claves) {
            Tanda.Medicion m = ms.get(clave);
            Map<String, Integer> por = m == null || m.enArreglo == null ? Map.of() : m.enArreglo;
            long msA = m == null ? 0 : m.msArreglo;
            int[] c = casos.getOrDefault(clave, new int[4]);
            String nombre = TANDA.equals(clave) ? TANDA : Textos.nombreSimple(clave);
            filaArreglo(sb, nombre, msA, por, c);
            msArreglo += msA;
            por.forEach((k, v) -> totalPor.merge(k, v == null ? 0 : v, Integer::sum));
            for (int i = 0; i < totalCasos.length; i++) {
                totalCasos[i] += c[i];
            }
        }
        filaArreglo(sb, "**" + FILA_TOTAL + "**", msArreglo, totalPor, totalCasos);
        sb.append("\nTiempo de generación: ").append(tiempo(Math.max(0, msTotal - msArreglo))).append("; tiempo de la fase de arreglo: ")
                .append(tiempo(msArreglo)).append(".\n");
        return sb.toString();
    }

    private static void filaArreglo(StringBuilder sb, String nombre, long msArreglo, Map<String, Integer> por, int[] casos) {
        int todas = 0;
        int conocidas = 0;
        for (Map.Entry<String, Integer> e : por.entrySet()) {
            int v = e.getValue() == null ? 0 : e.getValue();
            todas += v;
            if (HERRAMIENTAS_ARREGLO.contains(e.getKey())) {
                conocidas += v;
            }
        }
        sb.append("| ").append(EscritorInforme.celda(nombre)).append(" | ").append(tiempo(msArreglo)).append(" | ").append(todas);
        for (String h : HERRAMIENTAS_ARREGLO) {
            Integer v = por.get(h);
            sb.append(" | ").append(v == null ? 0 : v);
        }
        sb.append(" | ").append(todas - conocidas);
        for (int c : casos) {
            sb.append(" | ").append(c);
        }
        sb.append(" |\n");
    }

    private static void fila(StringBuilder sb, String nombre, Tanda.Medicion m) {
        Map<String, Integer> por = m.porHerramienta == null ? Map.of() : m.porHerramienta;
        int conocidas = 0;
        int todas = 0;
        for (Map.Entry<String, Integer> e : por.entrySet()) {
            int v = e.getValue() == null ? 0 : e.getValue();
            todas += v;
            if (HERRAMIENTAS_TABLA.contains(e.getKey())) {
                conocidas += v;
            }
        }
        int enArreglo = 0;
        if (m.enArreglo != null) {
            for (Integer v : m.enArreglo.values()) {
                enArreglo += v == null ? 0 : v;
            }
        }
        sb.append("| ").append(EscritorInforme.celda(nombre)).append(" | ").append(tiempo(m.ms)).append(" | ").append(m.llamadas);
        for (String h : HERRAMIENTAS_TABLA) {
            sb.append(" | ").append(por.getOrDefault(h, 0));
        }
        sb.append(" | ").append(todas - conocidas).append(" | ").append(m.rechazadas).append(" | ").append(enArreglo).append(" | ")
                .append(String.format(Locale.ROOT, "%.1f", m.bytes / BYTES_KB)).append(" |\n");
    }

    private static String tiempo(long ms) {
        long s = Math.round(ms / (double) MS_SEGUNDO);
        if (s < SEGUNDOS_MINUTO) {
            return s + " s";
        }
        return (s / SEGUNDOS_MINUTO) + " min " + String.format(Locale.ROOT, "%02d", s % SEGUNDOS_MINUTO) + " s";
    }

    /** Escribe una sola vez la sección «Medición de la tanda» en informe.md; si falla, se podrá reintentar. */
    static void informar(SesionMcp sesion, Path proyecto, Path salida) {
        if (proyecto == null || salida == null) {
            return;
        }
        Tanda t = sesion.tanda(proyecto);
        if (t == null) {
            return;
        }
        String cuerpo;
        synchronized (t) {
            if (t.mediciones == null || t.mediciones.isEmpty() || t.medicionInformada) {
                return;
            }
            t.medicionInformada = true;
            cuerpo = cuerpoInforme(t);
        }
        try {
            sesion.fachada().anotarMedicion(proyecto, salida, cuerpo);
        } catch (RuntimeException e) {
            synchronized (t) {
                t.medicionInformada = false;
            }
            System.err.println("junit-gen: no se pudo escribir la medición de la tanda en el informe: " + e.getClass().getSimpleName());
        }
    }
}
