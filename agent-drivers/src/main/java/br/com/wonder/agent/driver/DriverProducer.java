package br.com.wonder.agent.driver;

import br.com.wonder.agent.model.driver.RuntimeDriver;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.Instance;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Produtor de RuntimeDriver que seleciona baseado em driver.type na configuração.
 *
 * Permite suportar múltiplos drivers (WildflyDriver, QuarkusDriver, ProxyDriver)
 * injetados com @Named("tipo").
 *
 * Ver docs/architecture/decisions/ADR-004-driver-abstraction.md
 */
@Slf4j
@ApplicationScoped
public class DriverProducer {

    @Produces
    @ApplicationScoped
    public RuntimeDriver produceDriver(
        @ConfigProperty(name = "driver.type", defaultValue = "wildfly") String driverType,
        Instance<RuntimeDriver> drivers
    ) {
        log.info("Selecionando driver: {}", driverType);

        Instance<RuntimeDriver> selected = drivers.select(new NamedLiteral(driverType));

        if (selected.isUnsatisfied()) {
            throw new IllegalStateException("Driver não encontrado: " + driverType +
                ". Verifique driver.type em application.yaml");
        }

        if (selected.isAmbiguous()) {
            throw new IllegalStateException("Múltiplas implementações para driver: " + driverType);
        }

        RuntimeDriver driver = selected.get();
        log.info("Driver carregado: {} ({})", driverType, driver.getRuntimeType());
        return driver;
    }
}
