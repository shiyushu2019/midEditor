package com.example;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AppTest {

    @Test
    void sumAddsTwoNumbers() {
        assertEquals(3, App.sum(1, 2));
    }
}
