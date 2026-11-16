package com.legacy.negocio;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class FacturaManagerTest {

    @Mock
    private ClienteDao clienteDao;

    @InjectMocks
    private FacturaManager manager;

    @Test(expected = IllegalArgumentException.class)
    public void importeConIva_baseNegativa() {
        manager.importeConIva(1L, -5.0);
    }
}