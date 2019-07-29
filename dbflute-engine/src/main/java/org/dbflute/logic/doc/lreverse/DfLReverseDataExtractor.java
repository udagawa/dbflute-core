/*
 * Copyright 2014-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 */
package org.dbflute.logic.doc.lreverse;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import javax.sql.DataSource;

import org.apache.torque.engine.database.model.Column;
import org.apache.torque.engine.database.model.ForeignKey;
import org.apache.torque.engine.database.model.Table;
import org.dbflute.DfBuildProperties;
import org.dbflute.helper.jdbc.facade.DfJFadCursorCallback;
import org.dbflute.helper.jdbc.facade.DfJFadStringConverter;
import org.dbflute.helper.jdbc.facade.DfJdbcFacade;
import org.dbflute.jdbc.ValueType;
import org.dbflute.optional.OptionalThing;
import org.dbflute.properties.DfBasicProperties;
import org.dbflute.s2dao.valuetype.basic.StringType;
import org.dbflute.s2dao.valuetype.basic.TimeType;
import org.dbflute.s2dao.valuetype.basic.TimestampType;
import org.dbflute.s2dao.valuetype.basic.UtilDateAsSqlDateType;
import org.dbflute.s2dao.valuetype.basic.UtilDateAsTimestampType;
import org.dbflute.s2dao.valuetype.plugin.BytesType;
import org.dbflute.s2dao.valuetype.plugin.StringClobType;
import org.dbflute.util.DfCollectionUtil;
import org.dbflute.util.DfTypeUtil;

/**
 * @author jflute
 * @author p1us2er0
 * @since 0.8.3 (2008/10/28 Tuesday)
 */
public class DfLReverseDataExtractor {

    // ===================================================================================
    //                                                                           Attribute
    //                                                                           =========
    protected final int MAX_SELF_REFERENCE_DEPTH = 60; // join count limit of mysql
    protected final DataSource _dataSource;
    protected int _extractingLimit = -1;
    protected int _largeBorder = -1;

    // ===================================================================================
    //                                                                         Constructor
    //                                                                         ===========
    public DfLReverseDataExtractor(DataSource dataSource) {
        _dataSource = dataSource;
    }

    // ===================================================================================
    //                                                                        Extract Data
    //                                                                        ============
    /**
     * Extract load-data.
     * @param tableMap The map of table. (NotNull)
     */
    public Map<String, DfLReverseDataResult> extractData(Map<String, Table> tableMap) {
        final Map<String, DfLReverseDataResult> loadDataMap = new LinkedHashMap<String, DfLReverseDataResult>();
        for (Entry<String, Table> entry : tableMap.entrySet()) {
            final String tableDbName = entry.getKey();
            final Table table = entry.getValue();
            final DfLReverseDataResult result = selectData(table);
            loadDataMap.put(tableDbName, result);
        }
        return loadDataMap;
    }

    protected DfLReverseDataResult selectData(Table table) {
        final String tableSqlName = table.getTableSqlNameDirectUse();

        boolean large = false;
        if (_largeBorder >= 0) {
            if (_extractingLimit < 0 || _largeBorder < _extractingLimit) {
                final DfJdbcFacade facade = new DfJdbcFacade(_dataSource);
                final int countAll = facade.selectCountAll(tableSqlName);
                if (countAll > _largeBorder) { // it's large
                    large = true;
                }
            }
        }

        final List<String> sqlList = DfCollectionUtil.newArrayList();
        buildSelfReferenceForeignKeyExtractingTrySqlList(table).ifPresent(trySqlList -> {
            sqlList.addAll(trySqlList);
        });
        sqlList.add(buildExtractingSql(table));
        if (large) {
            return processLargeData(table, sqlList);
        } else { // mainly here
            return processNormalData(table, sqlList);
        }
    }

    
    protected OptionalThing<List<String>> buildSelfReferenceForeignKeyExtractingTrySqlList(Table table) {
        final ForeignKey selfReferenceFK = table.getSelfReferenceForeignKey();
        if (selfReferenceFK == null || !selfReferenceFK.isSimpleKeyFK()) {
            return OptionalThing.empty();
        }

        final String tableSqlName = table.getTableSqlNameDirectUse();
        final Column firstLocalColumn = table.getColumn(selfReferenceFK.getFirstLocalColumnName());
        final String firstLocalName = firstLocalColumn.getColumnSqlNameDirectUse();
        final Column firstForeignColumn = table.getColumn(selfReferenceFK.getFirstForeignColumnName());
        final String firstForeignName = firstForeignColumn.getColumnSqlNameDirectUse();

        final List<String> sqlList = DfCollectionUtil.newArrayList();
        String sql = "WITH RECURSIVE cte AS (SELECT * FROM %1$s where %2$s is null UNION ALL SELECT child.* FROM %1$s AS child, cte WHERE cte.%3$s = child.%2$s) SELECT * FROM cte";
        sqlList.add(String.format(sql, tableSqlName, firstLocalName, firstForeignName));
        
        final StringBuilder sb = new StringBuilder();
        sb.append("select ");
        sb.append(table.getColumnList().stream().map(column -> "dfloc." + column.getColumnSqlNameDirectUse()).collect(Collectors.joining(", ")));
        sb.append(IntStream.range(0, MAX_SELF_REFERENCE_DEPTH)
                .mapToObj(index -> " left outer join " + tableSqlName + " dfrel_" + index + " on "
                        + (index == 0 ? "dfloc" : "dfrel_" + (index - 1)) + "." + firstLocalName + " = dfrel_" + index + "." + firstForeignName)
                .collect(Collectors.joining("", " from " + tableSqlName + " dfloc", "")));
        sb.append(IntStream.range(0, MAX_SELF_REFERENCE_DEPTH)
                .mapToObj(index -> " case when " + (index == 0 ? "dfloc" : "dfrel_" + index) + "." + firstLocalName
                        + " is null then 0 else 1 end asc,")
                .collect(Collectors.joining("", " order by", " dfloc." + firstForeignName + " asc")));
        sqlList.add(sb.toString());

        return OptionalThing.of(sqlList);
    }

