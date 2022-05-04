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

package org.apache.spark.sql

import scala.collection.JavaConverters._

import org.apache.spark.sql.execution.adaptive.AdaptiveSparkPlanHelper
import org.apache.spark.sql.test.SharedSparkSession
import org.apache.spark.sql.types._

class DataFramePITJoinSuite extends QueryTest
  with SharedSparkSession
  with AdaptiveSparkPlanHelper {

  def prepareForPITJoin(): (DataFrame, DataFrame) = {
    val schema1 = StructType(
      StructField("a", IntegerType, false) ::
        StructField("b", StringType, false) ::
        StructField("left_val", StringType, false) :: Nil)
    val rowSeq1: List[Row] = List(Row(1, "x", "a"), Row(5, "y", "b"), Row(10, "z", "c"))
    val df1 = spark.createDataFrame(rowSeq1.asJava, schema1)

    val schema2 = StructType(
      StructField("a", IntegerType) ::
        StructField("b", StringType) ::
        StructField("right_val", IntegerType) :: Nil)
    val rowSeq2: List[Row] = List(Row(1, "v", 1), Row(2, "w", 2), Row(3, "x", 3),
      Row(6, "y", 6), Row(7, "z", 7))
    val df2 = spark.createDataFrame(rowSeq2.asJava, schema2)

    (df1, df2)
  }

  test("PIT join - with ID") {
    val (df1, df2) = prepareForPITJoin()
    checkAnswer(
      df1.pitJoin(
        df2,
        df1.col("a"),
        df2.col("a"), df1("b") === df2("b")
      ),
      Seq(
        Row(10, "z", "c", 7, "z", 7)
      )
    )
  }

  test("as-of join - usingColumns, left outer") {
    val (df1, df2) = prepareForPITJoin()
    checkAnswer(
      df1.pitJoin(df2, df1.col("a"), df2.col("a"), df1("b") === df2("b"),
        true),
      Seq(
        Row(1, "x", "a", null, null, null),
        Row(5, "y", "b", null, null, null),
        Row(10, "z", "c", 7, "z", 7)
      )
    )
  }

  test("as-of join - tolerance = 1") {
    val (df1, df2) = prepareForPITJoin()
    checkAnswer(
      df1.pitJoin(df2, df1.col("a"), df2.col("a"), df1("b") === df2("b"),
        false, tolerance = 1),
      Seq(
        Row(1, "x", "a", 1, "v", 1)
      )
    )
  }
}
