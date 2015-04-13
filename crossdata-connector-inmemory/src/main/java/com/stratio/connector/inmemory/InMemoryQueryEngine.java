/*
 * Licensed to STRATIO (C) under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information
 * regarding copyright ownership.  The STRATIO (C) licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.stratio.connector.inmemory;

import com.codahale.metrics.Timer;
import com.stratio.connector.inmemory.datastore.InMemoryDatastore;
import com.stratio.connector.inmemory.datastore.datatypes.JoinValue;
import com.stratio.connector.inmemory.datastore.datatypes.SimpleValue;
import com.stratio.crossdata.common.connector.IQueryEngine;
import com.stratio.crossdata.common.connector.IResultHandler;
import com.stratio.crossdata.common.data.Cell;
import com.stratio.crossdata.common.data.ColumnName;
import com.stratio.crossdata.common.data.ResultSet;
import com.stratio.crossdata.common.data.Row;
import com.stratio.crossdata.common.exceptions.ConnectorException;
import com.stratio.crossdata.common.exceptions.ExecutionException;
import com.stratio.crossdata.common.exceptions.UnsupportedException;
import com.stratio.crossdata.common.logicalplan.LogicalWorkflow;
import com.stratio.crossdata.common.logicalplan.Project;
import com.stratio.crossdata.common.logicalplan.Select;
import com.stratio.crossdata.common.metadata.ColumnMetadata;
import com.stratio.crossdata.common.metadata.ColumnType;
import com.stratio.crossdata.common.result.QueryResult;
import com.stratio.crossdata.common.statements.structures.Selector;
import org.apache.log4j.Logger;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static com.codahale.metrics.MetricRegistry.name;

/**
 * Class that implements the  {@link com.stratio.crossdata.common.connector.IQueryEngine}.
 */
public class InMemoryQueryEngine implements IQueryEngine {

    /**
     * Class logger.
     */
    private static final Logger LOG = Logger.getLogger(InMemoryQueryEngine.class);

    /**
     * Link to the in memory connector.
     */
    private final InMemoryConnector connector;

    private final Timer executeTimer;

    /**
     * Class constructor.
     * @param connector The linked {@link com.stratio.connector.inmemory.InMemoryConnector}.
     */
    public InMemoryQueryEngine(InMemoryConnector connector){
        this.connector = connector;
        executeTimer = new Timer();
        String timerName = name(InMemoryQueryEngine.class, "execute");
        connector.registerMetric(timerName, executeTimer);
    }

    @Override
    public QueryResult execute(LogicalWorkflow workflow) throws ConnectorException {
        //Init Metric
        Timer.Context executeTimerContext = executeTimer.time();


        Project projectOne =  (Project)workflow.getInitialSteps().get(0);
        InMemoryDatastore datastore = connector.getDatastore(projectOne.getClusterName());

        if(datastore == null){
            throw new ExecutionException("No datastore connected to " + projectOne.getClusterName());
        }

        InMemoryQuery query = new InMemoryQuery(projectOne);

        List<SimpleValue[]> results = null;
        try {
            results = datastore.search(query.catalogName, query.tableName.getName(), query.relations, query.outputColumns);
        } catch (Exception e) {
            throw new ExecutionException("Cannot perform execute operation: " + e.getMessage(), e);
        }

        List<List<SimpleValue[]>> externalJoinsResult = new ArrayList<>();

        if (query.joinStep != null && results.size()>0){
            Project projectTwo =  (Project) workflow.getInitialSteps().get(1);
            InMemoryQuery queryTwo = new InMemoryQuery(projectTwo, results);

            List<SimpleValue[]> resultsTwo = null;
            try {
                resultsTwo = datastore.search(queryTwo.catalogName, queryTwo.tableName.getName(), queryTwo.relations, queryTwo.outputColumns);
            } catch (Exception e) {
                throw new ExecutionException("Cannot perform execute operation: " + e.getMessage(), e);
            }
            externalJoinsResult.add(resultsTwo);

        }

        List<SimpleValue[]> joinResult = joinResults(results, externalJoinsResult);
        //joinResult = query.orderResult(joinResult);

        QueryResult finalResult = toCrossdataResults((Select) workflow.getLastStep(), query.limit, joinResult);

        //End Metric
        long millis = executeTimerContext.stop();
        LOG.info("Query took " + millis + " nanoseconds");

        return finalResult;
    }

    private List<SimpleValue[]> joinResults(List<SimpleValue[]> results, List<List<SimpleValue[]>> joinTables) {

        List<SimpleValue[]> finalResult = new ArrayList<SimpleValue[]>();
        if (joinTables.size() > 0) {
            for (SimpleValue[] row : results){
                SimpleValue[] joinedRow = joinRow(row, joinTables);
                if (joinedRow != null) {
                    finalResult.add(joinedRow);
                }
            }

        }else{
            for (SimpleValue[] row : results) {
                finalResult.add(getRowValues(row).toArray(new SimpleValue[]{}));
            }
        }

        return finalResult;
    }

    private List<SimpleValue> getRowValues(SimpleValue[] row) {
        List<SimpleValue> finalRow = new ArrayList();
        for(SimpleValue field:row){
            if (!(field instanceof JoinValue)){
                finalRow.add(field);
            }
        }
        return finalRow;
    }

