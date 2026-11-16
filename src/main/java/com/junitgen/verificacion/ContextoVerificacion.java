package com.junitgen.verificacion;

import com.junitgen.escaneo.ModuloEscaneado;
import com.junitgen.escaneo.ProyectoEscaneado;
import com.junitgen.seguridad.EscritorSeguro;
import com.junitgen.seguridad.ProcesoSeguro;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/** Estado compartido durante la verificación de un módulo. */
public class ContextoVerificacion {

    public ProyectoEscaneado proyecto;
    public ModuloEscaneado modulo;
    public EscritorSeguro escritor;
    public ProcesoSeguro proceso;
    /** {@code <salida>/.junit-gen-tmp/<módulo>} */
    public Path tmp;
    public LocalizadorJdk.Seleccion jdk;
    public final List<String> avisos = new ArrayList<>();
    public final Path javaHerramienta = Paths.get(System.getProperty("java.home"));

    public Path log(String nombre) {
        return tmp.resolve("logs").resolve(nombre);
    }

    public static String clave(ModuloEscaneado m) {
        String rel = m.modulo.rel;
        return rel.isEmpty() ? "_raiz" : rel.replace('/', '_');
    }
}
