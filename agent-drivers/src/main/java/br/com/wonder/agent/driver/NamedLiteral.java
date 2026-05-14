package br.com.wonder.agent.driver;

import jakarta.enterprise.util.AnnotationLiteral;
import jakarta.inject.Named;

/**
 * Utilitário para criar qualificadores @Named dinamicamente em CDI.
 *
 * Permite seleção de driver via Instance<RuntimeDriver>.select(new NamedLiteral(type)).get()
 */
public class NamedLiteral extends AnnotationLiteral<Named> implements Named {
    private final String value;

    public NamedLiteral(String value) {
        this.value = value;
    }

    @Override
    public String value() {
        return value;
    }
}
