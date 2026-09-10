package com.jupiter.shortlink.agent.support;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** Test-only repository resource lookup; works in source exports without Git metadata. */
public final class RepositoryFiles {
    private static final String MAVEN = "http://maven.apache.org/POM/4.0.0";

    private RepositoryFiles() {}

    public static Path riskProfileScript(Path workingDirectory) throws IOException {
        Path start = workingDirectory.toRealPath();
        if (!Files.isDirectory(start)) throw new IOException("Working directory is not a directory");
        for (Path root = start; root != null; root = root.getParent()) {
            Path pom = root.resolve("pom.xml");
            if (!Files.isRegularFile(pom, LinkOption.NOFOLLOW_LINKS) || !isRepositoryPom(pom)) continue;
            Path target = root.resolve("scripts/risk-profile-policy-e2e.ps1");
            // A valid inner source export is a boundary: never borrow an outer checkout's file.
            if (!Files.isRegularFile(target)) throw new IOException("Repository target is missing: " + target);
            Path realTarget = target.toRealPath();
            if (!realTarget.startsWith(root)) throw new IOException("Repository target escapes its root: " + target);
            return realTarget;
        }
        throw new IOException("Cannot locate com.jupiter.shortlink:shortlink-all repository from " + start);
    }

    private static boolean isRepositoryPom(Path pom) throws IOException {
        try {
            var factory = DocumentBuilderFactory.newDefaultInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new DefaultHandler());
            try (var input = Files.newInputStream(pom)) {
                Element project = builder.parse(input).getDocumentElement();
                Element modules = child(project, "modules");
                return MAVEN.equals(project.getNamespaceURI())
                        && "project".equals(project.getLocalName())
                        && "com.jupiter.shortlink".equals(text(project, "groupId"))
                        && "shortlink-all".equals(text(project, "artifactId"))
                        && "pom".equals(text(project, "packaging"))
                        && modules != null && !text(modules, "module").isBlank();
            }
        } catch (SAXException invalidPom) {
            return false;
        } catch (ParserConfigurationException invalidParser) {
            throw new IOException("Cannot configure safe POM parsing", invalidParser);
        }
    }

    private static String text(Element parent, String name) {
        Element element = child(parent, name);
        return element == null ? "" : element.getTextContent().trim();
    }

    private static Element child(Element parent, String name) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && MAVEN.equals(element.getNamespaceURI())
                    && name.equals(element.getLocalName())) return element;
        }
        return null;
    }
}
