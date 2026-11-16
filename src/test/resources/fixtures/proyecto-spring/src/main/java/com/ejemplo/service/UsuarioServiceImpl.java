package com.ejemplo.service;

import org.springframework.stereotype.Service;

@Service
public class UsuarioServiceImpl implements UsuarioService {

    private final UsuarioRepository repo;
    private final Notificador notificador;

    public UsuarioServiceImpl(UsuarioRepository repo, Notificador notificador) {
        this.repo = repo;
        this.notificador = notificador;
    }

    @Override
    public Usuario buscar(Long id) {
        return repo.findById(id).orElseThrow(() -> new IllegalArgumentException("No existe " + id));
    }

    @Override
    public Usuario activar(Long id) {
        Usuario u = buscar(id);
        if (u.isActivo()) {
            throw new IllegalStateException("Ya activo");
        }
        u.setActivo(true);
        Usuario guardado = repo.save(u);
        notificador.enviar("activado " + id);
        return guardado;
    }

    @Override
    public int contarActivos() {
        int n = 0;
        for (Usuario u : repo.findAll()) {
            if (u.isActivo()) {
                n++;
            }
        }
        return n;
    }
}