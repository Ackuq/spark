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

package org.apache.spark.sql.errors

import org.apache.spark.sql.{AnalysisException, IntegratedUDFTestUtils, QueryTest, Row}
import org.apache.spark.sql.api.java.{UDF1, UDF2, UDF23Test}
import org.apache.spark.sql.expressions.SparkUserDefinedFunction
import org.apache.spark.sql.functions.{grouping, grouping_id, sum, udf}
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.types.{IntegerType, MapType, StringType, StructField, StructType}

case class StringLongClass(a: String, b: Long)

case class StringIntClass(a: String, b: Int)

case class ComplexClass(a: Long, b: StringLongClass)

class QueryCompilationErrorsSuite
  extends QueryTest
  with QueryErrorsSuiteBase {
  import testImplicits._

  test("CANNOT_UP_CAST_DATATYPE: invalid upcast data type") {
    val e1 = intercept[AnalysisException] {
      sql("select 'value1' as a, 1L as b").as[StringIntClass]
    }
    checkErrorClass(
      exception = e1,
      errorClass = "CANNOT_UP_CAST_DATATYPE",
      msg =
        s"""
           |Cannot up cast b from "BIGINT" to "INT".
           |The type path of the target object is:
           |- field (class: "scala.Int", name: "b")
           |- root class: "org.apache.spark.sql.errors.StringIntClass"
           |You can either add an explicit cast to the input data or choose a higher precision type
         """.stripMargin.trim + " of the field in the target object")

    val e2 = intercept[AnalysisException] {
      sql("select 1L as a," +
        " named_struct('a', 'value1', 'b', cast(1.0 as decimal(38,18))) as b")
        .as[ComplexClass]
    }
    checkErrorClass(
      exception = e2,
      errorClass = "CANNOT_UP_CAST_DATATYPE",
      msg =
        s"""
           |Cannot up cast b.`b` from "DECIMAL(38,18)" to "BIGINT".
           |The type path of the target object is:
           |- field (class: "scala.Long", name: "b")
           |- field (class: "org.apache.spark.sql.errors.StringLongClass", name: "b")
           |- root class: "org.apache.spark.sql.errors.ComplexClass"
           |You can either add an explicit cast to the input data or choose a higher precision type
         """.stripMargin.trim + " of the field in the target object")
  }

  test("UNSUPPORTED_GROUPING_EXPRESSION: filter with grouping/grouping_Id expression") {
    val df = Seq(
      (536361, "85123A", 2, 17850),
      (536362, "85123B", 4, 17850),
      (536363, "86123A", 6, 17851)
    ).toDF("InvoiceNo", "StockCode", "Quantity", "CustomerID")
    Seq("grouping", "grouping_id").foreach { grouping =>
      val e = intercept[AnalysisException] {
        df.groupBy("CustomerId").agg(Map("Quantity" -> "max"))
          .filter(s"$grouping(CustomerId)=17850")
      }
      checkErrorClass(
        exception = e,
        errorClass = "UNSUPPORTED_GROUPING_EXPRESSION",
        msg = "grouping()/grouping_id() can only be used with GroupingSets/Cube/Rollup")
    }
  }

  test("UNSUPPORTED_GROUPING_EXPRESSION: Sort with grouping/grouping_Id expression") {
    val df = Seq(
      (536361, "85123A", 2, 17850),
      (536362, "85123B", 4, 17850),
      (536363, "86123A", 6, 17851)
    ).toDF("InvoiceNo", "StockCode", "Quantity", "CustomerID")
    Seq(grouping("CustomerId"), grouping_id("CustomerId")).foreach { grouping =>
      val e = intercept[AnalysisException] {
        df.groupBy("CustomerId").agg(Map("Quantity" -> "max")).
          sort(grouping)
      }
      checkErrorClass(
        exception = e,
        errorClass = "UNSUPPORTED_GROUPING_EXPRESSION",
        msg = "grouping()/grouping_id() can only be used with GroupingSets/Cube/Rollup")
    }
  }

  test("ILLEGAL_SUBSTRING: the argument_index of string format is invalid") {
    withSQLConf(SQLConf.ALLOW_ZERO_INDEX_IN_FORMAT_STRING.key -> "false") {
      val e = intercept[AnalysisException] {
        sql("select format_string('%0$s', 'Hello')")
      }
      checkErrorClass(
        exception = e,
        errorClass = "ILLEGAL_SUBSTRING",
        msg = "The argument_index of string format cannot contain position 0$.; line 1 pos 7")
    }
  }

  test("INVALID_PANDAS_UDF_PLACEMENT: Using aggregate function with grouped aggregate pandas UDF") {
    import IntegratedUDFTestUtils._
    assume(shouldTestGroupedAggPandasUDFs)

    val df = Seq(
      (536361, "85123A", 2, 17850),
      (536362, "85123B", 4, 17850),
      (536363, "86123A", 6, 17851)
    ).toDF("InvoiceNo", "StockCode", "Quantity", "CustomerID")
    val e = intercept[AnalysisException] {
      val pandasTestUDF1 = TestGroupedAggPandasUDF(name = "pandas_udf_1")
      val pandasTestUDF2 = TestGroupedAggPandasUDF(name = "pandas_udf_2")
      df.groupBy("CustomerId")
        .agg(pandasTestUDF1(df("Quantity")), pandasTestUDF2(df("Quantity")), sum(df("Quantity")))
        .collect()
    }

    checkErrorClass(
      exception = e,
      errorClass = "INVALID_PANDAS_UDF_PLACEMENT",
      msg = "The group aggregate pandas UDF `pandas_udf_1`, `pandas_udf_2` cannot be invoked " +
        "together with as other, non-pandas aggregate functions.")
  }

  test("UNSUPPORTED_FEATURE: Using Python UDF with unsupported join condition") {
    import IntegratedUDFTestUtils._

    val df1 = Seq(
      (536361, "85123A", 2, 17850),
      (536362, "85123B", 4, 17850),
      (536363, "86123A", 6, 17851)
    ).toDF("InvoiceNo", "StockCode", "Quantity", "CustomerID")
    val df2 = Seq(
      ("Bob", 17850),
      ("Alice", 17850),
      ("Tom", 17851)
    ).toDF("CustomerName", "CustomerID")

    val e = intercept[AnalysisException] {
      val pythonTestUDF = TestPythonUDF(name = "python_udf")
      df1.join(
        df2, pythonTestUDF(df1("CustomerID") === df2("CustomerID")), "leftouter").collect()
    }

    checkErrorClass(
      exception = e,
      errorClass = "UNSUPPORTED_FEATURE",
      msg = "The feature is not supported: " +
        "Using PythonUDF in join condition of join type \"LEFT OUTER\" is not supported.",
      sqlState = Some("0A000"))
  }

  test("UNSUPPORTED_FEATURE: Using pandas UDF aggregate expression with pivot") {
    import IntegratedUDFTestUtils._
    assume(shouldTestGroupedAggPandasUDFs)

    val df = Seq(
      (536361, "85123A", 2, 17850),
      (536362, "85123B", 4, 17850),
      (536363, "86123A", 6, 17851)
    ).toDF("InvoiceNo", "StockCode", "Quantity", "CustomerID")

    val e = intercept[AnalysisException] {
      val pandasTestUDF = TestGroupedAggPandasUDF(name = "pandas_udf")
      df.groupBy(df("CustomerID")).pivot(df("CustomerID")).agg(pandasTestUDF(df("Quantity")))
    }

    checkErrorClass(
      exception = e,
      errorClass = "UNSUPPORTED_FEATURE",
      msg = "The feature is not supported: " +
        "Pandas UDF aggregate expressions don't support pivot.",
      sqlState = Some("0A000"))
  }

  test("NO_HANDLER_FOR_UDAF: No handler for UDAF error") {
    val functionName = "myCast"
    withUserDefinedFunction(functionName -> true) {
      sql(
        s"""
          |CREATE TEMPORARY FUNCTION $functionName
          |AS 'org.apache.spark.sql.errors.MyCastToString'
          |""".stripMargin)

      val e = intercept[AnalysisException] (
        sql(s"SELECT $functionName(123) as value")
      )
      checkErrorClass(
        exception = e,
        errorClass = "NO_HANDLER_FOR_UDAF",
        msg = "No handler for UDAF 'org.apache.spark.sql.errors.MyCastToString'. " +
          "Use sparkSession.udf.register(...) instead.; line 1 pos 7")
    }
  }

  test("UNTYPED_SCALA_UDF: use untyped Scala UDF should fail by default") {
    checkErrorClass(
      exception = intercept[AnalysisException](udf((x: Int) => x, IntegerType)),
      errorClass = "UNTYPED_SCALA_UDF",
      msg =
        "You're using untyped Scala UDF, which does not have the input type " +
        "information. Spark may blindly pass null to the Scala closure with primitive-type " +
        "argument, and the closure will see the default value of the Java type for the null " +
        "argument, e.g. `udf((x: Int) => x, IntegerType)`, the result is 0 for null input. " +
        "To get rid of this error, you could:\n" +
        "1. use typed Scala UDF APIs(without return type parameter), e.g. `udf((x: Int) => x)`\n" +
        "2. use Java UDF APIs, e.g. `udf(new UDF1[String, Integer] { " +
        "override def call(s: String): Integer = s.length() }, IntegerType)`, " +
        "if input types are all non primitive\n" +
        s"3. set ${SQLConf.LEGACY_ALLOW_UNTYPED_SCALA_UDF.key} to true and " +
        s"use this API with caution")
  }

  test("NO_UDF_INTERFACE_ERROR: java udf class does not implement any udf interface") {
    val className = "org.apache.spark.sql.errors.MyCastToString"
    val e = intercept[AnalysisException](
      spark.udf.registerJava(
        "myCast",
        className,
        StringType)
    )
    checkErrorClass(
      exception = e,
      errorClass = "NO_UDF_INTERFACE_ERROR",
      msg = s"UDF class $className doesn't implement any UDF interface")
  }

  test("MULTI_UDF_INTERFACE_ERROR: java udf implement multi UDF interface") {
    val className = "org.apache.spark.sql.errors.MySum"
    val e = intercept[AnalysisException](
      spark.udf.registerJava(
        "mySum",
        className,
        StringType)
    )
    checkErrorClass(
      exception = e,
      errorClass = "MULTI_UDF_INTERFACE_ERROR",
      msg = s"Not allowed to implement multiple UDF interfaces, UDF class $className")
  }

  test("UNSUPPORTED_FEATURE: java udf with too many type arguments") {
    val className = "org.apache.spark.sql.errors.MultiIntSum"
    val e = intercept[AnalysisException](
      spark.udf.registerJava(
        "mySum",
        className,
        StringType)
    )
    checkErrorClass(
      exception = e,
      errorClass = "UNSUPPORTED_FEATURE",
      msg = "The feature is not supported: UDF class with 24 type arguments",
      sqlState = Some("0A000"))
  }

  test("GROUPING_COLUMN_MISMATCH: not found the grouping column") {
    val groupingColMismatchEx = intercept[AnalysisException] {
      courseSales.cube("course", "year").agg(grouping("earnings")).explain()
    }
    checkErrorClass(
      exception = groupingColMismatchEx,
      errorClass = "GROUPING_COLUMN_MISMATCH",
      msg =
        "Column of grouping \\(earnings.*\\) can't be found in grouping columns course.*,year.*",
      sqlState = Some("42000"),
      matchMsg = true)
  }

  test("GROUPING_ID_COLUMN_MISMATCH: columns of grouping_id does not match") {
    val groupingIdColMismatchEx = intercept[AnalysisException] {
      courseSales.cube("course", "year").agg(grouping_id("earnings")).explain()
    }
    checkErrorClass(
      exception = groupingIdColMismatchEx,
      errorClass = "GROUPING_ID_COLUMN_MISMATCH",
      msg = "Columns of grouping_id \\(earnings.*\\) does not match " +
        "grouping columns \\(course.*,year.*\\)",
      sqlState = Some("42000"),
      matchMsg = true)
  }

  test("GROUPING_SIZE_LIMIT_EXCEEDED: max size of grouping set") {
    withTempView("t") {
      sql("CREATE TEMPORARY VIEW t AS SELECT * FROM " +
        s"VALUES(${(0 until 65).map { _ => 1 }.mkString(", ")}, 3) AS " +
        s"t(${(0 until 65).map { i => s"k$i" }.mkString(", ")}, v)")

      def testGroupingIDs(numGroupingSet: Int, expectedIds: Seq[Any] = Nil): Unit = {
        val groupingCols = (0 until numGroupingSet).map { i => s"k$i" }
        val df = sql("SELECT GROUPING_ID(), SUM(v) FROM t GROUP BY " +
          s"GROUPING SETS ((${groupingCols.mkString(",")}), (${groupingCols.init.mkString(",")}))")
        checkAnswer(df, expectedIds.map { id => Row(id, 3) })
      }

      withSQLConf(SQLConf.LEGACY_INTEGER_GROUPING_ID.key -> "true") {
        checkErrorClass(
          exception = intercept[AnalysisException] { testGroupingIDs(33) },
          errorClass = "GROUPING_SIZE_LIMIT_EXCEEDED",
          msg = "Grouping sets size cannot be greater than 32")
      }

      withSQLConf(SQLConf.LEGACY_INTEGER_GROUPING_ID.key -> "false") {
        checkErrorClass(
          exception = intercept[AnalysisException] { testGroupingIDs(65) },
          errorClass = "GROUPING_SIZE_LIMIT_EXCEEDED",
          msg = "Grouping sets size cannot be greater than 64")
      }
    }
  }

  test("FORBIDDEN_OPERATION: desc partition on a temporary view") {
    val tableName: String = "t"
    val tempViewName: String = "tempView"

    withTable(tableName) {
      sql(
        s"""
          |CREATE TABLE $tableName (a STRING, b INT, c STRING, d STRING)
          |USING parquet
          |PARTITIONED BY (c, d)
          |""".stripMargin)

      withTempView(tempViewName) {
        sql(s"CREATE TEMPORARY VIEW $tempViewName as SELECT * FROM $tableName")

        checkErrorClass(
          exception = intercept[AnalysisException] {
            sql(s"DESC TABLE $tempViewName PARTITION (c='Us', d=1)")
          },
          errorClass = "FORBIDDEN_OPERATION",
          msg = s"""The operation "DESC PARTITION" is not allowed """ +
            s"on the temporary view: `$tempViewName`")
      }
    }
  }

  test("FORBIDDEN_OPERATION: desc partition on a view") {
    val tableName: String = "t"
    val viewName: String = "view"

    withTable(tableName) {
      sql(
        s"""
           |CREATE TABLE $tableName (a STRING, b INT, c STRING, d STRING)
           |USING parquet
           |PARTITIONED BY (c, d)
           |""".stripMargin)

      withView(viewName) {
        sql(s"CREATE VIEW $viewName as SELECT * FROM $tableName")

        checkErrorClass(
          exception = intercept[AnalysisException] {
            sql(s"DESC TABLE $viewName PARTITION (c='Us', d=1)")
          },
          errorClass = "FORBIDDEN_OPERATION",
          msg = s"""The operation "DESC PARTITION" is not allowed """ +
            s"on the view: `$viewName`")
      }
    }
  }

  test("SECOND_FUNCTION_ARGUMENT_NOT_INTEGER: " +
    "the second argument of 'date_add' function needs to be an integer") {
    withSQLConf(SQLConf.ANSI_ENABLED.key -> "false") {
      checkErrorClass(
        exception = intercept[AnalysisException] {
          sql("select date_add('1982-08-15', 'x')").collect()
        },
        errorClass = "SECOND_FUNCTION_ARGUMENT_NOT_INTEGER",
        msg = "The second argument of 'date_add' function needs to be an integer.",
        sqlState = Some("22023"))
    }
  }

  test("INVALID_JSON_SCHEMA_MAPTYPE: " +
    "Parse JSON rows can only contain StringType as a key type for a MapType.") {
    val schema = StructType(
      StructField("map", MapType(IntegerType, IntegerType, true), false) :: Nil)

    checkErrorClass(
      exception = intercept[AnalysisException] {
        spark.read.schema(schema).json(spark.emptyDataset[String])
      },
      errorClass = "INVALID_JSON_SCHEMA_MAPTYPE",
      msg = "Input schema " +
        "StructType(StructField(map,MapType(IntegerType,IntegerType,true),false)) " +
        "can only contain StringType as a key type for a MapType."
    )
  }
}

class MyCastToString extends SparkUserDefinedFunction(
  (input: Any) => if (input == null) {
    null
  } else {
    input.toString
  },
  StringType,
  inputEncoders = Seq.fill(1)(None))

class MySum extends UDF1[Int, Int] with UDF2[Int, Int, Int] {
  override def call(t1: Int): Int = t1

  override def call(t1: Int, t2: Int): Int = t1 + t2
}

class MultiIntSum extends
  UDF23Test[Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int,
    Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int] {
  // scalastyle:off argcount
  override def call(
      t1: Int, t2: Int, t3: Int, t4: Int, t5: Int, t6: Int, t7: Int, t8: Int,
      t9: Int, t10: Int, t11: Int, t12: Int, t13: Int, t14: Int, t15: Int, t16: Int,
      t17: Int, t18: Int, t19: Int, t20: Int, t21: Int, t22: Int, t23: Int): Int = {
    t1 + t2 + t3 + t4 + t5 + t6 + t7 + t8 + t9 + t10 +
      t11 + t12 + t13 + t14 + t15 + t16 + t17 + t18 + t19 + t20 + t21 + t22 + t23
  }
  // scalastyle:on argcount
}
