package br.com.wonder.agent.core.provision;

import jakarta.enterprise.context.ApplicationScoped;
import lombok.extern.slf4j.Slf4j;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.Base64;

/**
 * Consulta o maven-metadata.xml do Reposilite para resolver a versão mais recente
 * de um artefato sem depender do wonder-central.
 *
 * URL consultada:
 * {repositoryUrl}/{repoPath}/{groupPath}/{artifactId}/maven-metadata.xml
 */
@Slf4j
@ApplicationScoped
public class ReposiliteMetadataClient {

    /**
     * Resolve a versão mais recente do artefato consultando o maven-metadata.xml.
     * Prioridade: {@code <latest>} → {@code <release>} → última entrada em {@code <versions>}.
     *
     * @throws WildflyProvisioner.ProvisioningException se não for possível resolver a versão
     */
    public String resolveLatestVersion(String repositoryUrl, String repoPath,
                                        String groupPath, String artifactId,
                                        String username, String password)
            throws WildflyProvisioner.ProvisioningException {

        String url = repositoryUrl.stripTrailing()
                + "/" + repoPath
                + "/" + groupPath
                + "/" + artifactId
                + "/maven-metadata.xml";

        log.debug("Consultando maven-metadata.xml: {}", url);

        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(10_000);
            conn.setRequestProperty("Accept", "application/xml");
            if (username != null && !username.isBlank()) {
                String creds = Base64.getEncoder().encodeToString((username + ":" + password).getBytes());
                conn.setRequestProperty("Authorization", "Basic " + creds);
            }

            int status = conn.getResponseCode();
            if (status == 401) {
                throw new WildflyProvisioner.ProvisioningException(
                        "Acesso negado ao Reposilite (" + url + ") — verifique WONDER_REPO_USERNAME/PASSWORD", null);
            }
            if (status == 404) {
                throw new WildflyProvisioner.ProvisioningException(
                        "Artefato não encontrado no Reposilite: " + url, null);
            }
            if (status != 200) {
                throw new WildflyProvisioner.ProvisioningException(
                        "Resposta inesperada do Reposilite HTTP " + status + " — " + url, null);
            }

            try (InputStream in = conn.getInputStream()) {
                return parseLatestVersion(in, url);
            }
        } catch (WildflyProvisioner.ProvisioningException e) {
            throw e;
        } catch (Exception e) {
            throw new WildflyProvisioner.ProvisioningException(
                    "Falha ao consultar versão no Reposilite: " + url, e);
        }
    }

    /**
     * Para versões SNAPSHOT, resolve o valor concreto (timestamp+buildNumber) do artefato
     * consultando o maven-metadata.xml do nível de versão:
     * {@code {repositoryUrl}/{repoPath}/{groupPath}/{artifactId}/{version}/maven-metadata.xml}
     *
     * Exemplo de retorno: {@code "1.0.0-20260522.190222-7"} para classifier=dist, extension=zip.
     * Para versões não-SNAPSHOT retorna {@code version} sem consulta.
     *
     * @throws WildflyProvisioner.ProvisioningException se não encontrar o valor
     */
    public String resolveSnapshotValue(String repositoryUrl, String repoPath,
                                       String groupPath, String artifactId,
                                       String version, String classifier, String extension,
                                       String username, String password)
            throws WildflyProvisioner.ProvisioningException {

        if (!version.endsWith("-SNAPSHOT")) {
            return version;
        }

        String url = repositoryUrl.stripTrailing()
                + "/" + repoPath
                + "/" + groupPath
                + "/" + artifactId
                + "/" + version
                + "/maven-metadata.xml";

        log.debug("Consultando maven-metadata.xml de SNAPSHOT: {}", url);

        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(10_000);
            conn.setRequestProperty("Accept", "application/xml");
            if (username != null && !username.isBlank()) {
                String creds = Base64.getEncoder().encodeToString((username + ":" + password).getBytes());
                conn.setRequestProperty("Authorization", "Basic " + creds);
            }

            int status = conn.getResponseCode();
            if (status == 401) {
                throw new WildflyProvisioner.ProvisioningException(
                        "Acesso negado ao Reposilite (" + url + ") — verifique WONDER_REPO_USERNAME/PASSWORD", null);
            }
            if (status == 404) {
                throw new WildflyProvisioner.ProvisioningException(
                        "maven-metadata.xml não encontrado: " + url, null);
            }
            if (status != 200) {
                throw new WildflyProvisioner.ProvisioningException(
                        "Resposta inesperada do Reposilite HTTP " + status + " — " + url, null);
            }

            try (InputStream in = conn.getInputStream()) {
                return parseSnapshotValue(in, classifier, extension, url);
            }
        } catch (WildflyProvisioner.ProvisioningException e) {
            throw e;
        } catch (Exception e) {
            throw new WildflyProvisioner.ProvisioningException(
                    "Falha ao consultar maven-metadata.xml de SNAPSHOT: " + url, e);
        }
    }

    // Parseia <snapshotVersions> procurando o <value> para o classifier+extension pedidos.
    // Fallback: constrói o valor a partir de <timestamp>+<buildNumber> se não encontrar snapshotVersions.
    private String parseSnapshotValue(InputStream xml, String classifier, String extension, String url)
            throws WildflyProvisioner.ProvisioningException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(xml);
            doc.getDocumentElement().normalize();

            // Percorre <snapshotVersion> procurando classifier+extension correspondentes
            NodeList snapshotVersions = doc.getElementsByTagName("snapshotVersion");
            for (int i = 0; i < snapshotVersions.getLength(); i++) {
                var node = snapshotVersions.item(i);
                String cls = childText(node, "classifier");
                String ext = childText(node, "extension");
                String value = childText(node, "value");
                if (classifier.equals(cls) && extension.equals(ext) && value != null && !value.isBlank()) {
                    log.debug("Valor SNAPSHOT encontrado para {}/{}: {}", classifier, extension, value);
                    return value;
                }
            }

            // Fallback: timestamp + buildNumber
            String timestamp = textContent(doc, "timestamp");
            String buildNumber = textContent(doc, "buildNumber");
            if (timestamp != null && buildNumber != null) {
                // versão é ex. "1.0.0-SNAPSHOT" → base "1.0.0"
                String base = textContent(doc, "version");
                if (base == null) base = "SNAPSHOT";
                String fallback = base.replace("-SNAPSHOT", "") + "-" + timestamp + "-" + buildNumber;
                log.debug("Fallback timestamp+buildNumber: {}", fallback);
                return fallback;
            }

            throw new WildflyProvisioner.ProvisioningException(
                    "Nenhum snapshotVersion para " + classifier + "/" + extension + " em " + url, null);
        } catch (WildflyProvisioner.ProvisioningException e) {
            throw e;
        } catch (Exception e) {
            throw new WildflyProvisioner.ProvisioningException(
                    "Falha ao parsear maven-metadata.xml de SNAPSHOT: " + url, e);
        }
    }

    private static String childText(org.w3c.dom.Node parent, String tagName) {
        var children = ((org.w3c.dom.Element) parent).getElementsByTagName(tagName);
        if (children.getLength() == 0) return null;
        return children.item(0).getTextContent().trim();
    }

    private String parseLatestVersion(InputStream xml, String url)
            throws WildflyProvisioner.ProvisioningException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // Desabilita entidades externas (XXE)
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(xml);
            doc.getDocumentElement().normalize();

            String latest = textContent(doc, "latest");
            if (latest != null && !latest.isBlank()) {
                log.debug("Versão <latest> encontrada: {}", latest);
                return latest;
            }

            String release = textContent(doc, "release");
            if (release != null && !release.isBlank()) {
                log.debug("Versão <release> encontrada: {}", release);
                return release;
            }

            // Fallback: última versão em <versions>
            NodeList versions = doc.getElementsByTagName("version");
            if (versions.getLength() > 0) {
                String last = versions.item(versions.getLength() - 1).getTextContent().trim();
                log.debug("Versão extraída de <versions>: {}", last);
                return last;
            }

            throw new WildflyProvisioner.ProvisioningException(
                    "Nenhuma versão encontrada no maven-metadata.xml de " + url, null);
        } catch (WildflyProvisioner.ProvisioningException e) {
            throw e;
        } catch (Exception e) {
            throw new WildflyProvisioner.ProvisioningException(
                    "Falha ao parsear maven-metadata.xml de " + url, e);
        }
    }

    private static String textContent(Document doc, String tagName) {
        NodeList nodes = doc.getElementsByTagName(tagName);
        if (nodes.getLength() == 0) return null;
        return nodes.item(0).getTextContent().trim();
    }
}
