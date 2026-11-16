package com.ejemplo.tienda.usuarios;

/** Envía avisos a los usuarios (correo, SMS...). */
public interface Notificador {

    void enviar(String mensaje);
}
