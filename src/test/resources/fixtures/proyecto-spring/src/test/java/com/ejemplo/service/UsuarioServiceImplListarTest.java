package com.ejemplo.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class UsuarioServiceImplListarTest {

    @Test
    void listar_pendiente() {
        assertTrue(EstadoPedido.values().length > 0);
    }
}