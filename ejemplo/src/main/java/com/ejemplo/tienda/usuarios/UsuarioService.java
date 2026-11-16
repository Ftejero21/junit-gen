package com.ejemplo.tienda.usuarios;

public interface UsuarioService {

    Usuario buscar(Long id);

    Usuario activar(Long id);

    int contarActivos();
}