    protected String buildExtractingSql(Table table) {
        final String sql;
        {
            final List<Column> columnList = table.getColumnList();
            final String selectClause = buildSelectClause(columnList);
            final String tableSqlName = table.getTableSqlNameDirectUse();
            final String fromClause = buildFromClause(tableSqlName);
            final String orderByClause = buildOrderByClause(table);
            final String sqlSuffix = buildSqlSuffix(table);
            sql = selectClause + fromClause + orderByClause + sqlSuffix;
        }
        return sql;
    }

    // ===================================================================================
    //                                                                         Normal Data
    //                                                                         ===========
    protected DfLReverseDataResult processNormalData(Table table, List<String> sqlList) {
        final DfJdbcFacade facade = new DfJdbcFacade(_dataSource);
        final Map<String, ValueType> valueTypeMap = createColumnValueTypeMap(table.getColumnList());
        final DfJFadStringConverter converter = createStringConverter();
        final Integer limit = _extractingLimit;
        final List<Map<String, String>> resultList = facade.selectStringList(sqlList, valueTypeMap, converter, limit);
        return new DfLReverseDataResult(resultList);
    }

    // ===================================================================================
    //                                                                          Large Data
    //                                                                          ==========
    protected DfLReverseDataResult processLargeData(Table table, final List<String> sqlList) {
        final DfJdbcFacade facade = new DfJdbcFacade(_dataSource);
        final Map<String, ValueType> valueTypeMap = createColumnValueTypeMap(table.getColumnList());
        final DfJFadStringConverter converter = createStringConverter();
        final DfJFadCursorCallback callback = facade.selectCursor(sqlList, valueTypeMap, converter);
        return new DfLReverseDataResult(callback);
    }

    public class DfLReverseLargeDataResultSetWrapper {
        protected final ResultSet _rs;
        protected final Map<String, ValueType> _columnValueTypeMap;

        public DfLReverseLargeDataResultSetWrapper(ResultSet rs, Map<String, ValueType> columnValueTypeMap) {
            _rs = rs;
            _columnValueTypeMap = columnValueTypeMap;
        }

        public boolean next() throws SQLException {
            return _rs.next();
        }

        public String getString(String columnName) throws SQLException {
            final ValueType valueType = _columnValueTypeMap.get(columnName);
            return convertToStringValue(valueType.getValue(_rs, columnName));
        }
    }

    // ===================================================================================
    //                                                                       JDBC Handling
    //                                                                       =============
    protected Map<String, ValueType> createColumnValueTypeMap(List<Column> columnList) {
        final Map<String, ValueType> valueTypeMap = new LinkedHashMap<String, ValueType>();
        for (Column column : columnList) {
            final String columnName = column.getName();

            // create value type for the column
            final ValueType valueType;
            if (column.isJavaNativeStringObject()) {
                if (column.isDbTypeStringClob()) {
                    valueType = new StringClobType();
                } else {
                    valueType = new StringType();
                }
            } else if (column.isJavaNativeDateObject()) {
                // date types should be treated correctly
                if (column.isJdbcTypeTime()) {
                    valueType = new TimeType();
                } else if (column.isJdbcTypeTimestamp()) {
                    valueType = new TimestampType();
                } else if (column.isJdbcTypeDate()) {
                    if (column.isDbTypeOracleDate()) {
                        valueType = new UtilDateAsTimestampType();
                    } else {
                        valueType = new UtilDateAsSqlDateType();
                    }
                } else { // no way
                    valueType = new TimestampType();
                }
            } else if (column.isJavaNativeBinaryObject()) {
                // unsupported BLOG as loda data
                valueType = new NullBytesType();
            } else {
                // other types are treated as string
                // because ReplaceSchema can accept them
                valueType = new StringType();
            }

            valueTypeMap.put(columnName, valueType);
        }
        return valueTypeMap;
    }

