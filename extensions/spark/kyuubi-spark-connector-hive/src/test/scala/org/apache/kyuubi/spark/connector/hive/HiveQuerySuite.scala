/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.kyuubi.spark.connector.hive

import org.apache.spark.sql.{AnalysisException, Row, SparkSession}

class HiveQuerySuite extends KyuubiHiveTest {

  def withTempNonPartitionedTable(spark: SparkSession, table: String)(f: => Unit): Unit = {
    spark.sql(
      s"""
         | CREATE TABLE IF NOT EXISTS
         | $table (id String, date String)
         | USING PARQUET
         |""".stripMargin).collect()
    try f
    finally spark.sql(s"DROP TABLE $table")
  }

  def withTempPartitionedTable(spark: SparkSession, table: String)(f: => Unit): Unit = {
    spark.sql(
      s"""
         | CREATE TABLE IF NOT EXISTS
         | $table (id String, year String, month string)
         | USING PARQUET
         | PARTITIONED BY (year, month)
         |""".stripMargin).collect()
    try f
    finally spark.sql(s"DROP TABLE $table")
  }

  def checkQueryResult(
      sql: String,
      sparkSession: SparkSession,
      excepted: Array[Row]): Unit = {
    val result = sparkSession.sql(sql).collect()
    assert(result sameElements excepted)
  }

  test("simple query") {
    withSparkSession() { spark =>
      val table = "hive.default.employee"
      withTempNonPartitionedTable(spark, table) {
        // can query an existing Hive table in three sections
        val result = spark.sql(
          s"""
             | SELECT * FROM $table
             |""".stripMargin)
        assert(result.collect().isEmpty)

        // error msg should contains catalog info if table is not exist
        val e = intercept[AnalysisException] {
          spark.sql(
            s"""
               | SELECT * FROM hive.ns1.tb1
               |""".stripMargin)
        }
        assert(e.getMessage().contains("Table or view not found: hive.ns1.tb1"))
      }
    }
  }

  test("Non partitioned table insert") {
    withSparkSession() { spark =>
      val table = "hive.default.employee"
      withTempNonPartitionedTable(spark, table) {
        spark.sql(
          s"""
             | INSERT OVERWRITE
             | $table
             | VALUES("yi", "2022-08-08")
             |""".stripMargin).collect()

        checkQueryResult(s"select * from $table", spark, Array(Row.apply("yi", "2022-08-08")))
      }
    }
  }

  test("Partitioned table insert and all dynamic insert") {
    withSparkSession(Map("hive.exec.dynamic.partition.mode" -> "nonstrict")) { spark =>
      val table = "hive.default.employee"
      withTempPartitionedTable(spark, table) {
        spark.sql(
          s"""
             | INSERT OVERWRITE
             | $table
             | VALUES("yi", "2022", "0808")
             |""".stripMargin).collect()

        checkQueryResult(s"select * from $table", spark, Array(Row.apply("yi", "2022", "0808")))
      }
    }
  }

  test("[KYUUBI #4525] Partitioning predicates should take effect to filter data") {
    withSparkSession(Map("hive.exec.dynamic.partition.mode" -> "nonstrict")) { spark =>
      val table = "hive.default.employee"
      withTempPartitionedTable(spark, table) {
        spark.sql(
          s"""
             | INSERT OVERWRITE
             | $table
             | VALUES("yi", "2022", "0808"),("yi", "2023", "0316")
             |""".stripMargin).collect()

        checkQueryResult(
          s"select * from $table where year = '2022'",
          spark,
          Array(Row.apply("yi", "2022", "0808")))

        checkQueryResult(
          s"select * from $table where year = '2023'",
          spark,
          Array(Row.apply("yi", "2023", "0316")))
      }
    }
  }

  test("Partitioned table insert and all static insert") {
    withSparkSession() { spark =>
      val table = "hive.default.employee"
      withTempPartitionedTable(spark, table) {
        spark.sql(
          s"""
             | INSERT OVERWRITE
             | $table PARTITION(year = '2022', month = '08')
             | VALUES("yi")
             |""".stripMargin).collect()

        checkQueryResult(s"select * from $table", spark, Array(Row.apply("yi", "2022", "08")))
      }
    }
  }

  test("Partitioned table insert and static and dynamic insert") {
    withSparkSession() { spark =>
      val table = "hive.default.employee"
      withTempPartitionedTable(spark, table) {
        spark.sql(
          s"""
             | INSERT OVERWRITE
             | $table PARTITION(year = '2022')
             | VALUES("yi", "08")
             |""".stripMargin).collect()

        checkQueryResult(s"select * from $table", spark, Array(Row.apply("yi", "2022", "08")))
      }
    }
  }

  test("Partitioned table insert and static partition value is empty string") {
    withSparkSession() { spark =>
      val table = "hive.default.employee"
      withTempPartitionedTable(spark, table) {
        val exception = intercept[KyuubiHiveConnectorException] {
          spark.sql(
            s"""
               | INSERT OVERWRITE
               | $table PARTITION(year = '', month = '08')
               | VALUES("yi")
               |""".stripMargin).collect()
        }
        // 1. not thrown `Dynamic partition cannot be the parent of a static partition`
        // 2. thrown `Partition spec is invalid`, should be consist with spark v1.
        assert(exception.message.contains("Partition spec is invalid. The spec (year='') " +
          "contains an empty partition column value"))
      }
    }
  }
}
