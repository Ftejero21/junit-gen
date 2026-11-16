package com.ejemplo.tienda.catalogo;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Cálculo de descuentos del catálogo (lógica pura, sin dependencias). */
@Service
public class DescuentoService {

    /** Porcentaje de descuento por volumen: 5 % desde 10 unidades, 10 % desde 50 y 15 % desde 100. */
    public int descuentoPorVolumen(int unidades) {
        if (unidades < 0) {
            throw new IllegalArgumentException("Unidades negativas: " + unidades);
        }
        if (unidades >= 100) {
            return 15;
        }
        if (unidades >= 50) {
            return 10;
        }
        if (unidades >= 10) {
            return 5;
        }
        return 0;
    }

    /** Aplica un porcentaje de descuento (0-100) al precio, redondeando a 2 decimales. */
    public BigDecimal aplicar(BigDecimal precio, int porcentaje) {
        if (precio == null || precio.signum() < 0) {
            throw new IllegalArgumentException("Precio no válido");
        }
        if (porcentaje < 0 || porcentaje > 100) {
            throw new IllegalArgumentException("Porcentaje fuera de rango: " + porcentaje);
        }
        BigDecimal factor = BigDecimal.valueOf(100 - porcentaje);
        return precio.multiply(factor).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }

    /** Precio total de una línea con el descuento por volumen ya aplicado. */
    public BigDecimal precioFinal(BigDecimal precioUnitario, int unidades) {
        if (precioUnitario == null) {
            throw new IllegalArgumentException("Falta el precio unitario");
        }
        BigDecimal bruto = precioUnitario.multiply(BigDecimal.valueOf(unidades));
        return aplicar(bruto, descuentoPorVolumen(unidades));
    }
}
