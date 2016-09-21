/*
 * *
 *  * Licensed to the Apache Software Foundation (ASF) under one
 *  * or more contributor license agreements.  See the NOTICE file
 *  * distributed with this work for additional information
 *  * regarding copyright ownership.  The ASF licenses this file
 *  * to you under the Apache License, Version 2.0 (the
 *  * "License"); you may not use this file except in compliance
 *  * with the License.  You may obtain a copy of the License at
 *  * <p>
 *  * http://www.apache.org/licenses/LICENSE-2.0
 *  * <p>
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 *
 */
package org.apache.storm.sql.compiler.backends.trident;

import com.google.common.collect.Lists;
import org.apache.calcite.adapter.java.JavaTypeFactory;
import org.apache.calcite.jdbc.JavaTypeFactoryImpl;
import org.apache.calcite.rel.type.RelDataTypeSystem;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.storm.Config;
import org.apache.storm.ILocalCluster;
import org.apache.storm.LocalCluster;
import org.apache.storm.generated.StormTopology;
import org.apache.storm.sql.TestUtils;
import org.apache.storm.sql.TestUtils.MockSqlTridentDataSource.CollectDataFunction;
import org.apache.storm.sql.compiler.TestCompilerUtils;
import org.apache.storm.sql.runtime.ISqlTridentDataSource;
import org.apache.storm.sql.runtime.trident.AbstractTridentProcessor;
import org.apache.storm.trident.TridentTopology;
import org.apache.storm.tuple.Fields;
import org.apache.storm.tuple.Values;
import org.apache.storm.utils.Utils;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import static org.apache.storm.sql.TestUtils.MockSqlTridentDataSource.CollectDataFunction.getCollectedValues;

public class TestPlanCompiler {
  private final JavaTypeFactory typeFactory = new JavaTypeFactoryImpl(
          RelDataTypeSystem.DEFAULT);

  @Before
  public void setUp() {
    getCollectedValues().clear();
  }

  @Test
  public void testCompile() throws Exception {
    final int EXPECTED_VALUE_SIZE = 2;
    String sql = "SELECT ID FROM FOO WHERE ID > 2";
    TestCompilerUtils.CalciteState state = TestCompilerUtils.sqlOverDummyTable(sql);
    final Map<String, ISqlTridentDataSource> data = new HashMap<>();
    data.put("FOO", new TestUtils.MockSqlTridentDataSource());
    PlanCompiler compiler = new PlanCompiler(data, typeFactory);
    final AbstractTridentProcessor proc = compiler.compileForTest(state.tree());
    final TridentTopology topo = proc.build(data);
    Fields f = proc.outputStream().getOutputFields();
    proc.outputStream().each(f, new CollectDataFunction(), new Fields()).toStream();
    runTridentTopology(EXPECTED_VALUE_SIZE, proc, topo);
    Assert.assertArrayEquals(new Values[] { new Values(3), new Values(4)}, getCollectedValues().toArray());
  }

  @Test
  public void testCompileGroupByExp() throws Exception {
    final int EXPECTED_VALUE_SIZE = 1;
    final Map<String, ISqlTridentDataSource> data = new HashMap<>();
    data.put("FOO", new TestUtils.MockSqlTridentGroupedDataSource());
    String sql = "SELECT GRPID, COUNT(*) AS CNT, MAX(AGE) AS MAX_AGE, MIN(AGE) AS MIN_AGE, AVG(AGE) AS AVG_AGE, MAX(AGE) - MIN(AGE) AS DIFF FROM FOO GROUP BY GRPID";
    TestCompilerUtils.CalciteState state = TestCompilerUtils.sqlOverDummyGroupByTable(sql);
    PlanCompiler compiler = new PlanCompiler(data, typeFactory);
    final AbstractTridentProcessor proc = compiler.compileForTest(state.tree());
    final TridentTopology topo = proc.build(data);
    Fields f = proc.outputStream().getOutputFields();
    proc.outputStream().each(f, new CollectDataFunction(), new Fields()).toStream();
    runTridentTopology(EXPECTED_VALUE_SIZE, proc, topo);

    Assert.assertArrayEquals(new Values[] { new Values(0, 5L, 5, 1, 3, 4)}, getCollectedValues().toArray());
  }

