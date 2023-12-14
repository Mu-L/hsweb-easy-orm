package org.hswebframework.ezorm.rdb.supports.oracle;

import org.hswebframework.ezorm.rdb.TestJdbcReactiveSqlExecutor;
import org.hswebframework.ezorm.rdb.TestSyncSqlExecutor;
import org.hswebframework.ezorm.rdb.executor.SqlRequests;
import org.hswebframework.ezorm.rdb.executor.SyncSqlExecutor;
import org.hswebframework.ezorm.rdb.metadata.RDBDatabaseMetadata;
import org.hswebframework.ezorm.rdb.metadata.RDBIndexMetadata;
import org.hswebframework.ezorm.rdb.metadata.dialect.Dialect;
import org.junit.Assert;
import org.junit.Test;
import reactor.test.StepVerifier;

import java.util.List;

public class OracleIndexMetadataParserTest {

    @Test
    public void test() {

        SyncSqlExecutor sqlExecutor = new TestSyncSqlExecutor(new OracleConnectionProvider());
        try {

            OracleSchemaMetadata schema = new OracleSchemaMetadata("SYSTEM");
            schema.setDatabase(new RDBDatabaseMetadata(Dialect.ORACLE));
            schema.addFeature(new TestJdbcReactiveSqlExecutor(new OracleConnectionProvider()));

            OracleIndexMetadataParser parser = OracleIndexMetadataParser.of(schema);

            schema.addFeature(sqlExecutor);

            sqlExecutor.execute(SqlRequests.of(
                    "create table test_index_parser(" +
                            "id varchar2(32) primary key," +
                            "name varchar2(32) not null," +
                            "age int," +
                            "addr varchar2(128))"));

            sqlExecutor.execute(SqlRequests.of("create index test_index_0 on test_index_parser (age)"));

            sqlExecutor.execute(SqlRequests.of("create unique index test_index_2 on test_index_parser (name)"));

            sqlExecutor.execute(SqlRequests.of("create index test_index_3 on test_index_parser (name,addr desc)"));

            List<RDBIndexMetadata> list = parser.parseTableIndex("test_index_parser");

            Assert.assertEquals(list.size(), 4);
            Assert.assertTrue(list.stream().anyMatch(RDBIndexMetadata::isPrimaryKey));

            parser.parseTableIndexReactive("test_index_parser")
                  .as(StepVerifier::create)
                  .expectNextCount(4)
                  .verifyComplete();

        } finally {
            try {
                sqlExecutor.execute(SqlRequests.of("drop table test_index_parser"));
            } catch (Exception e) {

            }
        }

    }
}