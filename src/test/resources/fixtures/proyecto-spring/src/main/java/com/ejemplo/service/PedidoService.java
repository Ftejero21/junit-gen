package com.ejemplo.service;

import com.ejemplo.util.CalculadoraPrecio;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

@Service
public class PedidoService {

    @Autowired
    private UsuarioRepository usuarios;

    public double total(double base, int unidades) {
        if (unidades <= 0) {
            throw new IllegalArgumentException("unidades");
        }
        CalculadoraPrecio calculadora = new CalculadoraPrecio();
        return calculadora.calcular(base, unidades);
    }

    public boolean esHoy(LocalDate fecha) {
        return LocalDate.now().equals(fecha);
    }

    public String nombreUsuario(Long id) {
        return usuarios.findById(id).map(Usuario::getNombre).orElse("desconocido");
    }
}