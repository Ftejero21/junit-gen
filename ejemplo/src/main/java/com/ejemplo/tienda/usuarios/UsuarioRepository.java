package com.ejemplo.tienda.usuarios;

import java.util.List;
import java.util.Optional;

public interface UsuarioRepository {

    Optional<Usuario> findById(Long id);

    Usuario save(Usuario usuario);

    List<Usuario> findAll();
}
