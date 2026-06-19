package com.apex.sample;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class AppTest {
    @Test
    public void addReturnsSum() {
        assertEquals(5, App.add(2, 3));
    }
}
