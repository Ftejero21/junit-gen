package com.ejemplo.service;

public class UsuarioController {

    private final UsuarioService service;

    public UsuarioController(UsuarioService service) {
        this.service = service;
    }

    public String ver(Long id) {
        if (id == null) {
            return "vacío";
        }
        return service.buscar(id).getNombre();
    }
}