    protected static class NullBytesType extends BytesType {

        public NullBytesType() {
            super(BytesType.BLOB_TRAIT);
        }

        @Override
        public Object getValue(ResultSet rs, int index) throws SQLException {
            return null;
        };

        @Override
        public Object getValue(ResultSet rs, String columnName) throws SQLException {
            return null;
        };
    }

    protected void close(Connection conn, Statement st, ResultSet rs) {
        if (rs != null) {
            try {
                rs.close();
            } catch (SQLException ignored) {}
        }
        if (st != null) {
            try {
                st.close();
            } catch (SQLException ignored) {}
        }
        if (conn != null) {
            try {
                conn.close();
            } catch (SQLException ignored) {}
        }
    }

    // ===================================================================================
    //                                                                          SQL Clause
    //                                                                          ==========
    protected String buildSelectClause(List<Column> columnList) {
        final StringBuilder sb = new StringBuilder();
        for (Column column : columnList) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(column.getColumnSqlNameDirectUse());
        }
        return sb.insert(0, "select ").toString();
    }

    protected String buildFromClause(String tableName) {
        return " from " + tableName;
    }

    protected String buildOrderByClause(Table table) {
        final ForeignKey selfReferenceFK = table.getSelfReferenceForeignKey();
        final String orderBy;
        if (selfReferenceFK != null && selfReferenceFK.isSimpleKeyFK()) {
            final Column firstColumn = table.getColumn(selfReferenceFK.getFirstLocalColumnName());
            final String firstName = firstColumn.getColumnSqlNameDirectUse();
            orderBy = buildOrderByNullsFirst(firstName);
        } else {
            orderBy = "";
        }
        return orderBy;
    }

    protected String buildOrderByNullsFirst(String name) {
        return " order by case when " + name + " is null then 0 else 1 end asc, " + name + " asc";
    }

    protected String buildSqlSuffix(Table table) {
        if (_extractingLimit < 1) {
            return "";
        }
        if (hasLimitQuery()) {
            return " limit " + _extractingLimit;
        }
        return "";
    }

    protected boolean hasLimitQuery() {
        final DfBasicProperties prop = getBasicProperties();
        return prop.isDatabaseMySQL() || prop.isDatabasePostgreSQL() || prop.isDatabaseH2();
    }

    // ===================================================================================
    //                                                                          Conversion
    //                                                                          ==========
    protected DfJFadStringConverter createStringConverter() {
        return new DfJFadStringConverter() {
            public String convert(Object value) {
                return convertToStringValue(value);
            }
        };
    }

    protected String convertToStringValue(Object value) {
        if (value == null) {
            return null;
        }
        final String str;
        if (value instanceof String) {
            str = (String) value;
        } else if (value instanceof Timestamp) {
            final Timestamp timestamp = (Timestamp) value;
            str = formatDate(timestamp, "yyyy-MM-dd HH:mm:ss.SSS");
        } else if (value instanceof Time) {
            str = DfTypeUtil.toString((Time) value, "HH:mm:ss");
        } else if (value instanceof Date) {
            final Date date = (Date) value;
            str = formatDate(date, "yyyy-MM-dd HH:mm:ss");
        } else {
            str = value.toString();
        }
        return str;
    }

    protected String formatDate(Date date, String pattern) {
        final String prefix = (DfTypeUtil.isDateBC(date) ? "BC" : "");
        return prefix + DfTypeUtil.toString(date, pattern);
    }

    // ===================================================================================
    //                                                                          Properties
    //                                                                          ==========
    protected DfBuildProperties getProperties() {
        return DfBuildProperties.getInstance();
    }

    protected DfBasicProperties getBasicProperties() {
        return getProperties().getBasicProperties();
    }

    // ===================================================================================
    //                                                                            Accessor
    //                                                                            ========
    public int getExtractingLimit() {
        return _extractingLimit;
    }

    /**
     * @param extractingLimit The limit for extracting. (MinusAllowed: means no limit)
     */
    public void setExtractingLimit(int extractingLimit) {
        this._extractingLimit = extractingLimit;
    }

    public int getLargeBorder() {
        return _largeBorder;
    }

    /**
     * @param largeBorder The border count for large data. (MinusAllowed: means no border)
     */
    public void setLargeBorder(int largeBorder) {
        this._largeBorder = largeBorder;
    }
}
