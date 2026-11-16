package com.ejemplo.tienda.facturas;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
public class FacturaService {

    private final FacturaRepository facturaRepository;

    public FacturaService(FacturaRepository facturaRepository) {
        this.facturaRepository = facturaRepository;
    }

    /** Emite una factura pendiente de pago para el cliente. */
    public Factura emitir(String cliente, BigDecimal importe) {
        if (cliente == null || cliente.isBlank()) {
            throw new IllegalArgumentException("Falta el cliente");
        }
        if (importe == null || importe.signum() <= 0) {
            throw new IllegalArgumentException("El importe debe ser positivo");
        }
        Factura factura = new Factura();
        factura.setCliente(cliente.trim());
        factura.setImporte(importe);
        factura.setPagada(false);
        return facturaRepository.save(factura);
    }

    /** Marca una factura como pagada; no se puede pagar dos veces. */
    public Factura pagar(Long id) {
        Factura factura = facturaRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("No existe la factura " + id));
        if (factura.isPagada()) {
            throw new IllegalStateException("La factura " + id + " ya está pagada");
        }
        factura.setPagada(true);
        return facturaRepository.save(factura);
    }

    /** Suma de los importes de las facturas del cliente que siguen sin pagar. */
    public BigDecimal totalPendiente(String cliente) {
        BigDecimal total = BigDecimal.ZERO;
        for (Factura f : facturaRepository.findByCliente(cliente)) {
            if (!f.isPagada() && f.getImporte() != null) {
                total = total.add(f.getImporte());
            }
        }
        return total;
    }
}
