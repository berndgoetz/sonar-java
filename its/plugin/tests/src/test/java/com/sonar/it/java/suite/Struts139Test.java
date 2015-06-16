/*
 * Java :: IT :: Plugin :: Tests
 * Copyright (C) 2013 ${owner}
 * dev@sonar.codehaus.org
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02
 */
package com.sonar.it.java.suite;

import com.sonar.orchestrator.Orchestrator;
import com.sonar.orchestrator.build.MavenBuild;
import org.fest.assertions.Delta;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.sonar.wsclient.services.Measure;
import org.sonar.wsclient.services.Resource;
import org.sonar.wsclient.services.ResourceQuery;

import java.util.Date;

import static org.fest.assertions.Assertions.assertThat;
import static org.junit.Assume.assumeTrue;

public class Struts139Test {

  @ClassRule
  public static Orchestrator orchestrator = JavaTestSuite.ORCHESTRATOR;

  private static final String PROJECT_STRUTS = "org.apache.struts:struts-parent";
  private static final String MODULE_CORE = "org.apache.struts:struts-core";
  private static final String PACKAGE_ACTION = JavaTestSuite.keyFor(MODULE_CORE, "org/apache/struts/action", "");
  private static final String FILE_ACTION = JavaTestSuite.keyFor(MODULE_CORE, "org/apache/struts/action/", "Action.java");

  @BeforeClass
  public static void analyzeProject() {
    orchestrator.resetData();

    MavenBuild build = MavenBuild.create(TestUtils.projectPom("struts-1.3.9-lite"));
    if(JavaTestSuite.isAtLeastPlugin3_4()) {
      build.setGoals("org.jacoco:jacoco-maven-plugin:prepare-agent clean verify");
    } else {
      build.setGoals("org.jacoco:jacoco-maven-plugin:0.7.4.201502262128:prepare-agent clean verify");
    }
    orchestrator.executeBuild(build);

    MavenBuild analysis = MavenBuild.create(TestUtils.projectPom("struts-1.3.9-lite"))
      .setProperty("sonar.scm.disabled", "true")
      .setGoals("sonar:sonar");
    orchestrator.executeBuilds(build, analysis);
  }

  @Test
  public void shouldReturnAnalysisDate() {
    Date date = orchestrator.getServer().getWsClient().find(new ResourceQuery(PROJECT_STRUTS)).getDate();
    assertThat(date).isNotNull();
    assertThat(date.getYear()).isGreaterThan(111); // 1900 + 110
  }

  @Test
  public void strutsIsAnalyzed() {
    assertThat(orchestrator.getServer().getWsClient().find(new ResourceQuery(PROJECT_STRUTS)).getName()).isEqualTo("Struts");
    assertThat(orchestrator.getServer().getWsClient().find(new ResourceQuery(PROJECT_STRUTS)).getVersion()).isEqualTo("1.3.9");
    assertThat(orchestrator.getServer().getWsClient().find(new ResourceQuery(MODULE_CORE)).getName()).isEqualTo("Struts Core");
  }

  @Test
  public void size_metrics() {
    assertThat(getProjectMeasure("files").getIntValue()).isEqualTo(320);
    assertThat(getPackageMeasure("files").getIntValue()).isEqualTo(21);
    assertThat(getFileMeasure("files").getIntValue()).isEqualTo(1);
    assertThat(getProjectMeasure("lines").getIntValue()).isEqualTo(65059);
    assertThat(getProjectMeasure("ncloc").getIntValue()).isEqualTo(27577);
    // Compute accessors based on Syntax Tree
    assertThat(getProjectMeasure("functions").getIntValue()).isEqualTo(2730);
    assertThat(getProjectMeasure("accessors").getIntValue()).isEqualTo(208);
    assertThat(getProjectMeasure("classes").getIntValue()).isEqualTo(337);
    assertThat(getCoreModuleMeasure("files").getIntValue()).isEqualTo(134);

    assertThat(getCoreModuleMeasure("comment_lines").getIntValue()).isEqualTo(7605);

    int expected_public_api = 2846 - 208;
    assertThat(getProjectMeasure("public_api").getIntValue()).isEqualTo(expected_public_api);
  }

  @Test
  public void unit_test_metrics() {
    assertThat(getProjectMeasure("coverage").getValue()).isEqualTo(25.5, Delta.delta(0.1));
    assertThat(getCoreModuleMeasure("coverage").getValue()).isEqualTo(37.1, Delta.delta(0.2));
    assertThat(getProjectMeasure("line_coverage").getValue()).isEqualTo(26.0);
    assertThat(getProjectMeasure("branch_coverage").getValue()).isEqualTo(24.1);
    // overall coverage is the same as UT if no IT.
    assertThat(getCoreModuleMeasure("overall_coverage").getValue()).isEqualTo(37.1, Delta.delta(0.2));
    assertThat(getProjectMeasure("overall_coverage").getValue()).isEqualTo(25.5, Delta.delta(0.1));
    assertThat(getProjectMeasure("overall_line_coverage").getValue()).isEqualTo(26.0);
    assertThat(getProjectMeasure("overall_branch_coverage").getValue()).isEqualTo(24.1);
    assertThat(getProjectMeasure("tests").getIntValue()).isEqualTo(307);
    assertThat(getProjectMeasure("test_execution_time").getIntValue()).isGreaterThan(200);
    assertThat(getProjectMeasure("test_errors").getIntValue()).isEqualTo(0);
    assertThat(getProjectMeasure("test_failures").getIntValue()).isEqualTo(0);
    assertThat(getProjectMeasure("skipped_tests").getIntValue()).isEqualTo(0);
    assertThat(getProjectMeasure("test_success_density").getValue()).isEqualTo(100.0);
  }

