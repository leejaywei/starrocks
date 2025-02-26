// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.starrocks.sql.optimizer.rule.transformation.materialization;

import com.starrocks.catalog.Database;
import com.starrocks.catalog.MaterializedView;
import com.starrocks.catalog.Partition;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.sql.plan.PlanTestBase;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class MvRefreshAndRewriteIcebergTest extends MvRewriteTestBase {
    @BeforeClass
    public static void beforeClass() throws Exception {
        MvRewriteTestBase.beforeClass();
    }

    @Test
    public void testStr2DateMVRefreshRewriteSingleTable_UnionRewrite() throws Exception {
        String mvName = "test_mv1";
        starRocksAssert.withMaterializedView("create materialized view " + mvName + " " +
                "partition by str2date(d,'%Y-%m-%d') " +
                "distributed by hash(a) " +
                "REFRESH DEFERRED MANUAL " +
                "PROPERTIES (\n" +
                "'replication_num' = '1'" +
                ") " +
                "as select a, b, d, bitmap_union(to_bitmap(t1.c))" +
                " from iceberg0.partitioned_db.part_tbl1 as t1 " +
                " group by a, b, d;");
        Database testDb = GlobalStateMgr.getCurrentState().getDb("test");
        MaterializedView materializedView = ((MaterializedView) testDb.getTable(mvName));

        // initial create
        starRocksAssert.getCtx().executeSql("refresh materialized view " + mvName + " partition start('2023-08-01') " +
                "end ('2023-08-02') force with sync mode");
        List<String> partitions =
                materializedView.getPartitions().stream().map(Partition::getName).sorted()
                        .collect(Collectors.toList());
        Assert.assertEquals(Arrays.asList("p20230801_20230802"), partitions);

        {
            String query = "select t1.a, t2.b, t1.d, count(distinct t1.c)\n" +
                    " from  iceberg0.partitioned_db.part_tbl1 as t1 \n" +
                    " left join iceberg0.partitioned_db.part_tbl2 t2 on t1.d=t2.d \n" +
                    " left join iceberg0.partitioned_db.part_tbl3 t3 on t1.d=t3.d \n" +
                    " where t1.d in ('2023-08-01')\n" +
                    " group by t1.a, t2.b, t1.d;";
            String plan = getFragmentPlan(query);
            PlanTestBase.assertNotContains(plan, "test_mv1");
        }

        connectContext.getSessionVariable().setMaterializedViewRewriteMode("force");
        {
            String query = "select a, b, d, count(distinct t1.c)\n" +
                    " from iceberg0.partitioned_db.part_tbl1 as t1 \n" +
                    " where d='2023-08-01'" +
                    " group by a, b, d;";
            String plan = getFragmentPlan(query);
            PlanTestBase.assertNotContains(plan, "UNION");
            PlanTestBase.assertContains(plan, "0:OlapScanNode\n" +
                    "     TABLE: test_mv1\n" +
                    "     PREAGGREGATION: ON\n" +
                    "     partitions=1/1");
        }

        connectContext.getSessionVariable().setMaterializedViewRewriteMode("force");
        {
            String query = "select a, b, d, count(distinct t1.c)\n" +
                    " from iceberg0.partitioned_db.part_tbl1 as t1 \n" +
                    " where t1.d >= '2023-08-01' \n" +
                    " group by a, b, d;";
            String plan = getFragmentPlan(query);
            PlanTestBase.assertContains(plan, "UNION");

            PlanTestBase.assertContains(plan, "OlapScanNode\n" +
                    "     TABLE: test_mv1\n" +
                    "     PREAGGREGATION: ON\n" +
                    "     partitions=1/1");
            PlanTestBase.assertContains(plan, "IcebergScanNode\n" +
                    "     TABLE: part_tbl1\n" +
                    "     PREDICATES: 13: d != '2023-08-01'");
        }
        {
            String query = "select a, b, d, count(distinct t1.c)\n" +
                    " from iceberg0.partitioned_db.part_tbl1 as t1 \n" +
                    " group by a, b, d;";
            String plan = getFragmentPlan(query);
            PlanTestBase.assertContains(plan, "UNION");

            PlanTestBase.assertContains(plan, "OlapScanNode\n" +
                    "     TABLE: test_mv1\n" +
                    "     PREAGGREGATION: ON\n" +
                    "     partitions=1/1");
            PlanTestBase.assertContains(plan, "IcebergScanNode\n" +
                    "     TABLE: part_tbl1\n" +
                    "     PREDICATES: 13: d != '2023-08-01'");
        }

        starRocksAssert.getCtx().executeSql("refresh materialized view " + mvName + " partition start('2023-08-02') " +
                "end ('2023-08-03') force with sync mode");
        partitions =
                materializedView.getPartitions().stream().map(Partition::getName).sorted()
                        .collect(Collectors.toList());
        Assert.assertEquals(Arrays.asList("p20230801_20230802", "p20230802_20230803"), partitions);

        {
            String query = "select a, b, d, count(distinct t1.c)\n" +
                    " from iceberg0.partitioned_db.part_tbl1 as t1 \n" +
                    " where t1.d >= '2023-08-01' \n" +
                    " group by a, b, d;";
            String plan = getFragmentPlan(query);
            PlanTestBase.assertContains(plan, "UNION");

            PlanTestBase.assertContains(plan, "OlapScanNode\n" +
                    "     TABLE: test_mv1\n" +
                    "     PREAGGREGATION: ON\n" +
                    "     partitions=2/2");
            PlanTestBase.assertContains(plan, "IcebergScanNode\n" +
                    "     TABLE: part_tbl1\n" +
                    "     PREDICATES: 13: d != '2023-08-01', 13: d != '2023-08-02'");
        }
        {
            String query = "select a, b, d, count(distinct t1.c)\n" +
                    " from iceberg0.partitioned_db.part_tbl1 as t1 \n" +
                    " group by a, b, d;";
            String plan = getFragmentPlan(query);
            PlanTestBase.assertContains(plan, "UNION");

            PlanTestBase.assertContains(plan, "OlapScanNode\n" +
                    "     TABLE: test_mv1\n" +
                    "     PREAGGREGATION: ON\n" +
                    "     partitions=2/2");
            PlanTestBase.assertContains(plan, "IcebergScanNode\n" +
                    "     TABLE: part_tbl1\n" +
                    "     PREDICATES: 13: d != '2023-08-01', 13: d != '2023-08-02'");
        }
        starRocksAssert.dropMaterializedView(mvName);
    }

    @Test
    public void testStr2DateMVRefreshRewrite_InnerJoin_FullRefresh() throws Exception {
        String mvName = "test_mv1";
        starRocksAssert.withMaterializedView("create materialized view " + mvName + " " +
                "partition by str2date(d,'%Y-%m-%d') " +
                "distributed by hash(a) " +
                "REFRESH DEFERRED MANUAL " +
                "PROPERTIES (\n" +
                "'replication_num' = '1'" +
                ") " +
                "as select  t1.a, t2.b, t3.c, t1.d " +
                " from  iceberg0.partitioned_db.part_tbl1 as t1 " +
                " inner join iceberg0.partitioned_db.part_tbl2 t2 on t1.d=t2.d " +
                " inner join iceberg0.partitioned_db.part_tbl3 t3 on t1.d=t3.d ;");
        Database testDb = GlobalStateMgr.getCurrentState().getDb("test");
        MaterializedView materializedView = ((MaterializedView) testDb.getTable(mvName));

        // initial create
        starRocksAssert.getCtx().executeSql("refresh materialized view " + mvName + " force with sync mode");
        List<String> partitions =
                materializedView.getPartitions().stream().map(Partition::getName).sorted()
                        .collect(Collectors.toList());
        Assert.assertEquals(Arrays.asList("p20230801_20230802", "p20230802_20230803", "p20230803_20230804"), partitions);

        {
            String query = "select  t1.a, t2.b, t3.c, t1.d " +
                    " from  iceberg0.partitioned_db.part_tbl1 as t1 " +
                    " inner join iceberg0.partitioned_db.part_tbl2 t2 on t1.d=t2.d " +
                    " inner join iceberg0.partitioned_db.part_tbl3 t3 on t1.d=t3.d " +
                    " where t1.d='2023-08-01';";
            String plan = getFragmentPlan(query);
            PlanTestBase.assertContains(plan, "0:OlapScanNode\n" +
                    "     TABLE: test_mv1\n" +
                    "     PREAGGREGATION: ON\n" +
                    "     partitions=1/3\n" +
                    "     rollup: test_mv1");
        }

        {
            String query = "select  t1.a, t2.b, t3.c, t1.d " +
                    " from  iceberg0.partitioned_db.part_tbl1 as t1 " +
                    " inner join iceberg0.partitioned_db.part_tbl2 t2 on t1.d=t2.d " +
                    " inner join iceberg0.partitioned_db.part_tbl3 t3 on t1.d=t3.d " +
                    " where t1.d>='20230801';";
            String plan = getFragmentPlan(query);
            PlanTestBase.assertContains(plan, "0:OlapScanNode\n" +
                    "     TABLE: test_mv1\n" +
                    "     PREAGGREGATION: ON\n" +
                    "     PREDICATES: 16: d >= '20230801'\n" +
                    "     partitions=3/3\n" +
                    "     rollup: test_mv1");
        }

        {
            String query = "select  t1.a, t2.b, t3.c, t1.d " +
                    " from  iceberg0.partitioned_db.part_tbl1 as t1 " +
                    " inner join iceberg0.partitioned_db.part_tbl2 t2 on t1.d=t2.d " +
                    " inner join iceberg0.partitioned_db.part_tbl3 t3 on t1.d=t3.d " +
                    " where t1.d < '20230802';";
            String plan = getFragmentPlan(query);
            PlanTestBase.assertContains(plan, "0:OlapScanNode\n" +
                    "     TABLE: test_mv1\n" +
                    "     PREAGGREGATION: ON\n" +
                    "     partitions=1/3\n" +
                    "     rollup: test_mv1");
        }

        starRocksAssert.dropMaterializedView(mvName);
    }

    @Test
    public void testStr2DateMVRefreshRewrite_InnerJoin_PartialRefresh() throws Exception {
        String mvName = "test_mv1";
        starRocksAssert.withMaterializedView("create materialized view " + mvName + " " +
                "partition by str2date(d,'%Y-%m-%d') " +
                "distributed by hash(a) " +
                "REFRESH DEFERRED MANUAL " +
                "PROPERTIES (\n" +
                "'replication_num' = '1'" +
                ") " +
                "as select  t1.a, t2.b, t3.c, t1.d " +
                " from  iceberg0.partitioned_db.part_tbl1 as t1 " +
                " inner join iceberg0.partitioned_db.part_tbl2 t2 on t1.d=t2.d " +
                " inner join iceberg0.partitioned_db.part_tbl3 t3 on t1.d=t3.d ;");
        Database testDb = GlobalStateMgr.getCurrentState().getDb("test");
        MaterializedView materializedView = ((MaterializedView) testDb.getTable(mvName));

        // initial create
        starRocksAssert.getCtx().executeSql("refresh materialized view " + mvName + " partition start('2023-08-01') " +
                "end ('2023-08-02') force with sync mode");
        List<String> partitions =
                materializedView.getPartitions().stream().map(Partition::getName).sorted()
                        .collect(Collectors.toList());
        Assert.assertEquals(Arrays.asList("p20230801_20230802"), partitions);

        {
            String query = "select  t1.a, t2.b, t3.c, t1.d " +
                    " from  iceberg0.partitioned_db.part_tbl1 as t1 " +
                    " inner join iceberg0.partitioned_db.part_tbl2 t2 on t1.d=t2.d " +
                    " inner join iceberg0.partitioned_db.part_tbl3 t3 on t1.d=t3.d " +
                    " where t1.d='2023-08-01';";
            String plan = getFragmentPlan(query);
            PlanTestBase.assertContains(plan, "0:OlapScanNode\n" +
                    "     TABLE: test_mv1\n" +
                    "     PREAGGREGATION: ON\n" +
                    "     partitions=1/1\n" +
                    "     rollup: test_mv1");
        }

        {
            String query = "select  t1.a, t2.b, t3.c, t1.d " +
                    " from  iceberg0.partitioned_db.part_tbl1 as t1 " +
                    " inner join iceberg0.partitioned_db.part_tbl2 t2 on t1.d=t2.d " +
                    " inner join iceberg0.partitioned_db.part_tbl3 t3 on t1.d=t3.d " +
                    " where t1.d>='20230801';";
            String plan = getFragmentPlan(query);
            PlanTestBase.assertNotContains(plan, "test_mv1");
        }

        {
            String query = "select  t1.a, t2.b, t3.c, t1.d " +
                    " from  iceberg0.partitioned_db.part_tbl1 as t1 " +
                    " inner join iceberg0.partitioned_db.part_tbl2 t2 on t1.d=t2.d " +
                    " inner join iceberg0.partitioned_db.part_tbl3 t3 on t1.d=t3.d " +
                    " where t1.d < '20230802';";
            String plan = getFragmentPlan(query);
            PlanTestBase.assertContains(plan, "UNION");
            PlanTestBase.assertContains(plan, "12:OlapScanNode\n" +
                    "     TABLE: test_mv1\n" +
                    "     PREAGGREGATION: ON\n" +
                    "     partitions=1/1\n" +
                    "     rollup: test_mv1");
        }

        starRocksAssert.dropMaterializedView(mvName);
    }

    @Test
    public void testStr2DateMVRefreshRewrite_LeftJoin_FullRefresh() throws Exception {
        String mvName = "test_mv1";
        starRocksAssert.withMaterializedView("create materialized view " + mvName + " " +
                "partition by str2date(d,'%Y-%m-%d') " +
                "distributed by hash(a) " +
                "REFRESH DEFERRED MANUAL " +
                "PROPERTIES (\n" +
                "'replication_num' = '1'" +
                ") " +
                "as select  t1.a, t2.b, t3.c, t1.d " +
                " from  iceberg0.partitioned_db.part_tbl1 as t1 " +
                " left join iceberg0.partitioned_db.part_tbl2 t2 on t1.d=t2.d " +
                " left join iceberg0.partitioned_db.part_tbl3 t3 on t1.d=t3.d ;");
        Database testDb = GlobalStateMgr.getCurrentState().getDb("test");
        MaterializedView materializedView = ((MaterializedView) testDb.getTable(mvName));

        // initial create
        starRocksAssert.getCtx().executeSql("refresh materialized view " + mvName + " force with sync mode");
        List<String> partitions =
                materializedView.getPartitions().stream().map(Partition::getName).sorted()
                        .collect(Collectors.toList());
        Assert.assertEquals(Arrays.asList("p20230801_20230802", "p20230802_20230803", "p20230803_20230804"), partitions);

        {
            String query = "select  t1.a, t2.b, t3.c, t1.d " +
                    " from  iceberg0.partitioned_db.part_tbl1 as t1 " +
                    " left join iceberg0.partitioned_db.part_tbl2 t2 on t1.d=t2.d " +
                    " left join iceberg0.partitioned_db.part_tbl3 t3 on t1.d=t3.d " +
                    " where t1.d='2023-08-01';";
            String plan = getFragmentPlan(query);
            PlanTestBase.assertContains(plan, "0:OlapScanNode\n" +
                    "     TABLE: test_mv1\n" +
                    "     PREAGGREGATION: ON\n" +
                    "     partitions=1/3\n" +
                    "     rollup: test_mv1");
        }

        {
            String query = "select  t1.a, t2.b, t3.c, t1.d " +
                    " from  iceberg0.partitioned_db.part_tbl1 as t1 " +
                    " left join iceberg0.partitioned_db.part_tbl2 t2 on t1.d=t2.d " +
                    " left join iceberg0.partitioned_db.part_tbl3 t3 on t1.d=t3.d " +
                    " where t1.d>='20230801';";
            String plan = getFragmentPlan(query);
            PlanTestBase.assertContains(plan, "0:OlapScanNode\n" +
                    "     TABLE: test_mv1\n" +
                    "     PREAGGREGATION: ON\n" +
                    "     PREDICATES: 16: d >= '20230801'\n" +
                    "     partitions=3/3\n" +
                    "     rollup: test_mv1");
        }

        {
            String query = "select  t1.a, t2.b, t3.c, t1.d " +
                    " from  iceberg0.partitioned_db.part_tbl1 as t1 " +
                    " left join iceberg0.partitioned_db.part_tbl2 t2 on t1.d=t2.d " +
                    " left join iceberg0.partitioned_db.part_tbl3 t3 on t1.d=t3.d " +
                    " where t1.d < '20230802';";
            String plan = getFragmentPlan(query);
            PlanTestBase.assertContains(plan, "0:OlapScanNode\n" +
                    "     TABLE: test_mv1\n" +
                    "     PREAGGREGATION: ON\n" +
                    "     partitions=1/3\n" +
                    "     rollup: test_mv1");
        }

        starRocksAssert.dropMaterializedView(mvName);
    }

    @Test
    public void testStr2DateMVRefreshRewrite_LeftJoin_PartialRefresh() throws Exception {
        String mvName = "test_mv1";
        starRocksAssert.withMaterializedView("create materialized view " + mvName + " " +
                "partition by str2date(d,'%Y-%m-%d') " +
                "distributed by hash(a) " +
                "REFRESH DEFERRED MANUAL " +
                "PROPERTIES (\n" +
                "'replication_num' = '1'" +
                ") " +
                "as select  t1.a, t2.b, t3.c, t1.d " +
                " from  iceberg0.partitioned_db.part_tbl1 as t1 " +
                " left join iceberg0.partitioned_db.part_tbl2 t2 on t1.d=t2.d " +
                " left join iceberg0.partitioned_db.part_tbl3 t3 on t1.d=t3.d ;");
        Database testDb = GlobalStateMgr.getCurrentState().getDb("test");
        MaterializedView materializedView = ((MaterializedView) testDb.getTable(mvName));

        // initial create
        starRocksAssert.getCtx().executeSql("refresh materialized view " + mvName + " partition start('2023-08-01') " +
                "end ('2023-08-02') force with sync mode");
        List<String> partitions =
                materializedView.getPartitions().stream().map(Partition::getName).sorted()
                        .collect(Collectors.toList());
        Assert.assertEquals(Arrays.asList("p20230801_20230802"), partitions);

        {
            String query = "select  t1.a, t2.b, t3.c, t1.d " +
                    " from  iceberg0.partitioned_db.part_tbl1 as t1 " +
                    " left join iceberg0.partitioned_db.part_tbl2 t2 on t1.d=t2.d " +
                    " left join iceberg0.partitioned_db.part_tbl3 t3 on t1.d=t3.d " +
                    " where t1.d='2023-08-01';";
            String plan = getFragmentPlan(query);
            PlanTestBase.assertContains(plan, "0:OlapScanNode\n" +
                    "     TABLE: test_mv1\n" +
                    "     PREAGGREGATION: ON\n" +
                    "     partitions=1/1\n" +
                    "     rollup: test_mv1");
        }

        {
            String query = "select  t1.a, t2.b, t3.c, t1.d " +
                    " from  iceberg0.partitioned_db.part_tbl1 as t1 " +
                    " left join iceberg0.partitioned_db.part_tbl2 t2 on t1.d=t2.d " +
                    " left join iceberg0.partitioned_db.part_tbl3 t3 on t1.d=t3.d " +
                    " where t1.d>='20230801';";
            String plan = getFragmentPlan(query);
            PlanTestBase.assertNotContains(plan, "test_mv1");
        }

        {
            String query = "select  t1.a, t2.b, t3.c, t1.d " +
                    " from  iceberg0.partitioned_db.part_tbl1 as t1 " +
                    " left join iceberg0.partitioned_db.part_tbl2 t2 on t1.d=t2.d " +
                    " left join iceberg0.partitioned_db.part_tbl3 t3 on t1.d=t3.d " +
                    " where t1.d < '20230802';";
            String plan = getFragmentPlan(query);
            PlanTestBase.assertContains(plan, "UNION");
            PlanTestBase.assertContains(plan, "12:OlapScanNode\n" +
                    "     TABLE: test_mv1\n" +
                    "     PREAGGREGATION: ON\n" +
                    "     partitions=1/1\n" +
                    "     rollup: test_mv1");
        }

        {
            String query = "select  t1.a, t2.b, t3.c, t1.d " +
                    " from  iceberg0.partitioned_db.part_tbl1 as t1 " +
                    " left join iceberg0.partitioned_db.part_tbl2 t2 on t1.d=t2.d " +
                    " left join iceberg0.partitioned_db.part_tbl3 t3 on t1.d=t3.d " +
                    " where t1.d>='2023-08-01';";
            String plan = getFragmentPlan(query);
            PlanTestBase.assertContains(plan, "test_mv1");
        }

        {
            String query = "select  t1.a, t2.b, t3.c, t1.d " +
                    " from  iceberg0.partitioned_db.part_tbl1 as t1 " +
                    " left join iceberg0.partitioned_db.part_tbl2 t2 on t1.d=t2.d " +
                    " left join iceberg0.partitioned_db.part_tbl3 t3 on t1.d=t3.d " +
                    " where t1.d < '2023-08-02';";
            String plan = getFragmentPlan(query);
            PlanTestBase.assertContains(plan, "UNION");
            PlanTestBase.assertContains(plan, "12:OlapScanNode\n" +
                    "     TABLE: test_mv1\n" +
                    "     PREAGGREGATION: ON\n" +
                    "     partitions=1/1\n" +
                    "     rollup: test_mv1");
        }
        starRocksAssert.dropMaterializedView(mvName);
    }

    @Test
    public void testStr2DateMVRefreshRewrite_InnerJoin_PartitionPrune1() throws Exception {
        String mvName = "test_mv1";
        starRocksAssert.withMaterializedView("create materialized view " + mvName + " " +
                "partition by str2date(d,'%Y-%m-%d') " +
                "distributed by hash(a) " +
                "REFRESH DEFERRED MANUAL " +
                "PROPERTIES (\n" +
                "'replication_num' = '1'" +
                ") " +
                "as select  t1.a, t2.b, t3.c, t1.d " +
                " from  iceberg0.partitioned_db.part_tbl1 as t1 " +
                " inner join iceberg0.partitioned_db.part_tbl2 t2 on t1.d=t2.d " +
                " inner join iceberg0.partitioned_db.part_tbl3 t3 on t1.d=t3.d ;");
        Database testDb = GlobalStateMgr.getCurrentState().getDb("test");
        MaterializedView materializedView = ((MaterializedView) testDb.getTable(mvName));

        // initial create
        starRocksAssert.getCtx().executeSql("refresh materialized view " + mvName + " force with sync mode");
        List<String> partitions =
                materializedView.getPartitions().stream().map(Partition::getName).sorted()
                        .collect(Collectors.toList());
        Assert.assertEquals(Arrays.asList("p20230801_20230802", "p20230802_20230803", "p20230803_20230804"), partitions);

        {
            String query = "select  count(*) from " + mvName +
                    " where d='2023-08-01';";
            String plan = getFragmentPlan(query);
            PlanTestBase.assertContains(plan, "0:OlapScanNode\n" +
                    "     TABLE: test_mv1\n" +
                    "     PREAGGREGATION: ON\n" +
                    "     partitions=1/3\n" +
                    "     rollup: test_mv1");
        }

        {
            String query = "select  count(*) from " + mvName +
                    " where d in ('2023-08-01');";
            String plan = getFragmentPlan(query);
            PlanTestBase.assertContains(plan, "0:OlapScanNode\n" +
                    "     TABLE: test_mv1\n" +
                    "     PREAGGREGATION: ON\n" +
                    "     partitions=1/3");
        }

        {
            String query = "select  count(*) from " + mvName +
                    " where d in ('2023-08-01', '2023-08-02');";
            String plan = getFragmentPlan(query);
            PlanTestBase.assertContains(plan, "0:OlapScanNode\n" +
                    "     TABLE: test_mv1\n" +
                    "     PREAGGREGATION: ON\n" +
                    "     PREDICATES: 4: d IN ('2023-08-01', '2023-08-02')\n" +
                    "     partitions=2/3\n" +
                    "     rollup: test_mv1");
        }

        {
            String query = "select  count(*) from " + mvName +
                    " where d in ('2023-08-01', '2023-08-02', '2023-08-03');";
            String plan = getFragmentPlan(query);
            PlanTestBase.assertContains(plan, "0:OlapScanNode\n" +
                    "     TABLE: test_mv1\n" +
                    "     PREAGGREGATION: ON\n" +
                    "     PREDICATES: 4: d IN ('2023-08-01', '2023-08-02', '2023-08-03')\n" +
                    "     partitions=3/3");
        }

        {
            String query = "select  count(*) from " + mvName +
                    " where d not in ('2023-08-01', '2023-08-02');";
            String plan = getFragmentPlan(query);
            // TODO: no partition prune
            PlanTestBase.assertContains(plan, "0:OlapScanNode\n" +
                    "     TABLE: test_mv1\n" +
                    "     PREAGGREGATION: ON\n" +
                    "     PREDICATES: 4: d NOT IN ('2023-08-01', '2023-08-02')\n" +
                    "     partitions=3/3\n" +
                    "     rollup: test_mv1");
        }

        {
            String query = "select  count(*) from " + mvName +
                    " where d >= '2023-08-01' and d < '2023-08-02';";
            String plan = getFragmentPlan(query);
            PlanTestBase.assertContains(plan, "0:OlapScanNode\n" +
                    "     TABLE: test_mv1\n" +
                    "     PREAGGREGATION: ON\n" +
                    "     partitions=1/3\n" +
                    "     rollup: test_mv1");
        }

        {
            String query = "select  count(*) from " + mvName +
                    " where cast(d as date) >= '2023-08-01'";
            String plan = getFragmentPlan(query);
            // TODO: no partition prune
            PlanTestBase.assertContains(plan, "0:OlapScanNode\n" +
                    "     TABLE: test_mv1\n" +
                    "     PREAGGREGATION: ON\n" +
                    "     PREDICATES: CAST(4: d AS DATE) >= '2023-08-01'\n" +
                    "     partitions=3/3");
        }

        connectContext.getSessionVariable().setRangePrunerPredicateMaxLen(0);
        {
            String query = "select  count(*) from " + mvName +
                    " where d in ('2023-08-01');";
            String plan = getFragmentPlan(query);
            PlanTestBase.assertContains(plan, "0:OlapScanNode\n" +
                    "     TABLE: test_mv1\n" +
                    "     PREAGGREGATION: ON\n" +
                    "     partitions=1/3");
        }

        {
            String query = "select  count(*) from " + mvName +
                    " where d in ('2023-08-01', '2023-08-02');";
            String plan = getFragmentPlan(query);
            PlanTestBase.assertContains(plan, "0:OlapScanNode\n" +
                    "     TABLE: test_mv1\n" +
                    "     PREAGGREGATION: ON\n" +
                    "     PREDICATES: 4: d IN ('2023-08-01', '2023-08-02')\n" +
                    "     partitions=2/3\n" +
                    "     rollup: test_mv1");
        }

        {
            String query = "select  count(*) from " + mvName +
                    " where d in ('2023-08-01', '2023-08-02', '2023-08-03');";
            String plan = getFragmentPlan(query);
            PlanTestBase.assertContains(plan, "0:OlapScanNode\n" +
                    "     TABLE: test_mv1\n" +
                    "     PREAGGREGATION: ON\n" +
                    "     PREDICATES: 4: d IN ('2023-08-01', '2023-08-02', '2023-08-03')\n" +
                    "     partitions=3/3");
        }

        starRocksAssert.dropMaterializedView(mvName);
    }

    @Test
    public void testStr2DateMVRefreshRewrite_InnerJoin_PartitionPrune2() throws Exception {
        String mvName = "test_mv1";
        starRocksAssert.withMaterializedView("create materialized view " + mvName + " " +
                "partition by str2date(d,'%Y-%m-%d') " +
                "distributed by hash(a) " +
                "REFRESH DEFERRED MANUAL " +
                "PROPERTIES (\n" +
                "'replication_num' = '1'" +
                ") " +
                "as select  t1.a, t2.b, t3.c, t1.d " +
                " from  iceberg0.partitioned_db.part_tbl1 as t1 " +
                " inner join iceberg0.partitioned_db.part_tbl2 t2 on t1.d=t2.d " +
                " inner join iceberg0.partitioned_db.part_tbl3 t3 on t1.d=t3.d ;");
        Database testDb = GlobalStateMgr.getCurrentState().getDb("test");
        MaterializedView materializedView = ((MaterializedView) testDb.getTable(mvName));

        // initial create
        starRocksAssert.getCtx().executeSql("refresh materialized view " + mvName + " force with sync mode");
        List<String> partitions =
                materializedView.getPartitions().stream().map(Partition::getName).sorted()
                        .collect(Collectors.toList());
        Assert.assertEquals(Arrays.asList("p20230801_20230802", "p20230802_20230803", "p20230803_20230804"), partitions);

        {
            String query = "select  t1.a, t2.b, t3.c, t1.d \n" +
                    " from  iceberg0.partitioned_db.part_tbl1 as t1 \n" +
                    " inner join iceberg0.partitioned_db.part_tbl2 t2 on t1.d=t2.d \n" +
                    " inner join iceberg0.partitioned_db.part_tbl3 t3 on t1.d=t3.d \n" +
                    " where t1.d='2023-08-01';";
            String plan = getFragmentPlan(query);
            PlanTestBase.assertContains(plan, "0:OlapScanNode\n" +
                    "     TABLE: test_mv1\n" +
                    "     PREAGGREGATION: ON\n" +
                    "     partitions=1/3\n" +
                    "     rollup: test_mv1");
        }

        {
            String query = "select  t1.a, t2.b, t3.c, t1.d \n" +
                    " from  iceberg0.partitioned_db.part_tbl1 as t1 \n" +
                    " inner join iceberg0.partitioned_db.part_tbl2 t2 on t1.d=t2.d \n" +
                    " inner join iceberg0.partitioned_db.part_tbl3 t3 on t1.d=t3.d \n" +
                    " where t1.d in ('2023-08-01');";
            String plan = getFragmentPlan(query);
            PlanTestBase.assertContains(plan, "0:OlapScanNode\n" +
                    "     TABLE: test_mv1\n" +
                    "     PREAGGREGATION: ON\n" +
                    "     partitions=1/3");
        }

        {
            String query = "select  t1.a, t2.b, t3.c, t1.d \n" +
                    " from  iceberg0.partitioned_db.part_tbl1 as t1 \n" +
                    " inner join iceberg0.partitioned_db.part_tbl2 t2 on t1.d=t2.d \n" +
                    " inner join iceberg0.partitioned_db.part_tbl3 t3 on t1.d=t3.d \n" +
                    " where t1.d in ('2023-08-01', '2023-08-02');";
            String plan = getFragmentPlan(query);
            PlanTestBase.assertContains(plan, "0:OlapScanNode\n" +
                    "     TABLE: test_mv1\n" +
                    "     PREAGGREGATION: ON\n" +
                    "     PREDICATES: 16: d IN ('2023-08-01', '2023-08-02')\n" +
                    "     partitions=2/3");
        }

        {
            String query = "select  t1.a, t2.b, t3.c, t1.d \n" +
                    " from  iceberg0.partitioned_db.part_tbl1 as t1 \n" +
                    " inner join iceberg0.partitioned_db.part_tbl2 t2 on t1.d=t2.d \n" +
                    " inner join iceberg0.partitioned_db.part_tbl3 t3 on t1.d=t3.d \n" +
                    " where t1.d in ('2023-08-01', '2023-08-02', '2023-08-03');";
            String plan = getFragmentPlan(query);
            PlanTestBase.assertContains(plan, "0:OlapScanNode\n" +
                    "     TABLE: test_mv1\n" +
                    "     PREAGGREGATION: ON\n" +
                    "     PREDICATES: 16: d IN ('2023-08-01', '2023-08-02', '2023-08-03')\n" +
                    "     partitions=3/3");
        }

        {
            String query = "select  t1.a, t2.b, t3.c, t1.d \n" +
                    " from  iceberg0.partitioned_db.part_tbl1 as t1 \n" +
                    " inner join iceberg0.partitioned_db.part_tbl2 t2 on t1.d=t2.d \n" +
                    " inner join iceberg0.partitioned_db.part_tbl3 t3 on t1.d=t3.d \n" +
                    " where t1.d not in ('2023-08-01', '2023-08-02');";
            String plan = getFragmentPlan(query);
            // TODO: no partition prune
            PlanTestBase.assertContains(plan, "0:OlapScanNode\n" +
                    "     TABLE: test_mv1\n" +
                    "     PREAGGREGATION: ON\n" +
                    "     PREDICATES: ((16: d < '2023-08-01') OR ((16: d > '2023-08-01') AND " +
                    "(16: d < '2023-08-02'))) OR (16: d > '2023-08-02')\n" +
                    "     partitions=3/3");
        }

        {
            String query = "select  t1.a, t2.b, t3.c, t1.d \n" +
                    " from  iceberg0.partitioned_db.part_tbl1 as t1 \n" +
                    " inner join iceberg0.partitioned_db.part_tbl2 t2 on t1.d=t2.d \n" +
                    " inner join iceberg0.partitioned_db.part_tbl3 t3 on t1.d=t3.d \n" +
                    " where t1.d >= '2023-08-01' and t1.d < '2023-08-02';";
            String plan = getFragmentPlan(query);
            PlanTestBase.assertContains(plan, "0:OlapScanNode\n" +
                    "     TABLE: test_mv1\n" +
                    "     PREAGGREGATION: ON\n" +
                    "     partitions=1/3\n" +
                    "     rollup: test_mv1");
        }

        {
            String query = "select  t1.a, t2.b, t3.c, t1.d \n" +
                    " from  iceberg0.partitioned_db.part_tbl1 as t1 \n" +
                    " inner join iceberg0.partitioned_db.part_tbl2 t2 on t1.d=t2.d \n" +
                    " inner join iceberg0.partitioned_db.part_tbl3 t3 on t1.d=t3.d \n" +
                    " where cast(t1.d as date) >= '2023-08-01'";
            String plan = getFragmentPlan(query);
            // TODO: no partition prune
            PlanTestBase.assertContains(plan, "0:OlapScanNode\n" +
                    "     TABLE: test_mv1\n" +
                    "     PREAGGREGATION: ON\n" +
                    "     PREDICATES: CAST(16: d AS DATE) >= '2023-08-01'\n" +
                    "     partitions=3/3");
        }

        connectContext.getSessionVariable().setRangePrunerPredicateMaxLen(0);
        {
            String query = "select  t1.a, t2.b, t3.c, t1.d \n" +
                    " from  iceberg0.partitioned_db.part_tbl1 as t1 \n" +
                    " inner join iceberg0.partitioned_db.part_tbl2 t2 on t1.d=t2.d \n" +
                    " inner join iceberg0.partitioned_db.part_tbl3 t3 on t1.d=t3.d \n" +
                    " where t1.d in ('2023-08-01');";
            String plan = getFragmentPlan(query);
            PlanTestBase.assertContains(plan, "0:OlapScanNode\n" +
                    "     TABLE: test_mv1\n" +
                    "     PREAGGREGATION: ON\n" +
                    "     partitions=1/3");
        }

        {
            String query = "select  t1.a, t2.b, t3.c, t1.d \n" +
                    " from  iceberg0.partitioned_db.part_tbl1 as t1 \n" +
                    " inner join iceberg0.partitioned_db.part_tbl2 t2 on t1.d=t2.d \n" +
                    " inner join iceberg0.partitioned_db.part_tbl3 t3 on t1.d=t3.d \n" +
                    " where t1.d in ('2023-08-01', '2023-08-02');";
            String plan = getFragmentPlan(query);
            PlanTestBase.assertContains(plan, "0:OlapScanNode\n" +
                    "     TABLE: test_mv1\n" +
                    "     PREAGGREGATION: ON\n" +
                    "     PREDICATES: 16: d IN ('2023-08-01', '2023-08-02')\n" +
                    "     partitions=2/3");
        }

        {
            String query = "select  t1.a, t2.b, t3.c, t1.d \n" +
                    " from  iceberg0.partitioned_db.part_tbl1 as t1 \n" +
                    " inner join iceberg0.partitioned_db.part_tbl2 t2 on t1.d=t2.d \n" +
                    " inner join iceberg0.partitioned_db.part_tbl3 t3 on t1.d=t3.d \n" +
                    " where t1.d in ('2023-08-01', '2023-08-02', '2023-08-03');";
            String plan = getFragmentPlan(query);
            PlanTestBase.assertContains(plan, "0:OlapScanNode\n" +
                    "     TABLE: test_mv1\n" +
                    "     PREAGGREGATION: ON\n" +
                    "     PREDICATES: 16: d IN ('2023-08-01', '2023-08-02', '2023-08-03')\n" +
                    "     partitions=3/3");
        }

        starRocksAssert.dropMaterializedView(mvName);
    }

    @Test
    public void testStr2DateMVRefreshRewrite_InnerJoin_ExtraCompensate() throws Exception {
        String mvName = "test_mv1";
        starRocksAssert.withMaterializedView("create materialized view " + mvName + " " +
                "partition by str2date(d,'%Y-%m-%d') " +
                "distributed by hash(b) " +
                "REFRESH DEFERRED MANUAL " +
                "PROPERTIES (\n" +
                "'replication_num' = '1'" +
                ") " +
                "as select  t1.d, t2.b, t3.c, count(t1.a) " +
                " from  iceberg0.partitioned_db.part_tbl1 as t1 " +
                " inner join iceberg0.partitioned_db.part_tbl2 t2 on t1.d=t2.d " +
                " inner join iceberg0.partitioned_db.part_tbl3 t3 on t1.d=t3.d " +
                " group by t1.d, t2.b, t3.c;");
        Database testDb = GlobalStateMgr.getCurrentState().getDb("test");
        MaterializedView materializedView = ((MaterializedView) testDb.getTable(mvName));

        // initial create
        starRocksAssert.getCtx().executeSql("refresh materialized view " + mvName + " partition start('2023-08-01') " +
                "end ('2023-08-02') force with sync mode");
        List<String> partitions =
                materializedView.getPartitions().stream().map(Partition::getName).sorted()
                        .collect(Collectors.toList());
        Assert.assertEquals(Arrays.asList("p20230801_20230802"), partitions);

        {
            String query = "select  t1.d, t2.b, t3.c, count(t1.a) " +
                    " from  iceberg0.partitioned_db.part_tbl1 as t1 " +
                    " inner join iceberg0.partitioned_db.part_tbl2 t2 on t1.d=t2.d " +
                    " inner join iceberg0.partitioned_db.part_tbl3 t3 on t1.d=t3.d " +
                    " where t1.d='2023-08-01' " +
                    " group by t1.d, t2.b, t3.c;";

            String plan = getFragmentPlan(query);
            PlanTestBase.assertContains(plan, "0:OlapScanNode\n" +
                    "     TABLE: test_mv1\n" +
                    "     PREAGGREGATION: ON\n" +
                    "     partitions=1/1");
        }
        connectContext.getSessionVariable().setFollowerQueryForwardMode("force");
        {
            String query = "select  t1.d, t2.b, t3.c, count(t1.a) " +
                    " from  iceberg0.partitioned_db.part_tbl1 as t1 " +
                    " inner join iceberg0.partitioned_db.part_tbl2 t2 on t1.d=t2.d " +
                    " inner join iceberg0.partitioned_db.part_tbl3 t3 on t1.d=t3.d " +
                    " where t1.d>='2023-08-01' " +
                    " group by t1.d, t2.b, t3.c;";
            String plan = getFragmentPlan(query);
            PlanTestBase.assertContains(plan, "UNION");
            PlanTestBase.assertContains(plan, "13:OlapScanNode\n" +
                    "     TABLE: test_mv1\n" +
                    "     PREAGGREGATION: ON\n" +
                    "     partitions=1/1\n" +
                    "     rollup: test_mv1");
        }
        {
            String query = "select  t1.d, t2.b, t3.c, count(t1.a) " +
                    " from  iceberg0.partitioned_db.part_tbl1 as t1 " +
                    " inner join iceberg0.partitioned_db.part_tbl2 t2 on t1.d=t2.d " +
                    " inner join iceberg0.partitioned_db.part_tbl3 t3 on t1.d=t3.d " +
                    " where t1.d>='2023-08-01' and t2.b != '' and t2.b is not null " +
                    " group by t1.d, t2.b, t3.c;";
            String plan = getFragmentPlan(query);
            PlanTestBase.assertContains(plan, "UNION");
            PlanTestBase.assertContains(plan, "13:OlapScanNode\n" +
                    "     TABLE: test_mv1\n" +
                    "     PREAGGREGATION: ON\n" +
                    "     partitions=1/1\n" +
                    "     rollup: test_mv1");
        }

        {
            String query = "select  t1.d, t2.b, t3.c, count(t1.a) " +
                    " from  iceberg0.partitioned_db.part_tbl1 as t1 " +
                    " inner join iceberg0.partitioned_db.part_tbl2 t2 on t1.d=t2.d " +
                    " inner join iceberg0.partitioned_db.part_tbl3 t3 on t1.d=t3.d " +
                    " where t1.d>='2023-08-01' and t2.b != '' and t2.b is not null and t2.b in ('xxx') " +
                    " and t3.c in ('xxx') " +
                    " group by t1.d, t2.b, t3.c;";
            String plan = getFragmentPlan(query);
            PlanTestBase.assertContains(plan, "UNION");
            PlanTestBase.assertContains(plan, "13:OlapScanNode\n" +
                    "     TABLE: test_mv1\n" +
                    "     PREAGGREGATION: ON\n" +
                    "     partitions=1/1\n" +
                    "     rollup: test_mv1");
        }
        starRocksAssert.dropMaterializedView(mvName);
    }

    @Test
    public void testStr2DateMVRefreshRewrite_LeftJoin_OnPredicates_ExtraCompensate() throws Exception {
        String mvName = "test_mv1";
        starRocksAssert.withMaterializedView("create materialized view " + mvName + " " +
                "partition by str2date(d,'%Y-%m-%d') " +
                "distributed by hash(b) " +
                "REFRESH DEFERRED MANUAL " +
                "PROPERTIES (\n" +
                "'replication_num' = '1'" +
                ") " +
                "as select  t1.d, t2.b, t3.c, count(t1.a) " +
                " from  iceberg0.partitioned_db.part_tbl1 as t1 " +
                " left join iceberg0.partitioned_db.part_tbl2 t2 on t1.d=t2.d " +
                " left join iceberg0.partitioned_db.part_tbl3 t3 on t1.d=t3.d " +
                " group by t1.d, t2.b, t3.c;");
        Database testDb = GlobalStateMgr.getCurrentState().getDb("test");
        MaterializedView materializedView = ((MaterializedView) testDb.getTable(mvName));

        // initial create
        starRocksAssert.getCtx().executeSql("refresh materialized view " + mvName + " partition start('2023-08-01') " +
                "end ('2023-08-02') force with sync mode");
        List<String> partitions =
                materializedView.getPartitions().stream().map(Partition::getName).sorted()
                        .collect(Collectors.toList());
        Assert.assertEquals(Arrays.asList("p20230801_20230802"), partitions);

        {
            String query = "select  t1.d, t2.b, t3.c, count(t1.a) " +
                    " from  iceberg0.partitioned_db.part_tbl1 as t1 " +
                    " left join iceberg0.partitioned_db.part_tbl2 t2 on t1.d=t2.d and t2.b is not null " +
                    " left join iceberg0.partitioned_db.part_tbl3 t3 on t1.d=t3.d " +
                    " where t1.d='2023-08-01' " +
                    " group by t1.d, t2.b, t3.c;";

            String plan = getFragmentPlan(query);
            PlanTestBase.assertNotContains(plan, "UNION");
            PlanTestBase.assertContains(plan, "0:OlapScanNode\n" +
                    "     TABLE: test_mv1\n" +
                    "     PREAGGREGATION: ON\n" +
                    "     partitions=1/1");
        }
        {
            String query = "select  t1.d, t2.b, t3.c, count(t1.a) " +
                    " from  iceberg0.partitioned_db.part_tbl1 as t1 " +
                    " left join iceberg0.partitioned_db.part_tbl2 t2 on t1.d=t2.d and t2.b != '' " +
                    " left join iceberg0.partitioned_db.part_tbl3 t3 on t1.d=t3.d " +
                    " where t1.d='2023-08-01' " +
                    " group by t1.d, t2.b, t3.c;";

            String plan = getFragmentPlan(query);
            PlanTestBase.assertNotContains(plan, "UNION");
            PlanTestBase.assertNotContains(plan, "test_mv1");
        }
        {
            String query = "select  t1.d, t2.b, t3.c, count(t1.a) " +
                    " from  iceberg0.partitioned_db.part_tbl1 as t1 " +
                    " left join iceberg0.partitioned_db.part_tbl2 t2 on t1.d=t2.d and t2.b != '' " +
                    " left join iceberg0.partitioned_db.part_tbl3 t3 on t1.d=t3.d " +
                    " where t1.d>='2023-08-01' " +
                    " group by t1.d, t2.b, t3.c;";
            String plan = getFragmentPlan(query);
            PlanTestBase.assertNotContains(plan, "UNION");
            PlanTestBase.assertNotContains(plan, "test_mv1");
        }

        {
            String query = "select  t1.d, t2.b, t3.c, count(t1.a) " +
                    " from  iceberg0.partitioned_db.part_tbl1 as t1 " +
                    " left join iceberg0.partitioned_db.part_tbl2 t2 on t1.d=t2.d and t2.b != '' " +
                    " left join iceberg0.partitioned_db.part_tbl3 t3 on t1.d=t3.d " +
                    " where t1.d in ('2023-08-01', '2023-08-02') " +
                    " group by t1.d, t2.b, t3.c;";
            String plan = getFragmentPlan(query);
            PlanTestBase.assertNotContains(plan, "UNION");
            PlanTestBase.assertNotContains(plan, "test_mv1");
        }

        starRocksAssert.getCtx().executeSql("refresh materialized view " + mvName + " partition start('2023-08-02') " +
                "end ('2023-08-03') force with sync mode");
        partitions =
                materializedView.getPartitions().stream().map(Partition::getName).sorted()
                        .collect(Collectors.toList());
        Assert.assertEquals(Arrays.asList("p20230801_20230802", "p20230802_20230803"), partitions);

        {
            String query = "select  t1.d, t2.b, t3.c, count(t1.a) " +
                    " from  iceberg0.partitioned_db.part_tbl1 as t1 " +
                    " left join iceberg0.partitioned_db.part_tbl2 t2 on t1.d=t2.d and t2.b is not null " +
                    " left join iceberg0.partitioned_db.part_tbl3 t3 on t1.d=t3.d " +
                    " where t1.d>='2023-08-01' " +
                    " group by t1.d, t2.b, t3.c;";

            String plan = getFragmentPlan(query);
            PlanTestBase.assertContains(plan, "UNION");
            PlanTestBase.assertContains(plan, "3:IcebergScanNode\n" +
                    "     TABLE: part_tbl1\n" +
                    "     PREDICATES: 19: d >= '2023-08-01'");
        }

        {
            String query = "select  t1.d, t2.b, t3.c, count(t1.a) " +
                    " from  iceberg0.partitioned_db.part_tbl1 as t1 " +
                    " left join iceberg0.partitioned_db.part_tbl2 t2 on t1.d=t2.d and t2.b is not null " +
                    " left join iceberg0.partitioned_db.part_tbl3 t3 on t1.d=t3.d " +
                    " where t1.d in ('2023-08-01', '2023-08-02') " +
                    " group by t1.d, t2.b, t3.c;";

            String plan = getFragmentPlan(query);
            PlanTestBase.assertNotContains(plan, "UNION");
            PlanTestBase.assertContains(plan, "test_mv1");
        }
        starRocksAssert.dropMaterializedView(mvName);
    }

    @Test
    public void testStr2DateMVRefreshRewrite_InnerJoin_OnPredicates_ExtraCompensate() throws Exception {
        String mvName = "test_mv1";
        starRocksAssert.withMaterializedView("create materialized view " + mvName + " " +
                "partition by str2date(d,'%Y-%m-%d') " +
                "distributed by hash(b) " +
                "REFRESH DEFERRED MANUAL " +
                "PROPERTIES (\n" +
                "'replication_num' = '1'" +
                ") " +
                "as select  t1.d, t2.b, t3.c, count(t1.a) " +
                " from  iceberg0.partitioned_db.part_tbl1 as t1 " +
                " inner join iceberg0.partitioned_db.part_tbl2 t2 on t1.d=t2.d " +
                " inner join iceberg0.partitioned_db.part_tbl3 t3 on t1.d=t3.d " +
                " group by t1.d, t2.b, t3.c;");
        Database testDb = GlobalStateMgr.getCurrentState().getDb("test");
        MaterializedView materializedView = ((MaterializedView) testDb.getTable(mvName));

        // initial create
        starRocksAssert.getCtx().executeSql("refresh materialized view " + mvName + " partition start('2023-08-01') " +
                "end ('2023-08-02') force with sync mode");
        List<String> partitions =
                materializedView.getPartitions().stream().map(Partition::getName).sorted()
                        .collect(Collectors.toList());
        Assert.assertEquals(Arrays.asList("p20230801_20230802"), partitions);

        {
            String query = "select  t1.d, t2.b, t3.c, count(t1.a) " +
                    " from  iceberg0.partitioned_db.part_tbl1 as t1 " +
                    " inner join iceberg0.partitioned_db.part_tbl2 t2 on t1.d=t2.d and t2.b is not null " +
                    " inner join iceberg0.partitioned_db.part_tbl3 t3 on t1.d=t3.d " +
                    " where t1.d='2023-08-01' " +
                    " group by t1.d, t2.b, t3.c;";

            String plan = getFragmentPlan(query);
            PlanTestBase.assertNotContains(plan, "UNION");
            PlanTestBase.assertContains(plan, "0:OlapScanNode\n" +
                    "     TABLE: test_mv1\n" +
                    "     PREAGGREGATION: ON\n" +
                    "     partitions=1/1");
        }
        {
            String query = "select  t1.d, t2.b, t3.c, count(t1.a) " +
                    " from  iceberg0.partitioned_db.part_tbl1 as t1 " +
                    " inner join iceberg0.partitioned_db.part_tbl2 t2 on t1.d=t2.d and t2.b != '' " +
                    " inner join iceberg0.partitioned_db.part_tbl3 t3 on t1.d=t3.d " +
                    " where t1.d='2023-08-01' " +
                    " group by t1.d, t2.b, t3.c;";

            String plan = getFragmentPlan(query);
            PlanTestBase.assertNotContains(plan, "UNION");
            PlanTestBase.assertContains(plan, "test_mv1");
        }
        {
            String query = "select  t1.d, t2.b, t3.c, count(t1.a) " +
                    " from  iceberg0.partitioned_db.part_tbl1 as t1 " +
                    " inner join iceberg0.partitioned_db.part_tbl2 t2 on t1.d=t2.d and t2.b != '' " +
                    " inner join iceberg0.partitioned_db.part_tbl3 t3 on t1.d=t3.d " +
                    " where t1.d>='2023-08-01' " +
                    " group by t1.d, t2.b, t3.c;";
            String plan = getFragmentPlan(query);
            PlanTestBase.assertContains(plan, "UNION");
            PlanTestBase.assertContains(plan, "test_mv1");
        }

        {
            String query = "select  t1.d, t2.b, t3.c, count(t1.a) " +
                    " from  iceberg0.partitioned_db.part_tbl1 as t1 " +
                    " inner join iceberg0.partitioned_db.part_tbl2 t2 on t1.d=t2.d and t2.b != '' " +
                    " inner join iceberg0.partitioned_db.part_tbl3 t3 on t1.d=t3.d " +
                    " where t1.d in ('2023-08-01', '2023-08-02') " +
                    " group by t1.d, t2.b, t3.c;";
            String plan = getFragmentPlan(query);
            PlanTestBase.assertContains(plan, "UNION");
            PlanTestBase.assertContains(plan, "test_mv1");
        }

        starRocksAssert.dropMaterializedView(mvName);
    }

    @Test
    public void testStr2DateMVRefreshRewrite_LeftJoin_ExtraCompensate() throws Exception {
        String mvName = "test_mv1";
        starRocksAssert.withMaterializedView("create materialized view " + mvName + " " +
                "partition by str2date(d,'%Y-%m-%d') " +
                "distributed by hash(b) " +
                "REFRESH DEFERRED MANUAL " +
                "PROPERTIES (\n" +
                "'replication_num' = '1'" +
                ") " +
                "as select  t1.d, t2.b, t3.c, count(t1.a) " +
                " from  iceberg0.partitioned_db.part_tbl1 as t1 " +
                " left join iceberg0.partitioned_db.part_tbl2 t2 on t1.d=t2.d " +
                " left join iceberg0.partitioned_db.part_tbl3 t3 on t1.d=t3.d " +
                " group by t1.d, t2.b, t3.c;");
        Database testDb = GlobalStateMgr.getCurrentState().getDb("test");
        MaterializedView materializedView = ((MaterializedView) testDb.getTable(mvName));

        // initial create
        starRocksAssert.getCtx().executeSql("refresh materialized view " + mvName + " partition start('2023-08-01') " +
                "end ('2023-08-02') force with sync mode");
        List<String> partitions =
                materializedView.getPartitions().stream().map(Partition::getName).sorted()
                        .collect(Collectors.toList());
        Assert.assertEquals(Arrays.asList("p20230801_20230802"), partitions);

        {
            String query = "select  t1.d, t2.b, t3.c, count(t1.a) " +
                    " from  iceberg0.partitioned_db.part_tbl1 as t1 " +
                    " left join iceberg0.partitioned_db.part_tbl2 t2 on t1.d=t2.d " +
                    " left join iceberg0.partitioned_db.part_tbl3 t3 on t1.d=t3.d " +
                    " where t1.d='2023-08-01' " +
                    " group by t1.d, t2.b, t3.c;";

            String plan = getFragmentPlan(query);
            PlanTestBase.assertContains(plan, "0:OlapScanNode\n" +
                    "     TABLE: test_mv1\n" +
                    "     PREAGGREGATION: ON\n" +
                    "     partitions=1/1");
        }
        connectContext.getSessionVariable().setFollowerQueryForwardMode("force");
        {
            String query = "select  t1.d, t2.b, t3.c, count(t1.a) " +
                    " from  iceberg0.partitioned_db.part_tbl1 as t1 " +
                    " left join iceberg0.partitioned_db.part_tbl2 t2 on t1.d=t2.d " +
                    " left join iceberg0.partitioned_db.part_tbl3 t3 on t1.d=t3.d " +
                    " where t1.d>='2023-08-01' " +
                    " group by t1.d, t2.b, t3.c;";
            String plan = getFragmentPlan(query);
            System.out.println(plan);
            PlanTestBase.assertContains(plan, "UNION");
            PlanTestBase.assertContains(plan, "13:OlapScanNode\n" +
                    "     TABLE: test_mv1\n" +
                    "     PREAGGREGATION: ON\n" +
                    "     partitions=1/1\n" +
                    "     rollup: test_mv1");
        }
        {
            String query = "select  t1.d, t2.b, t3.c, count(t1.a) " +
                    " from  iceberg0.partitioned_db.part_tbl1 as t1 " +
                    " left join iceberg0.partitioned_db.part_tbl2 t2 on t1.d=t2.d " +
                    " left join iceberg0.partitioned_db.part_tbl3 t3 on t1.d=t3.d " +
                    " where t1.d>='2023-08-01' and t2.b != '' and t2.b is not null " +
                    " group by t1.d, t2.b, t3.c;";
            String plan = getFragmentPlan(query);
            System.out.println(plan);
            PlanTestBase.assertContains(plan, "UNION");
            PlanTestBase.assertContains(plan, "13:OlapScanNode\n" +
                    "     TABLE: test_mv1\n" +
                    "     PREAGGREGATION: ON\n" +
                    "     partitions=1/1\n" +
                    "     rollup: test_mv1");
        }

        {
            String query = "select  t1.d, t2.b, t3.c, count(t1.a) " +
                    " from  iceberg0.partitioned_db.part_tbl1 as t1 " +
                    " left join iceberg0.partitioned_db.part_tbl2 t2 on t1.d=t2.d " +
                    " left join iceberg0.partitioned_db.part_tbl3 t3 on t1.d=t3.d " +
                    " where t1.d>='2023-08-01' and t2.b != '' and t2.b is not null and t2.b in ('xxx') " +
                    " and t3.c in ('xxx') " +
                    " group by t1.d, t2.b, t3.c;";
            String plan = getFragmentPlan(query);
            System.out.println(plan);
            PlanTestBase.assertContains(plan, "UNION");
            PlanTestBase.assertContains(plan, "13:OlapScanNode\n" +
                    "     TABLE: test_mv1\n" +
                    "     PREAGGREGATION: ON\n" +
                    "     partitions=1/1\n" +
                    "     rollup: test_mv1");
        }
        starRocksAssert.dropMaterializedView(mvName);
    }

    @Test
    public void testStr2DateMVRefreshRewriteInnerJoinAggregatePartialRefreshUnion() throws Exception {
        String mvName = "test_mv1";
        starRocksAssert.withMaterializedView("create materialized view " + mvName + " " +
                "partition by str2date(d,'%Y-%m-%d') " +
                "distributed by hash(a) " +
                "REFRESH DEFERRED MANUAL " +
                "PROPERTIES (\n" +
                "'replication_num' = '1'" +
                ") " +
                "as select t1.a, t2.b, t1.d, count(t1.c)" +
                " from  iceberg0.partitioned_db.part_tbl1 as t1 " +
                " inner join iceberg0.partitioned_db.part_tbl2 t2 on t1.d=t2.d " +
                " inner join iceberg0.partitioned_db.part_tbl3 t3 on t1.d=t3.d " +
                " group by t1.a, t2.b, t1.d;");
        Database testDb = GlobalStateMgr.getCurrentState().getDb("test");
        MaterializedView materializedView = ((MaterializedView) testDb.getTable(mvName));

        // initial create
        starRocksAssert.getCtx().executeSql("refresh materialized view " + mvName + " partition start('2023-08-01') " +
                "end ('2023-08-02') force with sync mode");
        List<String> partitions =
                materializedView.getPartitions().stream().map(Partition::getName).sorted()
                        .collect(Collectors.toList());
        Assert.assertEquals(Arrays.asList("p20230801_20230802"), partitions);

        {
            String query = "select t1.a, t2.b, t1.d, count(t1.c)\n" +
                    " from  iceberg0.partitioned_db.part_tbl1 as t1 \n" +
                    " inner join iceberg0.partitioned_db.part_tbl2 t2 on t1.d=t2.d \n" +
                    " inner join iceberg0.partitioned_db.part_tbl3 t3 on t1.d=t3.d \n" +
                    " where t1.d in ('2023-08-01')\n" +
                    " group by t1.a, t2.b, t1.d;";
            String plan = getFragmentPlan(query);
            PlanTestBase.assertContains(plan, "0:OlapScanNode\n" +
                    "     TABLE: test_mv1\n" +
                    "     PREAGGREGATION: ON\n" +
                    "     partitions=1/1\n" +
                    "     rollup: test_mv1");
        }

        {
            String query = "select t1.a, t2.b, t1.d, count(t1.c)\n" +
                    " from  iceberg0.partitioned_db.part_tbl1 as t1 \n" +
                    " inner join iceberg0.partitioned_db.part_tbl2 t2 on t1.d=t2.d \n" +
                    " inner join iceberg0.partitioned_db.part_tbl3 t3 on t1.d=t3.d \n" +
                    " where t1.d ='2023-08-01'\n" +
                    " group by t1.a, t2.b, t1.d;";
            String plan = getFragmentPlan(query);
            PlanTestBase.assertContains(plan, "0:OlapScanNode\n" +
                    "     TABLE: test_mv1\n" +
                    "     PREAGGREGATION: ON\n" +
                    "     partitions=1/1\n" +
                    "     rollup: test_mv1");
        }

        {
            String query = "select t1.a, t2.b, t1.d, count(t1.c)\n" +
                    " from  iceberg0.partitioned_db.part_tbl1 as t1 \n" +
                    " inner join iceberg0.partitioned_db.part_tbl2 t2 on t1.d=t2.d \n" +
                    " inner join iceberg0.partitioned_db.part_tbl3 t3 on t1.d=t3.d \n" +
                    " where t1.d in ('2023-08-01', '2023-08-02')\n" +
                    " group by t1.a, t2.b, t1.d;";
            String plan = getFragmentPlan(query);
            PlanTestBase.assertContains(plan, "UNION");
            PlanTestBase.assertContains(plan, "14:OlapScanNode\n" +
                    "     TABLE: test_mv1\n" +
                    "     PREAGGREGATION: ON\n" +
                    "     partitions=1/1\n" +
                    "     rollup: test_mv1");
        }

        {
            String query = "select t1.a, t2.b, t1.d, count(t1.c)\n" +
                    " from  iceberg0.partitioned_db.part_tbl1 as t1 \n" +
                    " inner join iceberg0.partitioned_db.part_tbl2 t2 on t1.d=t2.d \n" +
                    " inner join iceberg0.partitioned_db.part_tbl3 t3 on t1.d=t3.d \n" +
                    " where t1.d in ('2023-08-01', '2023-08-02', '2023-08-03')\n" +
                    " group by t1.a, t2.b, t1.d;";
            String plan = getFragmentPlan(query);
            PlanTestBase.assertContains(plan, "UNION");
            PlanTestBase.assertContains(plan, "14:OlapScanNode\n" +
                    "     TABLE: test_mv1\n" +
                    "     PREAGGREGATION: ON\n" +
                    "     partitions=1/1\n" +
                    "     rollup: test_mv1");
        }

        {
            String query = "select t1.a, t2.b, t1.d, count(t1.c)\n" +
                    " from  iceberg0.partitioned_db.part_tbl1 as t1 \n" +
                    " inner join iceberg0.partitioned_db.part_tbl2 t2 on t1.d=t2.d \n" +
                    " inner join iceberg0.partitioned_db.part_tbl3 t3 on t1.d=t3.d \n" +
                    " where t1.d >= '2023-08-01' \n" +
                    " group by t1.a, t2.b, t1.d;";
            String plan = getFragmentPlan(query);
            PlanTestBase.assertContains(plan, "UNION");
            PlanTestBase.assertContains(plan, "14:OlapScanNode\n" +
                    "     TABLE: test_mv1\n" +
                    "     PREAGGREGATION: ON\n" +
                    "     partitions=1/1\n" +
                    "     rollup: test_mv1");
        }

        {
            String query = "select t1.a, t2.b, t1.d, count(t1.c)\n" +
                    " from  iceberg0.partitioned_db.part_tbl1 as t1 \n" +
                    " inner join iceberg0.partitioned_db.part_tbl2 t2 on t1.d=t2.d \n" +
                    " inner join iceberg0.partitioned_db.part_tbl3 t3 on t1.d=t3.d \n" +
                    " where t1.d >= '2023-08-01' and t1.d <= '2023-08-02' \n" +
                    " group by t1.a, t2.b, t1.d;";
            String plan = getFragmentPlan(query);
            PlanTestBase.assertContains(plan, "UNION");
            PlanTestBase.assertContains(plan, "14:OlapScanNode\n" +
                    "     TABLE: test_mv1\n" +
                    "     PREAGGREGATION: ON\n" +
                    "     partitions=1/1\n" +
                    "     rollup: test_mv1");
        }

        {
            String query = "select t1.a, t2.b, t1.d, count(t1.c)\n" +
                    " from  iceberg0.partitioned_db.part_tbl1 as t1 \n" +
                    " inner join iceberg0.partitioned_db.part_tbl2 t2 on t1.d=t2.d \n" +
                    " inner join iceberg0.partitioned_db.part_tbl3 t3 on t1.d=t3.d \n" +
                    " where t1.d = '2023-08-01' or t1.d = '2023-08-02' \n" +
                    " group by t1.a, t2.b, t1.d;";
            String plan = getFragmentPlan(query);
            PlanTestBase.assertContains(plan, "UNION");
            PlanTestBase.assertContains(plan, "14:OlapScanNode\n" +
                    "     TABLE: test_mv1\n" +
                    "     PREAGGREGATION: ON\n" +
                    "     partitions=1/1\n" +
                    "     rollup: test_mv1");
        }

        {
            String query = "select t1.a, t2.b, t1.d, count(t1.c)\n" +
                    " from  iceberg0.partitioned_db.part_tbl1 as t1 \n" +
                    " inner join iceberg0.partitioned_db.part_tbl2 t2 on t1.d=t2.d \n" +
                    " inner join iceberg0.partitioned_db.part_tbl3 t3 on t1.d=t3.d \n" +
                    " where t1.d in ('2023-08-01', '2023-08-02', '2023-08-03')\n" +
                    " group by t1.a, t2.b, t1.d;";
            connectContext.getSessionVariable().setRangePrunerPredicateMaxLen(0);
            String plan = getFragmentPlan(query);
            PlanTestBase.assertContains(plan, "UNION");
            PlanTestBase.assertContains(plan, "14:OlapScanNode\n" +
                    "     TABLE: test_mv1\n" +
                    "     PREAGGREGATION: ON\n" +
                    "     partitions=1/1\n" +
                    "     rollup: test_mv1");
        }
        starRocksAssert.dropMaterializedView(mvName);
    }

    @Test
    public void testStr2DateMVRefreshRewriteLeftJoinAggregatePartialRefreshUnion() throws Exception {
        String mvName = "test_mv1";
        starRocksAssert.withMaterializedView("create materialized view " + mvName + " " +
                "partition by str2date(d,'%Y-%m-%d') " +
                "distributed by hash(a) " +
                "REFRESH DEFERRED MANUAL " +
                "PROPERTIES (\n" +
                "'replication_num' = '1'" +
                ") " +
                "as select t1.a, t2.b, t1.d, count(t1.c)" +
                " from  iceberg0.partitioned_db.part_tbl1 as t1 " +
                " left join iceberg0.partitioned_db.part_tbl2 t2 on t1.d=t2.d " +
                " left join iceberg0.partitioned_db.part_tbl3 t3 on t1.d=t3.d " +
                " group by t1.a, t2.b, t1.d;");
        Database testDb = GlobalStateMgr.getCurrentState().getDb("test");
        MaterializedView materializedView = ((MaterializedView) testDb.getTable(mvName));

        // initial create
        starRocksAssert.getCtx().executeSql("refresh materialized view " + mvName + " partition start('2023-08-01') " +
                "end ('2023-08-02') force with sync mode");
        List<String> partitions =
                materializedView.getPartitions().stream().map(Partition::getName).sorted()
                        .collect(Collectors.toList());
        Assert.assertEquals(Arrays.asList("p20230801_20230802"), partitions);

        {
            String query = "select t1.a, t2.b, t1.d, count(t1.c)\n" +
                    " from  iceberg0.partitioned_db.part_tbl1 as t1 \n" +
                    " left join iceberg0.partitioned_db.part_tbl2 t2 on t1.d=t2.d \n" +
                    " left join iceberg0.partitioned_db.part_tbl3 t3 on t1.d=t3.d \n" +
                    " where t1.d in ('2023-08-01')\n" +
                    " group by t1.a, t2.b, t1.d;";
            String plan = getFragmentPlan(query);
            PlanTestBase.assertContains(plan, "0:OlapScanNode\n" +
                    "     TABLE: test_mv1\n" +
                    "     PREAGGREGATION: ON\n" +
                    "     partitions=1/1\n" +
                    "     rollup: test_mv1");
        }

        {
            String query = "select t1.a, t2.b, t1.d, count(t1.c)\n" +
                    " from  iceberg0.partitioned_db.part_tbl1 as t1 \n" +
                    " left join iceberg0.partitioned_db.part_tbl2 t2 on t1.d=t2.d \n" +
                    " left join iceberg0.partitioned_db.part_tbl3 t3 on t1.d=t3.d \n" +
                    " where t1.d ='2023-08-01'\n" +
                    " group by t1.a, t2.b, t1.d;";
            String plan = getFragmentPlan(query);
            PlanTestBase.assertContains(plan, "0:OlapScanNode\n" +
                    "     TABLE: test_mv1\n" +
                    "     PREAGGREGATION: ON\n" +
                    "     partitions=1/1\n" +
                    "     rollup: test_mv1");
        }

        {
            String query = "select t1.a, t2.b, t1.d, count(t1.c)\n" +
                    " from  iceberg0.partitioned_db.part_tbl1 as t1 \n" +
                    " left join iceberg0.partitioned_db.part_tbl2 t2 on t1.d=t2.d \n" +
                    " left join iceberg0.partitioned_db.part_tbl3 t3 on t1.d=t3.d \n" +
                    " where t1.d in ('2023-08-01', '2023-08-02')\n" +
                    " group by t1.a, t2.b, t1.d;";
            String plan = getFragmentPlan(query);
            PlanTestBase.assertContains(plan, "UNION");
            PlanTestBase.assertContains(plan, "14:OlapScanNode\n" +
                    "     TABLE: test_mv1\n" +
                    "     PREAGGREGATION: ON\n" +
                    "     partitions=1/1\n" +
                    "     rollup: test_mv1");
        }

        {
            String query = "select t1.a, t2.b, t1.d, count(t1.c)\n" +
                    " from  iceberg0.partitioned_db.part_tbl1 as t1 \n" +
                    " left join iceberg0.partitioned_db.part_tbl2 t2 on t1.d=t2.d \n" +
                    " left join iceberg0.partitioned_db.part_tbl3 t3 on t1.d=t3.d \n" +
                    " where t1.d in ('2023-08-01', '2023-08-02', '2023-08-03')\n" +
                    " group by t1.a, t2.b, t1.d;";
            String plan = getFragmentPlan(query);
            PlanTestBase.assertContains(plan, "UNION");
            PlanTestBase.assertContains(plan, "14:OlapScanNode\n" +
                    "     TABLE: test_mv1\n" +
                    "     PREAGGREGATION: ON\n" +
                    "     partitions=1/1\n" +
                    "     rollup: test_mv1");
        }

        {
            String query = "select t1.a, t2.b, t1.d, count(t1.c)\n" +
                    " from  iceberg0.partitioned_db.part_tbl1 as t1 \n" +
                    " left join iceberg0.partitioned_db.part_tbl2 t2 on t1.d=t2.d \n" +
                    " left join iceberg0.partitioned_db.part_tbl3 t3 on t1.d=t3.d \n" +
                    " where t1.d >= '2023-08-01' \n" +
                    " group by t1.a, t2.b, t1.d;";
            String plan = getFragmentPlan(query);
            PlanTestBase.assertContains(plan, "UNION");
            PlanTestBase.assertContains(plan, "14:OlapScanNode\n" +
                    "     TABLE: test_mv1\n" +
                    "     PREAGGREGATION: ON\n" +
                    "     partitions=1/1\n" +
                    "     rollup: test_mv1");
        }

        {
            String query = "select t1.a, t2.b, t1.d, count(t1.c)\n" +
                    " from  iceberg0.partitioned_db.part_tbl1 as t1 \n" +
                    " left join iceberg0.partitioned_db.part_tbl2 t2 on t1.d=t2.d \n" +
                    " left join iceberg0.partitioned_db.part_tbl3 t3 on t1.d=t3.d \n" +
                    " where t1.d >= '2023-08-01' and t1.d <= '2023-08-02' \n" +
                    " group by t1.a, t2.b, t1.d;";
            String plan = getFragmentPlan(query);
            PlanTestBase.assertContains(plan, "UNION");
            PlanTestBase.assertContains(plan, "14:OlapScanNode\n" +
                    "     TABLE: test_mv1\n" +
                    "     PREAGGREGATION: ON\n" +
                    "     partitions=1/1\n" +
                    "     rollup: test_mv1");
        }

        {
            String query = "select t1.a, t2.b, t1.d, count(t1.c)\n" +
                    " from  iceberg0.partitioned_db.part_tbl1 as t1 \n" +
                    " left join iceberg0.partitioned_db.part_tbl2 t2 on t1.d=t2.d \n" +
                    " left join iceberg0.partitioned_db.part_tbl3 t3 on t1.d=t3.d \n" +
                    " where t1.d = '2023-08-01' or t1.d = '2023-08-02' \n" +
                    " group by t1.a, t2.b, t1.d;";
            String plan = getFragmentPlan(query);
            PlanTestBase.assertContains(plan, "UNION");
            PlanTestBase.assertContains(plan, "14:OlapScanNode\n" +
                    "     TABLE: test_mv1\n" +
                    "     PREAGGREGATION: ON\n" +
                    "     partitions=1/1\n" +
                    "     rollup: test_mv1");
        }

        {
            String query = "select t1.a, t2.b, t1.d, count(t1.c)\n" +
                    " from  iceberg0.partitioned_db.part_tbl1 as t1 \n" +
                    " left join iceberg0.partitioned_db.part_tbl2 t2 on t1.d=t2.d \n" +
                    " left join iceberg0.partitioned_db.part_tbl3 t3 on t1.d=t3.d \n" +
                    " where t1.d in ('2023-08-01', '2023-08-02', '2023-08-03')\n" +
                    " group by t1.a, t2.b, t1.d;";
            connectContext.getSessionVariable().setRangePrunerPredicateMaxLen(0);
            String plan = getFragmentPlan(query);
            PlanTestBase.assertContains(plan, "UNION");
            PlanTestBase.assertContains(plan, "14:OlapScanNode\n" +
                    "     TABLE: test_mv1\n" +
                    "     PREAGGREGATION: ON\n" +
                    "     partitions=1/1\n" +
                    "     rollup: test_mv1");
        }
        starRocksAssert.dropMaterializedView(mvName);
    }

    @Test
    public void testStr2DateMVRefreshRewriteLeftJoinBitmapAggregatePartialRefreshUnion() throws Exception {
        String mvName = "test_mv1";
        starRocksAssert.withMaterializedView("create materialized view " + mvName + " " +
                "partition by str2date(d,'%Y-%m-%d') " +
                "distributed by hash(a) " +
                "REFRESH DEFERRED MANUAL " +
                "PROPERTIES (\n" +
                "'replication_num' = '1'" +
                ") " +
                "as select t1.a, t2.b, t1.d, bitmap_union(to_bitmap(t1.c))" +
                " from  iceberg0.partitioned_db.part_tbl1 as t1 " +
                " left join iceberg0.partitioned_db.part_tbl2 t2 on t1.d=t2.d " +
                " left join iceberg0.partitioned_db.part_tbl3 t3 on t1.d=t3.d " +
                " group by t1.a, t2.b, t1.d;");
        Database testDb = GlobalStateMgr.getCurrentState().getDb("test");
        MaterializedView materializedView = ((MaterializedView) testDb.getTable(mvName));

        // initial create
        starRocksAssert.getCtx().executeSql("refresh materialized view " + mvName + " partition start('2023-08-01') " +
                "end ('2023-08-02') force with sync mode");
        List<String> partitions =
                materializedView.getPartitions().stream().map(Partition::getName).sorted()
                        .collect(Collectors.toList());
        Assert.assertEquals(Arrays.asList("p20230801_20230802"), partitions);

        {
            String query = "select t1.a, t2.b, t1.d, count(distinct t1.c)\n" +
                    " from  iceberg0.partitioned_db.part_tbl1 as t1 \n" +
                    " left join iceberg0.partitioned_db.part_tbl2 t2 on t1.d=t2.d \n" +
                    " left join iceberg0.partitioned_db.part_tbl3 t3 on t1.d=t3.d \n" +
                    " where t1.d in ('2023-08-01')\n" +
                    " group by t1.a, t2.b, t1.d;";
            String plan = getFragmentPlan(query);
            PlanTestBase.assertNotContains(plan, "test_mv1");
        }

        connectContext.getSessionVariable().setMaterializedViewRewriteMode("force");
        {
            String query = "select t1.a, t2.b, t1.d, count(distinct t1.c)\n" +
                    " from  iceberg0.partitioned_db.part_tbl1 as t1 \n" +
                    " left join iceberg0.partitioned_db.part_tbl2 t2 on t1.d=t2.d \n" +
                    " left join iceberg0.partitioned_db.part_tbl3 t3 on t1.d=t3.d \n" +
                    " group by t1.a, t2.b, t1.d;";
            String plan = getFragmentPlan(query);
            PlanTestBase.assertContains(plan, "UNION");

            PlanTestBase.assertContains(plan, "OlapScanNode\n" +
                    "     TABLE: test_mv1\n" +
                    "     PREAGGREGATION: ON\n" +
                    "     partitions=1/1");
            PlanTestBase.assertContains(plan, "5:IcebergScanNode\n" +
                    "     TABLE: part_tbl3\n" +
                    "     PREDICATES: 27: d != '2023-08-01'");
        }

        {
            String query = "select t1.a, t2.b, t1.d, count(distinct t1.c)\n" +
                    " from  iceberg0.partitioned_db.part_tbl1 as t1 \n" +
                    " left join iceberg0.partitioned_db.part_tbl2 t2 on t1.d=t2.d \n" +
                    " left join iceberg0.partitioned_db.part_tbl3 t3 on t1.d=t3.d \n" +
                    " where t1.d ='2023-08-01'\n" +
                    " group by t1.a, t2.b, t1.d;";
            String plan = getFragmentPlan(query);
            PlanTestBase.assertContains(plan, "0:OlapScanNode\n" +
                    "     TABLE: test_mv1\n" +
                    "     PREAGGREGATION: ON\n" +
                    "     partitions=1/1\n" +
                    "     rollup: test_mv1");
        }

        {
            String query = "select t1.a, t2.b, t1.d, count(distinct t1.c)\n" +
                    " from  iceberg0.partitioned_db.part_tbl1 as t1 \n" +
                    " left join iceberg0.partitioned_db.part_tbl2 t2 on t1.d=t2.d \n" +
                    " left join iceberg0.partitioned_db.part_tbl3 t3 on t1.d=t3.d \n" +
                    " where t1.d in ('2023-08-01', '2023-08-02')\n" +
                    " group by t1.a, t2.b, t1.d;";
            String plan = getFragmentPlan(query);
            PlanTestBase.assertContains(plan, "UNION");
            PlanTestBase.assertContains(plan, "14:OlapScanNode\n" +
                    "     TABLE: test_mv1\n" +
                    "     PREAGGREGATION: ON\n" +
                    "     partitions=1/1\n" +
                    "     rollup: test_mv1");
        }

        {
            String query = "select t1.a, t2.b, t1.d, count(distinct t1.c)\n" +
                    " from  iceberg0.partitioned_db.part_tbl1 as t1 \n" +
                    " left join iceberg0.partitioned_db.part_tbl2 t2 on t1.d=t2.d \n" +
                    " left join iceberg0.partitioned_db.part_tbl3 t3 on t1.d=t3.d \n" +
                    " where t1.d in ('2023-08-01', '2023-08-02', '2023-08-03')\n" +
                    " group by t1.a, t2.b, t1.d;";
            String plan = getFragmentPlan(query);
            PlanTestBase.assertContains(plan, "UNION");
            PlanTestBase.assertContains(plan, "14:OlapScanNode\n" +
                    "     TABLE: test_mv1\n" +
                    "     PREAGGREGATION: ON\n" +
                    "     partitions=1/1\n" +
                    "     rollup: test_mv1");
        }

        {
            String query = "select t1.a, t2.b, t1.d, count(distinct t1.c)\n" +
                    " from  iceberg0.partitioned_db.part_tbl1 as t1 \n" +
                    " left join iceberg0.partitioned_db.part_tbl2 t2 on t1.d=t2.d \n" +
                    " left join iceberg0.partitioned_db.part_tbl3 t3 on t1.d=t3.d \n" +
                    " where t1.d >= '2023-08-01' \n" +
                    " group by t1.a, t2.b, t1.d;";
            String plan = getFragmentPlan(query);
            PlanTestBase.assertContains(plan, "UNION");
            PlanTestBase.assertContains(plan, "14:OlapScanNode\n" +
                    "     TABLE: test_mv1\n" +
                    "     PREAGGREGATION: ON\n" +
                    "     partitions=1/1\n" +
                    "     rollup: test_mv1");
        }

        {
            String query = "select t1.a, t2.b, t1.d, count(distinct t1.c)\n" +
                    " from  iceberg0.partitioned_db.part_tbl1 as t1 \n" +
                    " left join iceberg0.partitioned_db.part_tbl2 t2 on t1.d=t2.d \n" +
                    " left join iceberg0.partitioned_db.part_tbl3 t3 on t1.d=t3.d \n" +
                    " where t1.d >= '2023-08-01' and t1.d <= '2023-08-02' \n" +
                    " group by t1.a, t2.b, t1.d;";
            String plan = getFragmentPlan(query);
            PlanTestBase.assertContains(plan, "UNION");
            PlanTestBase.assertContains(plan, "14:OlapScanNode\n" +
                    "     TABLE: test_mv1\n" +
                    "     PREAGGREGATION: ON\n" +
                    "     partitions=1/1\n" +
                    "     rollup: test_mv1");
        }

        {
            String query = "select t1.a, t2.b, t1.d, count(distinct t1.c)\n" +
                    " from  iceberg0.partitioned_db.part_tbl1 as t1 \n" +
                    " left join iceberg0.partitioned_db.part_tbl2 t2 on t1.d=t2.d \n" +
                    " left join iceberg0.partitioned_db.part_tbl3 t3 on t1.d=t3.d \n" +
                    " where t1.d = '2023-08-01' or t1.d = '2023-08-02' \n" +
                    " group by t1.a, t2.b, t1.d;";
            String plan = getFragmentPlan(query);
            PlanTestBase.assertContains(plan, "UNION");
            PlanTestBase.assertContains(plan, "14:OlapScanNode\n" +
                    "     TABLE: test_mv1\n" +
                    "     PREAGGREGATION: ON\n" +
                    "     partitions=1/1\n" +
                    "     rollup: test_mv1");
        }

        {
            String query = "select t1.a, t2.b, t1.d, count(distinct t1.c)\n" +
                    " from  iceberg0.partitioned_db.part_tbl1 as t1 \n" +
                    " left join iceberg0.partitioned_db.part_tbl2 t2 on t1.d=t2.d \n" +
                    " left join iceberg0.partitioned_db.part_tbl3 t3 on t1.d=t3.d \n" +
                    " where t1.d in ('2023-08-01', '2023-08-02', '2023-08-03')\n" +
                    " group by t1.a, t2.b, t1.d;";
            connectContext.getSessionVariable().setRangePrunerPredicateMaxLen(0);
            String plan = getFragmentPlan(query);
            PlanTestBase.assertContains(plan, "UNION");
            PlanTestBase.assertContains(plan, "14:OlapScanNode\n" +
                    "     TABLE: test_mv1\n" +
                    "     PREAGGREGATION: ON\n" +
                    "     partitions=1/1\n" +
                    "     rollup: test_mv1");
        }
        connectContext.getSessionVariable().setMaterializedViewRewriteMode("default");
        starRocksAssert.dropMaterializedView(mvName);
    }

    @Test
    public void testStr2DateMVRefreshRewriteWithBitmapHash_LeftJoin() throws Exception {
        String mvName = "test_mv1";
        starRocksAssert.withMaterializedView("create materialized view " + mvName + " " +
                "partition by str2date(d,'%Y-%m-%d') " +
                "distributed by hash(b) " +
                "REFRESH DEFERRED MANUAL " +
                "PROPERTIES (\n" +
                "'replication_num' = '1'" +
                ") " +
                "as select  t1.d, t2.b, t3.c, bitmap_union(bitmap_hash(t1.a)) " +
                " from  iceberg0.partitioned_db.part_tbl1 as t1 " +
                " left join iceberg0.partitioned_db.part_tbl2 t2 on t1.d=t2.d " +
                " left join iceberg0.partitioned_db.part_tbl3 t3 on t1.d=t3.d " +
                " group by t1.d, t2.b, t3.c;");
        Database testDb = GlobalStateMgr.getCurrentState().getDb("test");
        MaterializedView materializedView = ((MaterializedView) testDb.getTable(mvName));

        // initial create
        starRocksAssert.getCtx().executeSql("refresh materialized view " + mvName + " partition start('2023-08-01') " +
                "end ('2023-08-02') force with sync mode");
        List<String> partitions =
                materializedView.getPartitions().stream().map(Partition::getName).sorted()
                        .collect(Collectors.toList());
        Assert.assertEquals(Arrays.asList("p20230801_20230802"), partitions);

        {
            String query = "select  t1.d, t2.b, t3.c, bitmap_union(bitmap_hash(t1.a)) " +
                    " from  iceberg0.partitioned_db.part_tbl1 as t1 " +
                    " left join iceberg0.partitioned_db.part_tbl2 t2 on t1.d=t2.d " +
                    " left join iceberg0.partitioned_db.part_tbl3 t3 on t1.d=t3.d " +
                    " where t1.d='2023-08-01' " +
                    " group by t1.d, t2.b, t3.c;";

            String plan = getFragmentPlan(query);
            PlanTestBase.assertContains(plan, "0:OlapScanNode\n" +
                    "     TABLE: test_mv1\n" +
                    "     PREAGGREGATION: ON\n" +
                    "     partitions=1/1");
        }

        {
            String query = "select  t1.d, t2.b, t3.c, bitmap_union_count(bitmap_hash(t1.a)) " +
                    " from  iceberg0.partitioned_db.part_tbl1 as t1 " +
                    " left join iceberg0.partitioned_db.part_tbl2 t2 on t1.d=t2.d " +
                    " left join iceberg0.partitioned_db.part_tbl3 t3 on t1.d=t3.d " +
                    " where t1.d='2023-08-01' " +
                    " group by t1.d, t2.b, t3.c;";

            String plan = getFragmentPlan(query);
            PlanTestBase.assertContains(plan, "0:OlapScanNode\n" +
                    "     TABLE: test_mv1\n" +
                    "     PREAGGREGATION: ON\n" +
                    "     partitions=1/1");
        }

        connectContext.getSessionVariable().setMaterializedViewRewriteMode("force");
        {
            String query = "select t1.d, t2.b, t3.c, count(distinct t1.a) " +
                    " from  iceberg0.partitioned_db.part_tbl1 as t1 " +
                    " left join iceberg0.partitioned_db.part_tbl2 t2 on t1.d=t2.d " +
                    " left join iceberg0.partitioned_db.part_tbl3 t3 on t1.d=t3.d " +
                    " where t1.d='2023-08-01' " +
                    " group by t1.d, t2.b, t3.c;";

            String plan = getFragmentPlan(query);
            PlanTestBase.assertContains(plan, "0:OlapScanNode\n" +
                    "     TABLE: test_mv1\n" +
                    "     PREAGGREGATION: ON\n" +
                    "     partitions=1/1");
        }

        {
            String query = "select  t1.d, t2.b, t3.c, count(distinct t1.a) " +
                    " from  iceberg0.partitioned_db.part_tbl1 as t1 " +
                    " left join iceberg0.partitioned_db.part_tbl2 t2 on t1.d=t2.d " +
                    " left join iceberg0.partitioned_db.part_tbl3 t3 on t1.d=t3.d " +
                    " where t1.d>='2023-08-01' " +
                    " group by t1.d, t2.b, t3.c;";

            String plan = getFragmentPlan(query);
            PlanTestBase.assertContains(plan, "UNION");
            PlanTestBase.assertContains(plan, "13:OlapScanNode\n" +
                    "     TABLE: test_mv1\n" +
                    "     PREAGGREGATION: ON\n" +
                    "     partitions=1/1");
        }

        {
            String query = "select  t1.d, count(distinct t1.a) " +
                    " from  iceberg0.partitioned_db.part_tbl1 as t1 " +
                    " left join iceberg0.partitioned_db.part_tbl2 t2 on t1.d=t2.d " +
                    " left join iceberg0.partitioned_db.part_tbl3 t3 on t1.d=t3.d " +
                    " where t1.d>='2023-08-01' " +
                    " group by t1.d;";

            String plan = getFragmentPlan(query);
            PlanTestBase.assertContains(plan, "UNION");
            PlanTestBase.assertContains(plan, "15:OlapScanNode\n" +
                    "     TABLE: test_mv1\n" +
                    "     PREAGGREGATION: ON\n" +
                    "     partitions=1/1");
        }
        starRocksAssert.dropMaterializedView(mvName);
        connectContext.getSessionVariable().setMaterializedViewRewriteMode("default");
    }
    @Test
    public void testStr2DateMVRefreshRewriteWithBitmapHash_InnerJoin() throws Exception {
        String mvName = "test_mv1";
        starRocksAssert.withMaterializedView("create materialized view " + mvName + " " +
                "partition by str2date(d,'%Y-%m-%d') " +
                "distributed by hash(b) " +
                "REFRESH DEFERRED MANUAL " +
                "PROPERTIES (\n" +
                "'replication_num' = '1'" +
                ") " +
                "as select  t1.d, t2.b, t3.c, bitmap_union(bitmap_hash(t1.a)) " +
                " from  iceberg0.partitioned_db.part_tbl1 as t1 " +
                " inner join iceberg0.partitioned_db.part_tbl2 t2 on t1.d=t2.d " +
                " inner join iceberg0.partitioned_db.part_tbl3 t3 on t1.d=t3.d " +
                " group by t1.d, t2.b, t3.c;");
        Database testDb = GlobalStateMgr.getCurrentState().getDb("test");
        MaterializedView materializedView = ((MaterializedView) testDb.getTable(mvName));

        // initial create
        starRocksAssert.getCtx().executeSql("refresh materialized view " + mvName + " partition start('2023-08-01') " +
                "end ('2023-08-02') force with sync mode");
        List<String> partitions =
                materializedView.getPartitions().stream().map(Partition::getName).sorted()
                        .collect(Collectors.toList());
        Assert.assertEquals(Arrays.asList("p20230801_20230802"), partitions);

        {
            String query = "select  t1.d, t2.b, t3.c, bitmap_union(bitmap_hash(t1.a)) " +
                    " from  iceberg0.partitioned_db.part_tbl1 as t1 " +
                    " inner join iceberg0.partitioned_db.part_tbl2 t2 on t1.d=t2.d " +
                    " inner join iceberg0.partitioned_db.part_tbl3 t3 on t1.d=t3.d " +
                    " where t1.d='2023-08-01' " +
                    " group by t1.d, t2.b, t3.c;";

            String plan = getFragmentPlan(query);
            PlanTestBase.assertContains(plan, "0:OlapScanNode\n" +
                    "     TABLE: test_mv1\n" +
                    "     PREAGGREGATION: ON\n" +
                    "     partitions=1/1");
        }

        {
            String query = "select  t1.d, t2.b, t3.c, bitmap_union_count(bitmap_hash(t1.a)) " +
                    " from  iceberg0.partitioned_db.part_tbl1 as t1 " +
                    " inner join iceberg0.partitioned_db.part_tbl2 t2 on t1.d=t2.d " +
                    " inner join iceberg0.partitioned_db.part_tbl3 t3 on t1.d=t3.d " +
                    " where t1.d='2023-08-01' " +
                    " group by t1.d, t2.b, t3.c;";

            String plan = getFragmentPlan(query);
            PlanTestBase.assertContains(plan, "0:OlapScanNode\n" +
                    "     TABLE: test_mv1\n" +
                    "     PREAGGREGATION: ON\n" +
                    "     partitions=1/1");
        }

        connectContext.getSessionVariable().setMaterializedViewRewriteMode("force");
        {
            String query = "select t1.d, t2.b, t3.c, count(distinct t1.a) " +
                    " from  iceberg0.partitioned_db.part_tbl1 as t1 " +
                    " inner join iceberg0.partitioned_db.part_tbl2 t2 on t1.d=t2.d " +
                    " inner join iceberg0.partitioned_db.part_tbl3 t3 on t1.d=t3.d " +
                    " where t1.d='2023-08-01' " +
                    " group by t1.d, t2.b, t3.c;";

            String plan = getFragmentPlan(query);
            PlanTestBase.assertContains(plan, "0:OlapScanNode\n" +
                    "     TABLE: test_mv1\n" +
                    "     PREAGGREGATION: ON\n" +
                    "     partitions=1/1");
        }

        {
            String query = "select  t1.d, t2.b, t3.c, count(distinct t1.a) " +
                    " from  iceberg0.partitioned_db.part_tbl1 as t1 " +
                    " inner join iceberg0.partitioned_db.part_tbl2 t2 on t1.d=t2.d " +
                    " inner join iceberg0.partitioned_db.part_tbl3 t3 on t1.d=t3.d " +
                    " where t1.d>='2023-08-01' " +
                    " group by t1.d, t2.b, t3.c;";

            String plan = getFragmentPlan(query);
            PlanTestBase.assertContains(plan, "UNION");
            PlanTestBase.assertContains(plan, "13:OlapScanNode\n" +
                    "     TABLE: test_mv1\n" +
                    "     PREAGGREGATION: ON\n" +
                    "     partitions=1/1");
        }

        {
            String query = "select  t1.d, count(distinct t1.a) " +
                    " from  iceberg0.partitioned_db.part_tbl1 as t1 " +
                    " inner join iceberg0.partitioned_db.part_tbl2 t2 on t1.d=t2.d " +
                    " inner join iceberg0.partitioned_db.part_tbl3 t3 on t1.d=t3.d " +
                    " where t1.d>='2023-08-01' " +
                    " group by t1.d;";

            String plan = getFragmentPlan(query);
            PlanTestBase.assertContains(plan, "UNION");
            PlanTestBase.assertContains(plan, "15:OlapScanNode\n" +
                    "     TABLE: test_mv1\n" +
                    "     PREAGGREGATION: ON\n" +
                    "     partitions=1/1");
        }
        connectContext.getSessionVariable().setMaterializedViewRewriteMode("default");
        starRocksAssert.dropMaterializedView(mvName);
    }
}
