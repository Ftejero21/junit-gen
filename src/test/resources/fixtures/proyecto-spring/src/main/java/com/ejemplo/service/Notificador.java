package com.ejemplo.service;

import org.springframework.stereotype.Component;

@Component
public class Notificador {

    private int enviados;

    public void enviar(String mensaje) {
        if (mensaje == null || mensaje.isEmpty()) {
            throw new IllegalArgumentException("mensaje vacío");
        }
        enviados++;
    }

    public int getEnviados() {
        return enviados;
    }
}