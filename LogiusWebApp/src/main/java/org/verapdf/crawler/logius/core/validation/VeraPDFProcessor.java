package org.verapdf.crawler.logius.core.validation;

import javanet.staxutils.SimpleNamespaceContext;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import org.verapdf.crawler.logius.document.DomainDocument;
import org.verapdf.crawler.logius.validation.VeraPDFValidationResult;
import org.verapdf.crawler.logius.validation.error.RuleViolationError;
import org.verapdf.crawler.logius.validation.error.ValidationError;
import org.verapdf.crawler.logius.validation.settings.ValidationSettings;
import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * @author Maksim Bezrukov
 */

@Component
public class VeraPDFProcessor {

    private static final Logger logger = LoggerFactory.getLogger(VeraPDFProcessor.class);

    private static final int MAX_PREFERRED_PROPERTY_LENGTH = 255;
    private static final String BASE_PATH = "/report/jobs/job/";
    private static final String VALIDATION_REPORT_PATH = BASE_PATH + "validationReport/";
    private static final String FLAVOUR_PART_PROPERTY_NAME = "flavourPart";
    private static final String FLAVOUR_CONFORMANCE_PROPERTY_NAME = "flavourConformance";

    private final String verapdfPath;
    private final File veraPDFErrorLog;


    VeraPDFProcessor(@Value("${logius.veraPDFService.verapdfPath}") String verapdfPath,
                     @Value("${logius.veraPDFService.verapdfErrors}") String veraPDFErrorLog) {
        this.verapdfPath = verapdfPath;
        this.veraPDFErrorLog = new File(veraPDFErrorLog);
    }

    private File getVeraPDFReport(String filename) throws IOException, InterruptedException {
        logger.info("Preparing veraPDF process...");
        String[] cmd = {verapdfPath, "--extract", "--format", "mrr", "--maxfailuresdisplayed", "1", filename};
        ProcessBuilder pb = new ProcessBuilder();
        pb.redirectError(this.veraPDFErrorLog);
        Path outputPath = Files.createTempFile("veraPDFReport", ".xml");
        File file = outputPath.toFile();
        pb.redirectOutput(file);
        pb.command(cmd);
        logger.info("Starting veraPDF process for file " + filename);
        Process process = pb.start();
        logger.info("VeraPDF process has been started");
        if (!process.waitFor(5, TimeUnit.MINUTES)) {
            process.destroy();
            logger.info("VeraPDF process has reached timeout. Destroying...");
        }
        logger.info("VeraPDF process has been finished");
        return file;
    }


    private File checkExtension(String filePath) {
        if (!filePath.endsWith(".pdf")) {
            File cur = new File(filePath);
            if (cur.isFile()) {
                try {
                    File res = File.createTempFile("tempPdf", ".pdf", cur.getParentFile());
                    try (InputStream is = new FileInputStream(cur);
                         OutputStream os = new FileOutputStream(res)) {
                        IOUtils.copy(is, os);
                    }
                    return res;
                } catch (IOException e) {
                    logger.error("Some problem during copy file " + filePath, e);
                }
            }
        }
        return null;
    }

    private void addNameSpaces(SimpleNamespaceContext nsc, ValidationSettings settings) {
        Map<String, String> namespaces = settings.getNamespaces();
        for (Map.Entry<String, String> entry : namespaces.entrySet()) {
            nsc.setPrefix(entry.getKey(), entry.getValue());
        }
    }

    private void evaluateProperties(VeraPDFValidationResult result, Document document, XPath xpath, ValidationSettings settings) {
        Map<String, List<String>> properties = new HashMap<>(settings.getProperties());
        List<String> partXPaths = properties.get(FLAVOUR_PART_PROPERTY_NAME);
        String part = getProperty(partXPaths, document, xpath);
        // if document is valid, then we have already placed OPEN result, but we need to remove it
        // in case, when flavour part is not 1 or 2
        try {
            int partInt = Integer.parseInt(part);
            if (partInt != 1 && partInt != 2) {
                result.setTestResult(DomainDocument.BaseTestResult.NOT_OPEN);
            }
        } catch (NumberFormatException e) {
            result.setTestResult(DomainDocument.BaseTestResult.NOT_OPEN);
        }
        List<String> conformanceXPaths = properties.get(FLAVOUR_CONFORMANCE_PROPERTY_NAME);
        String conformance = getProperty(conformanceXPaths, document, xpath).toUpperCase();
        String flavour = part + conformance;
        if (!flavour.isEmpty()) {
            result.addProperty("flavour", flavour);
        }
        properties.remove(FLAVOUR_PART_PROPERTY_NAME);
        properties.remove(FLAVOUR_CONFORMANCE_PROPERTY_NAME);
        for (Map.Entry<String, List<String>> property : properties.entrySet()) {
            String propertyValue = getProperty(property.getValue(), document, xpath);
            if (!propertyValue.isEmpty()) {
                result.addProperty(property.getKey(), propertyValue);
            }
        }
    }

