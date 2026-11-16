package com.legacy.negocio;

public class FacturaManager {

    private ClienteDao clienteDao;

    public void setClienteDao(ClienteDao clienteDao) {
        this.clienteDao = clienteDao;
    }

    public double importeConIva(Long clienteId, double base) {
        if (base < 0) {
            throw new IllegalArgumentException("base negativa");
        }
        String tipo = clienteDao.tipoCliente(clienteId);
        if ("EXENTO".equals(tipo)) {
            return base;
        }
        return base * 1.21;
    }
}