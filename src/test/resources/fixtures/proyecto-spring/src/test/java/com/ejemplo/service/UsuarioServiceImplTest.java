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
class UsuarioServiceImplTest {

    @Mock
    private UsuarioRepository repo;

    @Mock
    private Notificador notificador;

    @InjectMocks
    private UsuarioServiceImpl service;

    @Test
    void buscar_devuelveUsuarioSiExiste() {
        Usuario u = new Usuario(1L, "Ana");
        when(repo.findById(1L)).thenReturn(Optional.of(u));

        Usuario r = service.buscar(1L);

        assertEquals(u, r);
    }
}