  @Test
  public void complexityMetrics() {
    assertThat(getProjectMeasure("complexity").getIntValue()).isEqualTo(6740);
    assertThat(getProjectMeasure("class_complexity").getValue()).isEqualTo(20.0);
    assertThat(getProjectMeasure("function_complexity").getValue()).isEqualTo(2.5);
    int expected_statements = 12103;
    expected_statements += 3; // empty statements in type declaration or member of classes in struts-1.3.9
    assertThat(getProjectMeasure("statements").getIntValue()).isEqualTo(expected_statements);
  }

  /**
   * SONAR-3289
   */
  @Test
  public void fileComplexityDistribution() throws Exception {
    assertThat(orchestrator.getServer().getWsClient().find(
      ResourceQuery.createForMetrics(JavaTestSuite.keyFor(MODULE_CORE, "org/apache/struts/config", ""), "file_complexity_distribution"))
      .getMeasure("file_complexity_distribution").getData()).isEqualTo("0=2;5=2;10=2;20=0;30=4;60=4;90=1");
    assertThat(getCoreModuleMeasure("file_complexity_distribution").getData()).isEqualTo("0=43;5=23;10=21;20=11;30=17;60=9;90=10");
    assertThat(getProjectMeasure("file_complexity_distribution").getData()).isEqualTo("0=133;5=43;10=45;20=34;30=35;60=16;90=14");
  }

  @Test
  public void functionComplexityDistribution() throws Exception {
    assertThat(orchestrator.getServer().getWsClient().find(
      ResourceQuery.createForMetrics(JavaTestSuite.keyFor(MODULE_CORE, "org/apache/struts/config", ""), "function_complexity_distribution"))
      .getMeasure("function_complexity_distribution").getData()).isEqualTo("1=128;2=88;4=11;6=12;8=7;10=2;12=8");
  }

  @Test
  public void shouldNotPersistComplexityDistributionsOnFiles() {
    ResourceQuery query = ResourceQuery.createForMetrics(
      JavaTestSuite.keyFor(MODULE_CORE, "org/apache/struts/config/", "ConfigRuleSet.java"),
      "function_complexity_distribution", "file_complexity_distribution");
    assertThat(orchestrator.getServer().getWsClient().find(query).getMeasures().size()).isEqualTo(0);
  }

  @Test
  public void designMeasures() {
    assumeTrue(JavaTestSuite.sonarqube_version_is_prior_to_5_2());
    assertThat(getCoreModuleMeasure("package_cycles").getIntValue()).isGreaterThan(10);
    assertThat(getCoreModuleMeasure("package_cycles").getIntValue()).isLessThan(50);

    assertThat(getCoreModuleMeasure("package_feedback_edges").getIntValue()).isGreaterThan(3);
    assertThat(getCoreModuleMeasure("package_feedback_edges").getIntValue()).isLessThan(10);

    assertThat(getCoreModuleMeasure("package_tangles").getIntValue()).isGreaterThan(10);
    assertThat(getCoreModuleMeasure("package_tangles").getIntValue()).isLessThan(50);

    assertThat(orchestrator.getServer().getWsClient().find(ResourceQuery.createForMetrics(PROJECT_STRUTS, "dit", "noc")).getMeasures().size()).isEqualTo(0);
  }

  @Test
  public void shouldGetDetailsOfCoverageHits() {
    Resource resource = orchestrator.getServer().getWsClient().find(
      ResourceQuery.createForMetrics(JavaTestSuite.keyFor(MODULE_CORE, "org/apache/struts/action/", "ActionForward.java"), "coverage_line_hits_data"));
    Measure coverageData = resource.getMeasure("coverage_line_hits_data");
    assertThat(coverageData).isNotNull();
    assertThat(coverageData.getData().length()).isGreaterThan(10);
    assertThat(coverageData.getData()).matches("(\\d+=\\d+;{0,1})+");
  }

  private Measure getFileMeasure(String metricKey) {
    return orchestrator.getServer().getWsClient().find(ResourceQuery.createForMetrics(FILE_ACTION, metricKey)).getMeasure(metricKey);
  }

  private Measure getCoreModuleMeasure(String metricKey) {
    return orchestrator.getServer().getWsClient().find(ResourceQuery.createForMetrics(MODULE_CORE, metricKey)).getMeasure(metricKey);
  }

  private Measure getProjectMeasure(String metricKey) {
    return orchestrator.getServer().getWsClient().find(ResourceQuery.createForMetrics(PROJECT_STRUTS, metricKey)).getMeasure(metricKey);
  }

  private Measure getPackageMeasure(String metricKey) {
    return orchestrator.getServer().getWsClient().find(ResourceQuery.createForMetrics(PACKAGE_ACTION, metricKey)).getMeasure(metricKey);
  }
}
