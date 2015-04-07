package org.wikidata.query.rdf.tool.rdf;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.datatype.XMLGregorianCalendar;

import org.apache.http.Consts;
import org.apache.http.HttpEntity;
import org.apache.http.NameValuePair;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicHeader;
import org.apache.http.message.BasicNameValuePair;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.openrdf.model.Literal;
import org.openrdf.model.Statement;
import org.openrdf.query.Binding;
import org.openrdf.query.QueryEvaluationException;
import org.openrdf.query.QueryResultHandlerException;
import org.openrdf.query.TupleQueryResult;
import org.openrdf.query.impl.TupleQueryResultBuilder;
import org.openrdf.query.resultio.QueryResultParseException;
import org.openrdf.query.resultio.binary.BinaryQueryResultParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wikidata.query.rdf.common.uri.Entity;
import org.wikidata.query.rdf.common.uri.Provenance;
import org.wikidata.query.rdf.common.uri.SchemaDotOrg;
import org.wikidata.query.rdf.tool.exception.ContainedException;

import com.google.common.base.Charsets;
import com.google.common.io.CharStreams;

public class RdfRepository {
    private static final Logger log = LoggerFactory.getLogger(RdfRepository.class);

    private final CloseableHttpClient client = HttpClients.custom().setMaxConnPerRoute(100).setMaxConnTotal(100)
            .build();
    private final URI uri;
    private final Entity entityUris;

    public RdfRepository(URI uri, Entity entityUris) {
        this.uri = uri;
        this.entityUris = entityUris;
    }

    /**
     * Synchronizes the RDF repository's representation of an entity to be
     * exactly the provided statements. You can think of the RDF managed for an
     * entity as a tree rooted at the entity. The managed tree ends where the
     * next entity's managed tree starts. For example Q23 from wikidata includes
     * all statements about George Washington but not those about Martha
     * (Q191789) even though she is linked by the spouse attribute. On the other
     * hand the qualifiers on statements about George are included in George
     *
     * @param entityId id of the entity to sync
     * @param statements all known statements about the entity
     * @return the number of statements modified
     */
    public int sync(String entityId, Collection<Statement> statements) {
        // TODO this is becoming a mess too
        String entity = "entity:" + entityId;
        UpdateBuilder siteLinksBuilder = updateBuilder();
        siteLinksBuilder.delete("?s", "?p", "?o");
        siteLinksBuilder.where("?s", "schema:about", "entity:" + entityId);
        siteLinksBuilder.where("?s", "?p", "?o");
        if (!statements.isEmpty()) {
            siteLinksBuilder.where().notExists().values(statements, "?s", "?p", "?o");
        }

        UpdateBuilder referencesBuilder = updateBuilder();
        referencesBuilder.prefix("prov", Provenance.NAMESPACE);
        referencesBuilder.delete("?s", "?p", "?o");
        referencesBuilder.where(entity, "?statementPred", "?statement");
        referencesBuilder.where().add(startsWith("?statement", entityUris.statement().namespace()));
        referencesBuilder.where("?statement", "prov:wasDerivedFrom", "?s");
        referencesBuilder.where("?s", "?p", "?o");
        // We can't clear references that are still used elsewhere
        referencesBuilder.where().notExists()//
                .add("?otherStatement", "prov:wasDerivedFrom", "?s")//
                .add("?otherEntity", "?otherStatementPred", "?otherStatement")//
                .add("FILTER ( " + entity + " != ?otherEntity ) .");
        if (!statements.isEmpty()) {
            referencesBuilder.where().notExists().values(statements, "?s", "?p", "?o");
        }

        UpdateBuilder expandedStatementsBuilder = updateBuilder();
        expandedStatementsBuilder.delete("?s", "?p", "?o");
        expandedStatementsBuilder.where(entity, "?statementPred", "?s");
        expandedStatementsBuilder.where().add(startsWith("?s", entityUris.statement().namespace()));
        expandedStatementsBuilder.where("?s", "?p", "?o");
        if (!statements.isEmpty()) {
            expandedStatementsBuilder.where().notExists().values(statements, "?s", "?p", "?o");
        }

        UpdateBuilder generalBuilder = updateBuilder();
        generalBuilder.delete(entity, "?p", "?o");
        generalBuilder.where(entity, "?p", "?o");
        if (!statements.isEmpty()) {
            // TODO should this be statements, entity, ?p, ?o ?
            generalBuilder.where().notExists().values(statements, "?s", "?p", "?o");
        }

        /*
         * The order in which these are executed is important: if you think of
         * the triples that must be managed by this action as a tree then you
         * must start with the leaves first or when you clear out the trunk the
         * leaves will be orphaned.
         */
        StringBuilder command = new StringBuilder();
        command.append(siteLinksBuilder).append(";\n");
        command.append(referencesBuilder).append(";\n");
        command.append(expandedStatementsBuilder).append(";\n");
        command.append(generalBuilder).append(";\n");

        if (!statements.isEmpty()) {
            UpdateBuilder insertBuilder = updateBuilder();
            for (Statement statement : statements) {
                insertBuilder.insert(statement.getSubject(), statement.getPredicate(), statement.getObject());
            }
            command.append(insertBuilder).append(";\n");
        }
        long start = System.currentTimeMillis();
        int modified = execute("update", UPDATE_COUNT_RESPONSE, command.toString());
        log.debug("Updating {} took {} millis and modified {} statements", entityId,
                System.currentTimeMillis() - start, modified);
        return modified;
    }