  @Test
  public void testCompileGroupByExpWithExprInAggCall() throws Exception {
    final int EXPECTED_VALUE_SIZE = 1;
    final Map<String, ISqlTridentDataSource> data = new HashMap<>();
    data.put("FOO", new TestUtils.MockSqlTridentGroupedDataSource());
    String sql = "SELECT GRPID, COUNT(*) AS CNT, MAX(SCORE - AGE) AS MAX_SCORE_MINUS_AGE FROM FOO GROUP BY GRPID";
    TestCompilerUtils.CalciteState state = TestCompilerUtils.sqlOverDummyGroupByTable(sql);
    PlanCompiler compiler = new PlanCompiler(data, typeFactory);
    final AbstractTridentProcessor proc = compiler.compileForTest(state.tree());
    final TridentTopology topo = proc.build(data);
    Fields f = proc.outputStream().getOutputFields();
    proc.outputStream().each(f, new CollectDataFunction(), new Fields()).toStream();
    runTridentTopology(EXPECTED_VALUE_SIZE, proc, topo);

    Assert.assertArrayEquals(new Values[] { new Values(0, 5L, 39)}, getCollectedValues().toArray());
  }

  @Test
  public void testCompileEquiJoinAndGroupBy() throws Exception {
    final int EXPECTED_VALUE_SIZE = 2;
    final Map<String, ISqlTridentDataSource> data = new HashMap<>();
    data.put("EMP", new TestUtils.MockSqlTridentJoinDataSourceEmp());
    data.put("DEPT", new TestUtils.MockSqlTridentJoinDataSourceDept());
    String sql = "SELECT d.DEPTID, count(EMPID) FROM EMP AS e JOIN DEPT AS d ON e.DEPTID = d.DEPTID WHERE e.EMPID > 0 GROUP BY d.DEPTID";
    TestCompilerUtils.CalciteState state = TestCompilerUtils.sqlOverSimpleEquiJoinTables(sql);
    PlanCompiler compiler = new PlanCompiler(data, typeFactory);
    final AbstractTridentProcessor proc = compiler.compileForTest(state.tree());
    final TridentTopology topo = proc.build(data);
    Fields f = proc.outputStream().getOutputFields();
    proc.outputStream().each(f, new CollectDataFunction(), new Fields()).toStream();
    runTridentTopology(EXPECTED_VALUE_SIZE, proc, topo);

    assertListsAreEqualIgnoringOrder(Lists.newArrayList(new Values(1, 2L), new Values(0, 2L)), getCollectedValues());
  }

  @Test
  public void testCompileEquiJoinWithLeftOuterJoin() throws Exception {
    final int EXPECTED_VALUE_SIZE = 3;
    final Map<String, ISqlTridentDataSource> data = new HashMap<>();
    data.put("EMP", new TestUtils.MockSqlTridentJoinDataSourceEmp());
    data.put("DEPT", new TestUtils.MockSqlTridentJoinDataSourceDept());
    String sql = "SELECT d.DEPTID, e.DEPTID FROM DEPT AS d LEFT OUTER JOIN EMP AS e ON d.DEPTID = e.DEPTID WHERE e.EMPID is null";
    TestCompilerUtils.CalciteState state = TestCompilerUtils.sqlOverSimpleEquiJoinTables(sql);
    PlanCompiler compiler = new PlanCompiler(data, typeFactory);
    final AbstractTridentProcessor proc = compiler.compileForTest(state.tree());
    final TridentTopology topo = proc.build(data);
    Fields f = proc.outputStream().getOutputFields();
    proc.outputStream().each(f, new CollectDataFunction(), new Fields()).toStream();
    runTridentTopology(EXPECTED_VALUE_SIZE, proc, topo);

    assertListsAreEqualIgnoringOrder(Lists.newArrayList(new Values(2, null), new Values(3, null), new Values(4, null)), getCollectedValues());
  }

