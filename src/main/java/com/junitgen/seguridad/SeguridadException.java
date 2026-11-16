package com.junitgen.seguridad;

/**
 * Rechazo por motivos de seguridad: la operación intentaba escribir, borrar o ejecutar algo
 * fuera de lo permitido (el proyecto analizado es de solo lectura).
 */
public class SeguridadException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public SeguridadException(String mensaje) {
        super(mensaje);
    }

    public SeguridadException(String mensaje, Throwable causa) {
        super(mensaje, causa);
    }
}
