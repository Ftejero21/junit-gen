package com.ejemplo.tienda.usuarios;

import org.springframework.stereotype.Service;

@Service
public class UsuarioServiceImpl implements UsuarioService {

    private final UsuarioRepository usuarioRepository;
    private final Notificador notificador;

    public UsuarioServiceImpl(UsuarioRepository usuarioRepository, Notificador notificador) {
        this.usuarioRepository = usuarioRepository;
        this.notificador = notificador;
    }

    @Override
    public Usuario buscar(Long id) {
        return usuarioRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("No existe " + id));
    }

    @Override
    public Usuario activar(Long id) {
        Usuario u = buscar(id);
        if (u.isActivo()) {
            throw new IllegalStateException("Ya activo");
        }
        u.setActivo(true);
        Usuario guardado = usuarioRepository.save(u);
        notificador.enviar("activado " + id);
        return guardado;
    }

    @Override
    public int contarActivos() {
        int n = 0;
        for (Usuario u : usuarioRepository.findAll()) {
            if (u.isActivo()) {
                n++;
            }
        }
        return n;
    }
}