    private String getProperty(List<String> xpaths, Document document, XPath xpath) {
        String tempResult = "";
        try {
            for (String propertyXPath : xpaths) {
                String value = (String) xpath.evaluate(propertyXPath, document, XPathConstants.STRING);
                if (value != null && !value.isEmpty()) {
                    if (value.length() <= MAX_PREFERRED_PROPERTY_LENGTH) {
                        return value;
                    } else if (tempResult.isEmpty()) {
                        tempResult = value;
                    }
                }
            }
        } catch (Throwable e) {
            logger.info("Some problem in obtaining property", e);
        }
        return tempResult;
    }

    private VeraPDFValidationResult generateBaseResult(Document document, XPath xpath) throws XPathExpressionException {
        VeraPDFValidationResult result = new VeraPDFValidationResult();
        String exceptionPath = BASE_PATH + "taskResult/exceptionMessage";
        String exception = (String) xpath.evaluate(exceptionPath,
                document,
                XPathConstants.STRING);
        if (exception != null && !exception.isEmpty()) {
            result.addValidationError(new ValidationError(exception));
        }

        String isCompliantPath = VALIDATION_REPORT_PATH + "@isCompliant";
        String isCompliantString = (String) xpath.evaluate(isCompliantPath,
                document,
                XPathConstants.STRING);
        boolean isCompliant = Boolean.parseBoolean(isCompliantString);
        if (isCompliant) {
            // set temporary OPEN result. will have to check document on flavour part 3
            result.setTestResult(DomainDocument.BaseTestResult.OPEN);
        } else {
            addValidationErrors(result, document, xpath);
        }
        return result;
    }

    private void addValidationErrors(VeraPDFValidationResult result, Document document, XPath xpath) throws XPathExpressionException {
        String rulesPath = VALIDATION_REPORT_PATH + "details/rule";
        NodeList rules = (NodeList) xpath.evaluate(rulesPath,
                document,
                XPathConstants.NODESET);
        for (int i = 0; i < rules.getLength(); ++i) {
            Node rule = rules.item(i);
            NamedNodeMap attributes = rule.getAttributes();
            if (attributes.getNamedItem("status").getNodeValue().equalsIgnoreCase("failed")) {
                String specification = attributes.getNamedItem("specification").getNodeValue();
                String clause = attributes.getNamedItem("clause").getNodeValue();
                String testNumber = attributes.getNamedItem("testNumber").getNodeValue();
                String description = null;
                NodeList children = rule.getChildNodes();
                for (int j = 0; j < children.getLength(); ++j) {
                    Node child = children.item(j);
                    if (child.getNodeName().equals("description")) {
                        description = child.getTextContent();
                        break;
                    }
                }
                result.addValidationError(new RuleViolationError(specification, clause, testNumber, description));
            }
        }
    }

    private VeraPDFValidationResult generateProblemResult(String message) {
        VeraPDFValidationResult res = new VeraPDFValidationResult();
        res.addValidationError(new ValidationError(message));
        return res;
    }

    public VeraPDFValidationResult process(String filePath, ValidationSettings settings) {
        VeraPDFValidationResult result;
        File report = null;
        File tempPdfFile = null;
        try {
            tempPdfFile = checkExtension(filePath);
            String toValidatePath = tempPdfFile == null ? filePath : tempPdfFile.getAbsolutePath();
            report = getVeraPDFReport(toValidatePath);
            if (report != null) {
                logger.info("Obtaining result structure");
                DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
                dbf.setNamespaceAware(true);
                DocumentBuilder db = dbf.newDocumentBuilder();
                Document document = db.parse(report);
                XPathFactory xpf = XPathFactory.newInstance();
                XPath xpath = xpf.newXPath();
                SimpleNamespaceContext nsc = new SimpleNamespaceContext();
                addNameSpaces(nsc, settings);
                xpath.setNamespaceContext(nsc);
                result = generateBaseResult(document, xpath);
                evaluateProperties(result, document, xpath, settings);
            } else {
                result = generateProblemResult("Some problem in report generation");
            }
        } catch (InterruptedException e) {
            logger.info("Process has been interrupted", e);
            result = null;
        } catch (Throwable e) {
            logger.info("Some problem in generating result", e.getMessage());
            result = null;
        } finally {
            logger.info("Finished");
            if (report != null && !report.delete()) {
                logger.info("Report has not been deleted manually");
            }
            if (tempPdfFile != null && !tempPdfFile.delete()) {
                logger.info("Temp pdf file has not been deleted manually");
            }
        }
        return result;
    }
}