  @Test
  public void testCompileEquiJoinWithRightOuterJoin() throws Exception {
    final int EXPECTED_VALUE_SIZE = 3;
    final Map<String, ISqlTridentDataSource> data = new HashMap<>();
    data.put("EMP", new TestUtils.MockSqlTridentJoinDataSourceEmp());
    data.put("DEPT", new TestUtils.MockSqlTridentJoinDataSourceDept());
    String sql = "SELECT d.DEPTID, e.DEPTID FROM EMP AS e RIGHT OUTER JOIN DEPT AS d ON e.DEPTID = d.DEPTID WHERE e.EMPID is null";
    TestCompilerUtils.CalciteState state = TestCompilerUtils.sqlOverSimpleEquiJoinTables(sql);
    PlanCompiler compiler = new PlanCompiler(data, typeFactory);
    final AbstractTridentProcessor proc = compiler.compileForTest(state.tree());
    final TridentTopology topo = proc.build(data);
    Fields f = proc.outputStream().getOutputFields();
    proc.outputStream().each(f, new CollectDataFunction(), new Fields()).toStream();
    runTridentTopology(EXPECTED_VALUE_SIZE, proc, topo);

    assertListsAreEqualIgnoringOrder(Lists.newArrayList(new Values(2, null), new Values(3, null), new Values(4, null)), getCollectedValues());
  }

  @Test
  public void testCompileEquiJoinWithFullOuterJoin() throws Exception {
    final int EXPECTED_VALUE_SIZE = 8;
    final Map<String, ISqlTridentDataSource> data = new HashMap<>();
    data.put("EMP", new TestUtils.MockSqlTridentJoinDataSourceEmp());
    data.put("DEPT", new TestUtils.MockSqlTridentJoinDataSourceDept());
    String sql = "SELECT e.DEPTID, d.DEPTNAME FROM EMP AS e FULL OUTER JOIN DEPT AS d ON e.DEPTID = d.DEPTID WHERE (d.DEPTNAME is null OR e.EMPNAME is null)";
    TestCompilerUtils.CalciteState state = TestCompilerUtils.sqlOverSimpleEquiJoinTables(sql);
    PlanCompiler compiler = new PlanCompiler(data, typeFactory);
    final AbstractTridentProcessor proc = compiler.compileForTest(state.tree());
    final TridentTopology topo = proc.build(data);
    Fields f = proc.outputStream().getOutputFields();
    proc.outputStream().each(f, new CollectDataFunction(), new Fields()).toStream();
    runTridentTopology(EXPECTED_VALUE_SIZE, proc, topo);

    assertListsAreEqualIgnoringOrder(Lists.newArrayList(new Values(null, "dept-2"), new Values(null, "dept-3"), new Values(null, "dept-4"),
            new Values(10, null), new Values(11, null), new Values(12, null), new Values(13, null), new Values(14, null)),
            getCollectedValues());
  }

  @Test
  public void testInsert() throws Exception {
    final int EXPECTED_VALUE_SIZE = 1;
    String sql = "INSERT INTO BAR SELECT ID, NAME, ADDR FROM FOO WHERE ID > 3";
    TestCompilerUtils.CalciteState state = TestCompilerUtils.sqlOverDummyTable(sql);
    final Map<String, ISqlTridentDataSource> data = new HashMap<>();
    data.put("FOO", new TestUtils.MockSqlTridentDataSource());
    data.put("BAR", new TestUtils.MockSqlTridentDataSource());
    PlanCompiler compiler = new PlanCompiler(data, typeFactory);
    final AbstractTridentProcessor proc = compiler.compileForTest(state.tree());
    final TridentTopology topo = proc.build(data);
    runTridentTopology(EXPECTED_VALUE_SIZE, proc, topo);
    Assert.assertArrayEquals(new Values[] { new Values(4, "x", "y")}, getCollectedValues().toArray());
  }

