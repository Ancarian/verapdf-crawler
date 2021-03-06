package org.verapdf.crawler.db;

import io.dropwizard.hibernate.AbstractDAO;
import org.hibernate.SessionFactory;
import org.hibernate.query.Query;
import org.verapdf.crawler.api.crawling.CrawlJob_;
import org.verapdf.crawler.api.document.DomainDocument;
import org.verapdf.crawler.api.document.DomainDocument_;
import org.verapdf.crawler.api.validation.ValidationJob;
import org.verapdf.crawler.api.validation.ValidationJob_;

import javax.persistence.criteria.*;
import java.util.List;

public class ValidationJobDAO extends AbstractDAO<ValidationJob> {
    public ValidationJobDAO(SessionFactory sessionFactory) {
        super(sessionFactory);
    }

    public ValidationJob save(ValidationJob validationJob) {
        return persist(validationJob);
    }

    public ValidationJob next() {
        return getValidationJobWithStatus(ValidationJob.Status.NOT_STARTED);
    }

    public ValidationJob current() {
        return getValidationJobWithStatus(ValidationJob.Status.IN_PROGRESS);
    }

    private ValidationJob getValidationJobWithStatus(ValidationJob.Status status) {
        CriteriaBuilder builder = currentSession().getCriteriaBuilder();
        CriteriaQuery<ValidationJob> criteriaQuery = builder.createQuery(ValidationJob.class);
        Root<ValidationJob> jobRoot = criteriaQuery.from(ValidationJob.class);
        criteriaQuery.where(builder.and(
                builder.equal(jobRoot.get(ValidationJob_.status), status),
                builder.isNotNull(jobRoot.get(ValidationJob_.document).get(DomainDocument_.url))
        ));
        return currentSession().createQuery(criteriaQuery).setMaxResults(1).uniqueResult();
    }

    public void remove(ValidationJob validationJob) {
        currentSession().delete(validationJob);
    }

    public void pause(String domain) {
        bulkUpdateState(domain, ValidationJob.Status.NOT_STARTED, ValidationJob.Status.PAUSED);
    }

    public void unpause(String domain) {
        bulkUpdateState(domain, ValidationJob.Status.PAUSED, ValidationJob.Status.NOT_STARTED);
    }

    public Long count(String domain) {
        CriteriaBuilder builder = currentSession().getCriteriaBuilder();
        CriteriaQuery<Long> criteriaQuery = builder.createQuery(Long.class);
        Root<ValidationJob> job = criteriaQuery.from(ValidationJob.class);
        criteriaQuery.select(builder.count(job));
        if (domain != null) {
            criteriaQuery.where(
                    builder.equal(job.get(ValidationJob_.document).get(DomainDocument_.crawlJob).get(CrawlJob_.domain), domain)
            );
        }
        return currentSession().createQuery(criteriaQuery).getSingleResult();
    }

    public List<ValidationJob> getDocuments(String domain, Integer limit) {
        CriteriaBuilder builder = currentSession().getCriteriaBuilder();
        CriteriaQuery<ValidationJob> criteriaQuery = builder.createQuery(ValidationJob.class);
        Root<ValidationJob> job = criteriaQuery.from(ValidationJob.class);
        criteriaQuery.select(builder.construct(
                ValidationJob.class,
                job.get(ValidationJob_.id),
                job.get(ValidationJob_.status)
        ));
        if (domain != null) {
            criteriaQuery.where(
                    builder.equal(job.get(ValidationJob_.document).get(DomainDocument_.crawlJob).get(CrawlJob_.domain), domain)
            );
        }
        criteriaQuery.orderBy(
                builder.asc(job.get(ValidationJob_.status)),
                builder.asc(job.get(ValidationJob_.id))
        );
        Query<ValidationJob> query = currentSession().createQuery(criteriaQuery);
        if (limit != null) {
            query.setMaxResults(limit);
        }
        return list(query);
    }

    private void bulkUpdateState(String domain, ValidationJob.Status oldStatus, ValidationJob.Status newStatus) {
        CriteriaBuilder builder = currentSession().getCriteriaBuilder();
        CriteriaUpdate<ValidationJob> criteriaUpdate = builder.createCriteriaUpdate(ValidationJob.class);
        Root<ValidationJob> jobRoot = criteriaUpdate.from(ValidationJob.class);

        Subquery<String> subquery = criteriaUpdate.subquery(String.class);
        Root<DomainDocument> subqueryRoot = subquery.from(DomainDocument.class);
        subquery.select(subqueryRoot.get(DomainDocument_.url));
        subquery.where(
                builder.equal(subqueryRoot.get(DomainDocument_.crawlJob).get(CrawlJob_.domain), domain)
        );
        criteriaUpdate.set(jobRoot.get(ValidationJob_.status), newStatus);
        criteriaUpdate.where(builder.and(
                jobRoot.get(ValidationJob_.id).in(subquery),
                builder.equal(jobRoot.get(ValidationJob_.status), oldStatus)
        ));
        currentSession().createQuery(criteriaUpdate).executeUpdate();
    }
}