    /**
     * Does the triple store have this revision or better.
     */
    public boolean hasRevision(String entityId, long revision) {
        // TODO building queries with strings sucks because escaping....
        StringBuilder prefixes = new StringBuilder();
        prefixes.append("PREFIX schema: <").append(SchemaDotOrg.NAMESPACE).append(">\n");
        prefixes.append("PREFIX entity: <").append(entityUris.namespace()).append(">\n");
        return ask(String.format(Locale.ROOT, "%sASK {\n  entity:%s schema:version ?v .\n  FILTER (?v >= %s)\n}",
                prefixes, entityId, revision));
    }

    /**
     * Fetch the last wikidata update time.
     *
     * @return the date or null if there are no update times
     */
    public Date fetchLastUpdate() {
        // TODO this is very likely inefficient
        TupleQueryResult result = query("PREFIX schema: <http://schema.org/>\nSELECT (MAX(?lastUpdate) as ?maxLastUpdate)\nWHERE { ?s schema:dateModified ?lastUpdate . }");
        try {
            if (!result.hasNext()) {
                return null;
            }
            Binding maxLastUpdate = result.next().getBinding("maxLastUpdate");
            if (maxLastUpdate == null) {
                return null;
            }
            XMLGregorianCalendar xmlCalendar = ((Literal) maxLastUpdate.getValue()).calendarValue();
            /*
             * We convert rather blindly to a GregorianCalendar because we're
             * reasonably sure all the right data is present.
             */
            GregorianCalendar calendar = xmlCalendar.toGregorianCalendar();
            return calendar.getTime();
        } catch (QueryEvaluationException e) {
            throw new RuntimeException("Error evaluating query", e);
        }
    }

    /**
     * Execute a SPARQL ask and parse the boolean result.
     */
    public boolean ask(String sparql) {
        return execute("query", ASK_QUERY_RESPONSE, sparql);
    }

    /**
     * Execute some SPARQL which returns a results table.
     */
    public TupleQueryResult query(String sparql) {
        return execute("query", TUPLE_QUERY_RESPONSE, sparql);
    }

    /**
     * Execute some raw SPARQL.
     *
     * @param type name of the parameter in which to send sparql
     * @return results string from the server
     */
    protected <T> T execute(String type, ResponseHandler<T> responseHandler, String sparql) {
        HttpPost post = new HttpPost(uri);
        post.setHeader(new BasicHeader("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8"));
        // Note that Blazegraph totally ignores the Accept header for SPARQL
        // updates like this so the response is just html....
        if (responseHandler.acceptHeader() != null) {
            post.setHeader(new BasicHeader("Accept", responseHandler.acceptHeader()));
        }

        // TODO we might want to look into Blazegraph's incremental update
        // reporting.....
        List<NameValuePair> entity = new ArrayList<>();
        entity.add(new BasicNameValuePair(type, sparql));
        post.setEntity(new UrlEncodedFormEntity(entity, Consts.UTF_8));
        try {
            try (CloseableHttpResponse response = client.execute(post)) {
                if (response.getStatusLine().getStatusCode() != 200) {
                    throw new ContainedException("Non-200 response from triple store:  " + response + " body=\n"
                            + responseBodyAsString(response));
                }
                return responseHandler.parse(response.getEntity());
            }
        } catch (IOException e) {
            throw new RuntimeException("Error updating triple store", e);
        }
    }