  @Test
  public void testLogicalExpr() throws Exception {
    final int EXPECTED_VALUE_SIZE = 1;
    String sql = "SELECT ID > 0 OR ID < 1, ID > 0 AND ID < 1, NOT (ID > 0 AND ID < 1) FROM FOO WHERE ID > 0 AND ID < 2";
    TestCompilerUtils.CalciteState state = TestCompilerUtils.sqlOverDummyTable(sql);
    Map<String, ISqlTridentDataSource> data = new HashMap<>();
    data.put("FOO", new TestUtils.MockSqlTridentDataSource());
    PlanCompiler compiler = new PlanCompiler(data, typeFactory);
    AbstractTridentProcessor proc = compiler.compileForTest(state.tree());
    final TridentTopology topo = proc.build(data);
    Fields f = proc.outputStream().getOutputFields();
    proc.outputStream().each(f, new CollectDataFunction(), new Fields()).toStream();
    runTridentTopology(EXPECTED_VALUE_SIZE, proc, topo);
    Assert.assertArrayEquals(new Values[] { new Values(true, false, true) }, getCollectedValues().toArray());
  }

  @Test
  public void testUdf() throws Exception {
    int EXPECTED_VALUE_SIZE = 1;
    String sql = "SELECT MYPLUS(ID, 3)" +
            "FROM FOO " +
            "WHERE ID = 2";
    TestCompilerUtils.CalciteState state = TestCompilerUtils.sqlOverDummyTable(sql);
    Map<String, ISqlTridentDataSource> data = new HashMap<>();
    data.put("FOO", new TestUtils.MockSqlTridentDataSource());
    PlanCompiler compiler = new PlanCompiler(data, typeFactory);
    AbstractTridentProcessor proc = compiler.compileForTest(state.tree());
    final TridentTopology topo = proc.build(data);
    Fields f = proc.outputStream().getOutputFields();
    proc.outputStream().each(f, new CollectDataFunction(), new Fields()).toStream();
    runTridentTopology(EXPECTED_VALUE_SIZE, proc, topo);
    Assert.assertArrayEquals(new Values[] { new Values(5) }, getCollectedValues().toArray());
  }

  @Test
  public void testUdaf() throws Exception {
    int EXPECTED_VALUE_SIZE = 1;
    String sql = "SELECT GRPID, COUNT(*) AS CNT, MYSTATICSUM(AGE) AS MY_STATIC_SUM, MYSUM(AGE) AS MY_SUM FROM FOO GROUP BY GRPID";
    TestCompilerUtils.CalciteState state = TestCompilerUtils.sqlOverDummyGroupByTable(sql);
    Map<String, ISqlTridentDataSource> data = new HashMap<>();
    data.put("FOO", new TestUtils.MockSqlTridentGroupedDataSource());
    PlanCompiler compiler = new PlanCompiler(data, typeFactory);
    AbstractTridentProcessor proc = compiler.compileForTest(state.tree());
    final TridentTopology topo = proc.build(data);
    Fields f = proc.outputStream().getOutputFields();
    proc.outputStream().each(f, new CollectDataFunction(), new Fields()).toStream();
    runTridentTopology(EXPECTED_VALUE_SIZE, proc, topo);
    Assert.assertArrayEquals(new Values[] { new Values(0, 5L, 15L, 15L) }, getCollectedValues().toArray());
  }

  private void runTridentTopology(final int expectedValueSize, AbstractTridentProcessor proc,
                                  TridentTopology topo) throws Exception {
    final Config conf = new Config();
    conf.setMaxSpoutPending(20);

    ILocalCluster cluster = new LocalCluster();
    StormTopology stormTopo = topo.build();
    try {
      Utils.setClassLoaderForJavaDeSerialize(proc.getClass().getClassLoader());
      cluster.submitTopology("storm-sql", conf, stormTopo);
      waitForCompletion(1000 * 1000, new Callable<Boolean>() {
        @Override
        public Boolean call() throws Exception {
          return getCollectedValues().size() < expectedValueSize;
        }
      });
    } finally {
      Utils.resetClassLoaderForJavaDeSerialize();
      cluster.shutdown();
    }
  }

  private void waitForCompletion(long timeout, Callable<Boolean> cond) throws Exception {
    long start = TestUtils.monotonicNow();
    while (TestUtils.monotonicNow() - start < timeout && cond.call()) {
      Thread.sleep(100);
    }
  }

  private void assertListsAreEqualIgnoringOrder(List<Values> expected, List<List<Object>> actual) {
    Assert.assertTrue("Two lists are not same (even ignoring order)!\n"+ "Expected: " + expected + "\n" + "Actual: " + actual,
            CollectionUtils.isEqualCollection(expected, actual));
  }
}