package com.ejemplo.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PruebasPedidosTest {

    @Mock
    private UsuarioRepository usuarios;

    @InjectMocks
    private PedidoService pedidos;

    @Test
    void nombreUsuario_desconocidoSiNoExiste() {
        when(usuarios.findById(7L)).thenReturn(Optional.empty());
        assertEquals("desconocido", pedidos.nombreUsuario(7L));
    }
}