    private String responseBodyAsString(CloseableHttpResponse response) throws IOException {
        return CharStreams.toString(new InputStreamReader(response.getEntity().getContent(), "UTF-8"));
    }

    private UpdateBuilder updateBuilder() {
        UpdateBuilder b = new UpdateBuilder();
        b.prefix("entity", entityUris.namespace());
        b.prefix("schema", SchemaDotOrg.NAMESPACE);
        return b;
    }

    private String startsWith(String name, String prefix) {
        StringBuilder filter = new StringBuilder();
        filter.append("FILTER( STRSTARTS(STR(");
        filter.append(name).append("), \"");
        filter.append(prefix);
        filter.append("\") ) .");
        return filter.toString();
    }

    /**
     * Passed to execute to setup the accept header and parse the response. Its
     * super ultra mega important to parse the response in execute because
     * execute manages closing the http response object. If execute return the
     * input stream after closing the response then everything would
     * <strong>mostly</strong> work but things would blow up with strange socket
     * closed errors.
     *
     * @param <T> the type of response parsed
     */
    private interface ResponseHandler<T> {
        public String acceptHeader();

        public T parse(HttpEntity entity) throws IOException;
    }

    protected static ResponseHandler<Integer> UPDATE_COUNT_RESPONSE = new UpdateCountResponse();
    protected static ResponseHandler<TupleQueryResult> TUPLE_QUERY_RESPONSE = new TupleQueryResponse();
    protected static ResponseHandler<Boolean> ASK_QUERY_RESPONSE = new AskQueryResponse();

    /**
     * Attempts to log update response information but very likely only works
     * for Blazegraph.
     */
    protected static class UpdateCountResponse implements ResponseHandler<Integer> {
        private static final Pattern ELAPSED_LINE = Pattern.compile("><p>totalElapsed=[^ ]+ elapsed=([^<]+)</p");
        private static final Pattern COMMIT_LINE = Pattern
                .compile("><hr><p>COMMIT: totalElapsed=[^ ]+ commitTime=[^ ]+ mutationCount=([^<]+)</p");

        @Override
        public String acceptHeader() {
            return null;
        }

        @Override
        public Integer parse(HttpEntity entity) throws IOException {
            Integer mutationCount = null;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(entity.getContent(), Charsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    Matcher m = ELAPSED_LINE.matcher(line);
                    if (m.matches()) {
                        log.trace("elapsed = {}", m.group(1));
                    }
                    m = COMMIT_LINE.matcher(line);
                    if (m.matches()) {
                        log.trace("mutation count = {}", m.group(1));
                        mutationCount = Integer.valueOf(m.group(1));
                    }
                }
            }
            if (mutationCount == null) {
                throw new IOException("Couldn't find the mutation count!");
            }
            return mutationCount;
        }
    }

    private static class TupleQueryResponse implements ResponseHandler<TupleQueryResult> {
        @Override
        public String acceptHeader() {
            return "application/x-binary-rdf-results-table";
        }

        @Override
        public TupleQueryResult parse(HttpEntity entity) throws IOException {
            BinaryQueryResultParser p = new BinaryQueryResultParser();
            TupleQueryResultBuilder collector = new TupleQueryResultBuilder();
            p.setQueryResultHandler(collector);
            try {
                p.parseQueryResult(entity.getContent());
            } catch (QueryResultParseException | QueryResultHandlerException | IllegalStateException e) {
                throw new RuntimeException("Error parsing query", e);
            }
            return collector.getQueryResult();
        }
    }

    private static class AskQueryResponse implements ResponseHandler<Boolean> {
        @Override
        public String acceptHeader() {
            return "application/json";
        }

        @Override
        public Boolean parse(HttpEntity entity) throws IOException {
            try {
                JSONObject response = (JSONObject) new JSONParser().parse(new InputStreamReader(entity.getContent(),
                        Charsets.UTF_8));
                return (Boolean) response.get("boolean");
            } catch (ParseException e) {
                throw new IOException("Error parsing response", e);
            }
        }
    }
}