    private SimpleValue[] joinRow(SimpleValue[] mainRow,  List<List<SimpleValue[]>> otherTables){
        List<SimpleValue> finalJoinRow = new ArrayList<>();

        //Search JoinRows
        for(SimpleValue field:mainRow){
            if(field instanceof JoinValue){
                List<SimpleValue> joinResult = calculeJoin((JoinValue) field, otherTables);
                if (joinResult != null && joinResult.size()>0){
                    finalJoinRow.addAll(joinResult);
                }else{
                    return null;
                }
            }else{
                finalJoinRow.add(field);
            }
        }

        return finalJoinRow.toArray(new SimpleValue[]{});
    }

    private List<SimpleValue> calculeJoin(JoinValue join, List<List<SimpleValue[]>> otherTables){
        List<SimpleValue> joinResult = new ArrayList<>();
        boolean matched = false;

        for(List<SimpleValue[]> table: otherTables){
            rows: for(SimpleValue[] row: table){
                for(SimpleValue field:row){
                    if (field instanceof JoinValue){
                        JoinValue tableJoin = (JoinValue) field;
                        if (join.joinMatch(tableJoin)){
                            matched = true;
                            joinResult.addAll(getRowValues(row));
                            continue rows;
                        }
                    }
                }
            }
        }

        if (matched){
            return joinResult;
        }else{
            return null;
        }
    }




    /**
     * Transform a set of results into a Crossdata query result.
     * @param selectStep The {@link com.stratio.crossdata.common.logicalplan.Select} step to set the alias.
     * @param limit The query limit.
     * @param results The set of results retrieved from the database.
     * @return A {@link com.stratio.crossdata.common.result.QueryResult}.
     */
    private QueryResult toCrossdataResults(Select selectStep, int limit, List<SimpleValue[]> results) {
        ResultSet crossdataResults = new ResultSet();

        final List<String> columnAlias = new ArrayList<>();
        final List<ColumnMetadata> columnMetadataList = new ArrayList<>();
        for(Selector outputSelector : selectStep.getOutputSelectorOrder()){
            //ColumnSelector selector = new ColumnSelector(outputSelector.getColumnName());
            ColumnName columnName = outputSelector.getColumnName();
            String alias = selectStep.getColumnMap().get(outputSelector);
            if(alias == null){
                alias = columnName.getName();
            }
            columnAlias.add(alias);
            columnName.setAlias(alias);
            ColumnType columnType = selectStep.getTypeMapFromColumnName().get(outputSelector);
            ColumnMetadata metadata = new ColumnMetadata(
                    columnName, null, columnType);
            columnMetadataList.add(metadata);
        }

        //Store the metadata information
        crossdataResults.setColumnMetadata(columnMetadataList);

        int resultToAdd = results.size();
        if(limit != -1){
            resultToAdd = Math.min(results.size(), limit);
        }

        //Store the rows.
        List<Row> crossdataRows = new ArrayList<>();
        Iterator<SimpleValue[]> rowIterator = results.iterator();
        while(rowIterator.hasNext() && resultToAdd > 0){
            crossdataRows.add(toCrossdataRow(rowIterator.next(), columnAlias));
            resultToAdd--;
        }

        crossdataResults.setRows(crossdataRows);
        return QueryResult.createQueryResult(
                crossdataResults,
                0,
                true);
    }

    /**
     * Transform the results into a crossdata row.
     * @param row The in-memory row.
     * @param columnAlias The list of column alias.
     * @return A {@link com.stratio.crossdata.common.data.Row}
     */
    private Row toCrossdataRow(SimpleValue[] row, List<String> columnAlias) {
        Row result = new Row();

        for (String alias:columnAlias){
            for(SimpleValue field: row){
                if (alias.contains(field.getColumn().getName())){
                    result.addCell(alias, new Cell(field.getValue()));
                    break;
                }
            }
        }

        return result;
    }

    @Override
    public void asyncExecute(String queryId, LogicalWorkflow workflow, IResultHandler resultHandler)
            throws ConnectorException {
        throw new UnsupportedException("Async query execution is not supported");
    }

    @Override public void pagedExecute(
            String queryId,
            LogicalWorkflow workflow,
            IResultHandler resultHandler,
            int pageSize) throws ConnectorException {
        QueryResult queryResult = execute(workflow);
        ResultSet resultSet = queryResult.getResultSet();
        List<Row> rows = resultSet.getRows();
        int counter = 0;
        int page = 0;
        List<Row> partialRows = new ArrayList<>();
        for(Row row: rows){
            if(counter >= pageSize){
                QueryResult partialQueryResult = buildPartialResult(
                        partialRows,
                        queryResult.getResultSet().getColumnMetadata(),
                        queryId,
                        page,
                        false);
                resultHandler.processResult(partialQueryResult);
                counter = 0;
                page++;
                partialRows = new ArrayList<>();
            }
            partialRows.add(row);
            counter++;
        }
        QueryResult partialQueryResult = buildPartialResult(
                partialRows,
                queryResult.getResultSet().getColumnMetadata(),
                queryId,
                page,
                true);
        resultHandler.processResult(partialQueryResult);
    }

    QueryResult buildPartialResult(List<Row> partialRows, List<ColumnMetadata> columnsMetadata, String queryId,
            int page, boolean lastResult){
        ResultSet partialResultSet = new ResultSet();
        partialResultSet.setRows(partialRows);
        partialResultSet.setColumnMetadata(columnsMetadata);
        QueryResult partialQueryResult = QueryResult.createQueryResult(partialResultSet, page, lastResult);
        partialQueryResult.setQueryId(queryId);
        return partialQueryResult;
    }

    @Override
    public void stop(String queryId) throws ConnectorException {
        throw new UnsupportedException("Stopping running queries is not supported");
    }
}
