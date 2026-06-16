package org.example;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AppTest {

    @Test
    void appClassIsAnnotatedWithSpringBootApplication() {
        assertTrue(App.class.isAnnotationPresent(SpringBootApplication.class));
    }
}
