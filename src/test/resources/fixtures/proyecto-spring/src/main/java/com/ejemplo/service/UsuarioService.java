package com.ejemplo.service;

public interface UsuarioService {

    Usuario buscar(Long id);

    Usuario activar(Long id);

    int contarActivos();
}