package org.verapdf.crawler.logius.service;

import com.adobe.xmp.XMPDateTime;
import com.adobe.xmp.XMPDateTimeFactory;
import com.adobe.xmp.XMPException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.verapdf.crawler.logius.core.tasks.ValidationTask;
import org.verapdf.crawler.logius.core.validation.PDFValidator;
import org.verapdf.crawler.logius.db.DocumentDAO;
import org.verapdf.crawler.logius.db.ValidationErrorDAO;
import org.verapdf.crawler.logius.db.ValidationJobDAO;
import org.verapdf.crawler.logius.document.DomainDocument;
import org.verapdf.crawler.logius.validation.ValidationJob;
import org.verapdf.crawler.logius.validation.VeraPDFValidationResult;
import org.verapdf.crawler.logius.validation.error.ValidationError;

import javax.xml.datatype.DatatypeConfigurationException;
import javax.xml.datatype.DatatypeFactory;
import javax.xml.datatype.XMLGregorianCalendar;
import java.io.File;
import java.io.IOException;
import java.util.Date;
import java.util.List;
import java.util.Map;

@Service
public class ValidationService {
    private static final Logger logger = LoggerFactory.getLogger(ValidationTask.class);
    private static final String PROPERTY_NAME_MOD_DATE_XMP = "modDateXMP";
    private static final String PROPERTY_NAME_MOD_DATE_INFO_DICT = "modDateInfoDict";

    private final PDFValidator validator;
    private final ValidationJobDAO validationJobDAO;
    private final ValidationErrorDAO validationErrorDAO;
    private final DocumentDAO documentDAO;
    private ValidationJob currentJob;

    public ValidationService(PDFValidator validator, ValidationJobDAO validationJobDAO,
                             ValidationErrorDAO validationErrorDAO, DocumentDAO documentDAO) {

        this.validator = validator;
        this.validationJobDAO = validationJobDAO;
        this.validationErrorDAO = validationErrorDAO;
        this.documentDAO = documentDAO;
    }

    private static Date getModDate(String fromXMP, String fromInfoDict) {
        if (fromXMP != null) {
            try {
                XMPDateTime fromISO8601 = XMPDateTimeFactory.createFromISO8601(fromXMP);
                return fromISO8601.getCalendar().getTime();
            } catch (XMPException e) {
                return null;
            }
        } else if (fromInfoDict != null) {
            try {
                XMLGregorianCalendar xmlGregorianCalendar = DatatypeFactory.newInstance().newXMLGregorianCalendar(fromInfoDict);
                return xmlGregorianCalendar.toGregorianCalendar().getTime();
            } catch (DatatypeConfigurationException e) {
                return null;
            }
        } else {
            return null;
        }
    }

    @Transactional
    public ValidationJob retrieveNextJob() {
        logger.debug("Getting next job");
        ValidationJob job = validationJobDAO.next();
        if (job != null) {
            job.setStatus(ValidationJob.Status.IN_PROGRESS);
        }
        synchronized (ValidationService.class) {
            this.currentJob = job;
        }
        return job;
    }

    @Transactional
    public ValidationJob retrieveCurrentJob() {
        logger.debug("Getting current job");
        ValidationJob job = validationJobDAO.current();
        synchronized (ValidationService.class) {
            this.currentJob = job;
        }
        return job;
    }

    @Transactional
    public void saveResult(VeraPDFValidationResult result) {
        boolean shouldCleanDB = false;
        try {
            if (!currentJob.getStatus().equals(ValidationJob.Status.ABORTED)) {
                shouldCleanDB = true;
                logger.debug("Saving validation job results");
                DomainDocument document = currentJob.getDocument();
                document.setBaseTestResult(result.getTestResult());

                // Save errors where needed
                List<ValidationError> validationErrors = result.getValidationErrors();
                for (int index = 0; index < validationErrors.size(); index++) {
                    validationErrors.set(index, validationErrorDAO.save(validationErrors.get(index)));
                }
                document.setValidationErrors(validationErrors);

                // Link properties and modification date
                Map<String, String> properties = result.getProperties();
                String modDateXMP = null;
                String modDateInfoDict = null;
                if (properties.containsKey(PROPERTY_NAME_MOD_DATE_XMP)) {
                    modDateXMP = properties.get(PROPERTY_NAME_MOD_DATE_XMP);
                    properties.remove(PROPERTY_NAME_MOD_DATE_XMP);
                }
                if (properties.containsKey(PROPERTY_NAME_MOD_DATE_INFO_DICT)) {
                    modDateInfoDict = properties.get(PROPERTY_NAME_MOD_DATE_INFO_DICT);
                    properties.remove(PROPERTY_NAME_MOD_DATE_INFO_DICT);
                }
                Date modDate = getModDate(modDateXMP, modDateInfoDict);
                if (modDate != null) {
                    document.setLastModified(modDate);
                }
                document.setProperties(properties);

                // And update document (note that document was detached from hibernate context, thus we need to save explicitly)
                documentDAO.save(document);
            } else {
                logger.debug("Validation job was aborted, don't save any results");
            }
        } finally {
            cleanJob(currentJob, shouldCleanDB);
        }
    }

    private void cleanJob(ValidationJob job, boolean shouldCleanDB) {
        logger.debug("Cleanup validation job");
        if (job == null) {
            return;
        }
        if (job.getFilePath() != null) {
            if (!new File(job.getFilePath()).delete()) {
                logger.warn("Failed to clean validation job file " + job.getFilePath());
            }
        }
        if (shouldCleanDB) {
            validationJobDAO.remove(job);
        }
    }

    public void abortCurrentJob() {
        try {
            logger.info("Aborting current job");
            getCurrentJob().setStatus(ValidationJob.Status.ABORTED);
            validator.terminateValidation();
        } catch (IOException e) {
            logger.error("Can't terminate current job", e);
        }
    }

    public ValidationJob getCurrentJob() {
        return currentJob;
    }

}